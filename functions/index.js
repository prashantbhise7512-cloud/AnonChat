const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

exports.sendChatNotification = functions.database
  .ref('/threads/{threadId}/messages/{messageId}')
  .onCreate(async (snapshot, context) => {
    const message = snapshot.val();
    const threadId = context.params.threadId;
    const messageId = context.params.messageId;

    if (!message || !message.senderId || !message.message) {
      return null;
    }

    const threadRef = admin.database().ref(`/threads/${threadId}`);
    const threadSnap = await threadRef.once('value');
    const threadData = threadSnap.val() || {};
    const participants = Array.isArray(threadData.participants) ? threadData.participants : [];
    const recipientId = participants.find((id) => id !== message.senderId);

    if (!recipientId) {
      return null;
    }

    const recipientTokenSnap = await admin.database().ref(`/users/${recipientId}/fcmToken`).once('value');
    const recipientToken = recipientTokenSnap.val();

    if (!recipientToken) {
      return null;
    }

    const payload = {
      notification: {
        title: message.senderName || 'New message',
        body: message.message,
        icon: 'ic_notification'
      },
      data: {
        type: 'chat_message',
        recipientId,
        threadId,
        chatId: threadId,
        messageId,
        senderName: message.senderName || 'New message',
        body: message.message,
        timestamp: String(Date.now())
      }
    };

    return admin.messaging().sendToDevice(recipientToken, payload);
  });
