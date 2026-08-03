const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = 3000;

// Store messages in memory (last 100)
const messages = [];
const MAX_MESSAGES = 100;
let onlineUsers = new Set();

// --- HTTP Server (serves static files) ---
const server = http.createServer((req, res) => {
    // Serve from the web root: the auth screen lives in web/index.html, web/app.js,
    // and web/style.css. web/public/ holds a pre-auth copy of the app.
    const requestPath = decodeURIComponent(req.url.split("?")[0]);
    let filePath = path.join(__dirname, requestPath === "/" ? "/index.html" : requestPath);

    // Keep requests inside the web root.
    if (!path.resolve(filePath).startsWith(path.resolve(__dirname) + path.sep)) {
        res.writeHead(403);
        res.end("Forbidden");
        return;
    }

    const ext = path.extname(filePath);
    const mimeTypes = {
        ".html": "text/html",
        ".css": "text/css",
        ".js": "application/javascript",
        ".png": "image/png",
        ".svg": "image/svg+xml"
    };

    const contentType = mimeTypes[ext] || "text/plain";

    fs.readFile(filePath, (err, content) => {
        if (err) {
            res.writeHead(404);
            res.end("Not found");
            return;
        }
        res.writeHead(200, { "Content-Type": contentType });
        res.end(content);
    });
});

// --- WebSocket Server ---
const wss = new WebSocketServer({ server });

wss.on("connection", (ws) => {
    let currentUserId = null;

    // Send message history to new connection
    ws.send(JSON.stringify({ type: "history", messages }));

    // Send current online count
    broadcastOnlineCount();

    ws.on("message", (data) => {
        try {
            const parsed = JSON.parse(data);

            if (parsed.type === "join") {
                currentUserId = parsed.userId;
                onlineUsers.add(currentUserId);
                broadcastOnlineCount();
            }

            if (parsed.type === "message") {
                const msg = {
                    id: generateId(),
                    senderId: parsed.senderId,
                    senderName: parsed.senderName,
                    message: parsed.message,
                    timestamp: Date.now()
                };

                messages.push(msg);
                if (messages.length > MAX_MESSAGES) {
                    messages.shift();
                }

                // Broadcast to all connected clients
                const broadcast = JSON.stringify({ type: "newMessage", message: msg });
                wss.clients.forEach((client) => {
                    if (client.readyState === 1) {
                        client.send(broadcast);
                    }
                });
            }
        } catch (e) {
            // Ignore malformed messages
        }
    });

    ws.on("close", () => {
        if (currentUserId) {
            onlineUsers.delete(currentUserId);
            broadcastOnlineCount();
        }
    });
});

function broadcastOnlineCount() {
    const msg = JSON.stringify({ type: "onlineCount", count: onlineUsers.size });
    wss.clients.forEach((client) => {
        if (client.readyState === 1) {
            client.send(msg);
        }
    });
}

function generateId() {
    return Date.now().toString(36) + Math.random().toString(36).substring(2, 8);
}

server.listen(PORT, () => {
    console.log(`\n  ✅ AnonChat server running!\n`);
    console.log(`  Open in browser: http://localhost:${PORT}\n`);
    console.log(`  Open multiple tabs to test multi-user chat.\n`);
});
