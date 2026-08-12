// ===== AnonChat - Phone OTP Authentication + Random 1-on-1 Matching =====
// Flow: Auth Screen → Welcome → Chat List → Searching → Matched Chat Room

// ========== TEST MODE ==========
// Set to true to bypass Firebase Auth entirely (any phone + any code works, including empty)
// Set to false when ready for production with real Firebase Auth
const TEST_MODE = false;

// Local stand-in for an auth session while TEST_MODE is active. The key names and the
// account id derivation below MUST stay identical to TestSession.kt on Android so the same
// phone number produces the same account on both clients.
// Remove this block (and the TEST_MODE branches that use it) before shipping.
const TestSession = {
    KEY_ACTIVE: "anonchat_test_active",
    KEY_UID: "anonchat_test_uid",
    KEY_PHONE: "anonchat_test_phone",
    PROFILE_PREFIX: "anonchat_test_profile_",

    // Shared derivation: "test_" + every digit of the full E.164 number.
    deriveAccountId: function (phoneNumber) {
        const digits = (phoneNumber || "").replace(/\D/g, "");
        return digits ? "test_" + digits : "test_anonymous";
    },

    isActive: function () {
        return localStorage.getItem(this.KEY_ACTIVE) === "true";
    },

    signIn: function (phoneNumber) {
        const uid = this.deriveAccountId(phoneNumber);
        localStorage.setItem(this.KEY_ACTIVE, "true");
        localStorage.setItem(this.KEY_UID, uid);
        localStorage.setItem(this.KEY_PHONE, phoneNumber || "");
        return uid;
    },

    // Clears only the active flag, so the uid and cached profile survive a re-login.
    signOut: function () {
        localStorage.setItem(this.KEY_ACTIVE, "false");
    },

    uid: function () {
        return localStorage.getItem(this.KEY_UID) || "test_anonymous";
    },

    profileKey: function (accountId) {
        return this.PROFILE_PREFIX + accountId;
    },

    cachedProfile: function (accountId) {
        try {
            return JSON.parse(localStorage.getItem(this.profileKey(accountId)) || "null");
        } catch (e) {
            return null;
        }
    },

    cachedDisplayName: function (accountId) {
        const profile = this.cachedProfile(accountId);
        return (profile && profile.displayName) || null;
    }
};
// ===============================

// === Firebase Configuration (placeholder — replace with real project config) ===
const firebaseConfig = {
    apiKey: "AIzaSyDvq7jeUVNAZoFX0iQFloiYwk1NkxKOtFw",
    authDomain: "anonchat-8aabe.firebaseapp.com",
    databaseURL: "https://anonchat-8aabe-default-rtdb.firebaseio.com",
    projectId: "anonchat-8aabe",
    storageBucket: "anonchat-8aabe.firebasestorage.app",
    messagingSenderId: "724303260069",
    appId: "1:724303260069:android:3340e8e3955fbbddbde04c"
};

if (!TEST_MODE) {
    firebase.initializeApp(firebaseConfig);
}

// === Firebase Auth Initialization ===
const auth = TEST_MODE ? null : firebase.auth();
let recaptchaVerifier = null;
let confirmationResult = null;
let resendCountdownInterval = null;
let resendSecondsLeft = 0;
let testModePhone = null;

// Auth DOM elements
const authScreen = document.getElementById("authScreen");
const authStepPhone = document.getElementById("authStepPhone");
const authStepOtp = document.getElementById("authStepOtp");
const countryCodeSelect = document.getElementById("countryCodeSelect");
const phoneInput = document.getElementById("phoneInput");
const btnSendCode = document.getElementById("btnSendCode");
const otpInput = document.getElementById("otpInput");
const otpPhoneDisplay = document.getElementById("otpPhoneDisplay");
const btnVerifyOtp = document.getElementById("btnVerifyOtp");
const btnResendCode = document.getElementById("btnResendCode");
const resendCountdown = document.getElementById("resendCountdown");
const authLoading = document.getElementById("authLoading");
const authError = document.getElementById("authError");
const authErrorMessage = document.getElementById("authErrorMessage");

// === Auth Helper Functions ===
function showAuthLoading(show) {
    if (show) {
        authLoading.classList.remove("hidden");
    } else {
        authLoading.classList.add("hidden");
    }
}

function showAuthError(message) {
    authErrorMessage.textContent = message;
    authError.classList.remove("hidden");
}

function hideAuthError() {
    authError.classList.add("hidden");
    authErrorMessage.textContent = "";
}

function validatePhoneNumber(fullNumber) {
    // E.164 format: "+" followed by 7-15 digits
    const e164Regex = /^\+\d{7,15}$/;
    return e164Regex.test(fullNumber);
}

function getFullPhoneNumber() {
    const countryCode = countryCodeSelect.value;
    const phone = phoneInput.value.trim().replace(/\s+/g, "").replace(/^0+/, "");
    return countryCode + phone;
}

// === reCAPTCHA Setup (Task 2.3) ===
function setupRecaptcha() {
    // Set up a 10-second timeout for reCAPTCHA readiness
    let recaptchaReady = false;
    const recaptchaTimeout = setTimeout(() => {
        if (!recaptchaReady) {
            showAuthError("Verification could not be loaded. Please check for browser extensions or network issues blocking the script.");
            btnSendCode.disabled = true;
        }
    }, 10000);

    try {
        recaptchaVerifier = new firebase.auth.RecaptchaVerifier("btnSendCode", {
            size: "invisible",
            callback: function (response) {
                // reCAPTCHA solved — enable send code button
                recaptchaReady = true;
                clearTimeout(recaptchaTimeout);
                btnSendCode.disabled = false;
            },
            "expired-callback": function () {
                // reCAPTCHA expired, re-render
                btnSendCode.disabled = true;
                recaptchaVerifier.render().then(function () {
                    // Will trigger callback again once ready
                });
            }
        });

        recaptchaVerifier.render().then(function (widgetId) {
            // reCAPTCHA widget rendered — it will call callback when ready
            // For invisible reCAPTCHA, it signals ready immediately in most cases
            recaptchaReady = true;
            clearTimeout(recaptchaTimeout);
            btnSendCode.disabled = false;
        }).catch(function (error) {
            // reCAPTCHA failed to render
            clearTimeout(recaptchaTimeout);
            showAuthError("Verification could not be loaded. Please check for browser extensions or network issues blocking the script.");
            btnSendCode.disabled = true;
        });
    } catch (error) {
        clearTimeout(recaptchaTimeout);
        showAuthError("Verification could not be loaded. Please check for browser extensions or network issues blocking the script.");
        btnSendCode.disabled = true;
    }
}

// === Phone Number Submission & OTP Send (Task 2.4) ===
function startResendCountdown() {
    resendSecondsLeft = 60;
    btnResendCode.disabled = true;
    resendCountdown.textContent = "(" + resendSecondsLeft + "s)";

    resendCountdownInterval = setInterval(function () {
        resendSecondsLeft--;
        if (resendSecondsLeft <= 0) {
            clearInterval(resendCountdownInterval);
            resendCountdownInterval = null;
            resendCountdown.textContent = "";
            btnResendCode.disabled = false;
        } else {
            resendCountdown.textContent = "(" + resendSecondsLeft + "s)";
        }
    }, 1000);
}

async function sendOTP() {
    hideAuthError();

    const fullPhone = getFullPhoneNumber();

    // Validate phone number
    if (!phoneInput.value.trim()) {
        showAuthError("Enter a valid phone number with country code");
        return;
    }

    if (!validatePhoneNumber(fullPhone)) {
        showAuthError("Enter a valid phone number with country code");
        return;
    }

    // Disable button and show loading
    btnSendCode.disabled = true;
    showAuthLoading(true);

    // 15-second timeout
    let timedOut = false;
    const sendTimeout = setTimeout(function () {
        timedOut = true;
        showAuthLoading(false);
        btnSendCode.disabled = false;
        showAuthError("Request timed out. Please try again.");
    }, 15000);

    try {
        const result = await auth.signInWithPhoneNumber(fullPhone, recaptchaVerifier);

        if (timedOut) return; // Ignore late response
        clearTimeout(sendTimeout);

        confirmationResult = result;
        showAuthLoading(false);

        // Transition to OTP step
        authStepPhone.classList.remove("auth-step-active");
        authStepOtp.classList.add("auth-step-active");

        // Display the phone number in the OTP step
        otpPhoneDisplay.textContent = fullPhone;

        // Start 60-second resend countdown
        startResendCountdown();

    } catch (error) {
        if (timedOut) return; // Ignore late response
        clearTimeout(sendTimeout);

        showAuthLoading(false);
        btnSendCode.disabled = false;

        // Map Firebase errors to user-friendly messages
        if (error.code === "auth/invalid-phone-number") {
            showAuthError("Enter a valid phone number with country code");
        } else if (error.code === "auth/too-many-requests") {
            showAuthError("Too many attempts. Please wait before trying again.");
        } else if (error.code === "auth/network-request-failed") {
            showAuthError("No internet connection. Please check your network.");
        } else if (error.code === "auth/captcha-check-failed") {
            showAuthError("Verification failed. Please refresh the page.");
            btnSendCode.disabled = true;
        } else {
            showAuthError("Could not send code. Please try again.");
        }
    }
}

// === Event Listeners for Auth ===
btnSendCode.addEventListener("click", function () {
    if (TEST_MODE) {
        testModeSendCode();
    } else {
        sendOTP();
    }
});

phoneInput.addEventListener("input", function () {
    hideAuthError();
    if (TEST_MODE) {
        // Test mode: any phone number is accepted, so the button stays enabled.
        btnSendCode.disabled = false;
    }
});

otpInput.addEventListener("input", function () {
    hideAuthError();
});

// === TEST MODE: OTP Verify button ===
btnVerifyOtp.addEventListener("click", function () {
    if (TEST_MODE) {
        testModeVerify();
    }
});

// === TEST MODE FUNCTIONS ===
function testModeSendCode() {
    hideAuthError();
    console.log("[TEST MODE] Send Code clicked — transitioning to OTP step");

    // Any phone number is accepted, including an empty field. No validation, no SMS.
    testModePhone = getFullPhoneNumber();

    // Transition to OTP step immediately
    authStepPhone.classList.remove("auth-step-active");
    authStepOtp.classList.add("auth-step-active");
    otpPhoneDisplay.textContent = testModePhone;
    startResendCountdown();
}

function testModeVerify() {
    hideAuthError();

    // Any code is accepted, including an empty field.
    var accountId;
    try {
        accountId = TestSession.signIn(testModePhone);
    } catch (e) {
        // localStorage blocked — derive id without persisting.
        accountId = TestSession.deriveAccountId(testModePhone);
    }

    authScreen.style.display = "none";
    initChatApp(accountId, TestSession.cachedDisplayName(accountId) || "AnnoUser");
}

// === ONE-TIME MIGRATION: Clear old saved chats with random names ===
(function() {
    try {
        const migrated = localStorage.getItem("anonchat_migrated_v2");
        if (!migrated) {
            localStorage.removeItem("anonchat_saved_chats");
            localStorage.removeItem("anonchat_messages");
            localStorage.removeItem("anonchat_queue");
            localStorage.removeItem("anonchat_sessions");
            localStorage.removeItem("anonchat_online");
            localStorage.setItem("anonchat_migrated_v2", "true");
        }
    } catch (e) {
        // localStorage may be unavailable (file:// protocol).
    }
})();

// === Firebase Auth State Persistence and Initialization ===
if (TEST_MODE) {
    // Test mode: route on the local session instead of Firebase Auth.
    try {
        const testNotice = document.getElementById("authTestModeNotice");
        if (testNotice) testNotice.classList.remove("hidden");

        if (TestSession.isActive()) {
            // Already "logged in" — skip auth screen
            const savedUid = TestSession.uid();
            authScreen.style.display = "none";
            initChatApp(savedUid, TestSession.cachedDisplayName(savedUid) || "AnnoUser");
        } else {
            // Show auth screen, enable Send Code button immediately (no reCAPTCHA needed)
            authScreen.style.display = "";
            authScreen.classList.remove("hidden");
            btnSendCode.disabled = false;
        }
    } catch (e) {
        // localStorage might be blocked on file:// — show auth screen anyway.
        console.warn("TestSession bootstrap error:", e);
        authScreen.style.display = "";
        authScreen.classList.remove("hidden");
        btnSendCode.disabled = false;
    }
} else {
    auth.setPersistence(firebase.auth.Auth.Persistence.LOCAL).then(function () {
        // Check auth state on load
        auth.onAuthStateChanged(function (user) {
            if (user) {
                authScreen.style.display = "none";
                initChatApp(user.uid, "AnnoUser");
            } else {
                authScreen.style.display = "";
                authScreen.classList.remove("hidden");
                setupRecaptcha();
            }
        });
    }).catch(function (error) {
        // Fallback: show auth screen anyway
        authScreen.style.display = "";
        authScreen.classList.remove("hidden");
        setupRecaptcha();
    });
}

// === Chat App Logic (runs after successful authentication) ===
function initChatApp(authenticatedUserId, authenticatedUserName) {
    // Stable account id — same value Android derives from the same phone number.
    // Used for profile data, avatar, and the id shown on the Profile screen.
    const accountId = authenticatedUserId;

    // Matchmaking identity. In test mode every browser tab shares one localStorage, so a
    // per-tab suffix is appended here (and ONLY here) to stop a tab matching itself.
    const userId = TEST_MODE ? accountId + "_" + Math.random().toString(36).substring(2, 6) : accountId;

    let userName = authenticatedUserName;
    const channel = new BroadcastChannel("anonchat_room");
    const SAVED_KEY = "anonchat_saved_chats";
    const QUEUE_KEY = "anonchat_queue";
    const SESSIONS_KEY = "anonchat_sessions";

    let allMessages = [];
    let currentSessionId = null;
    let currentPartner = null;
    let currentPartnerAccountId = null;
    let searchInterval = null;
    let heartbeatInterval = null;

    // === LAST ACTIVE TRACKING ===
    // Update lastActive timestamp periodically so other users can see when we were online
    function updateLastActive() {
        const now = Date.now();
        try { localStorage.setItem("anonchat_last_active_" + accountId, now.toString()); } catch(e) {}
        if (!TEST_MODE) {
            firebase.database().ref("/users/" + accountId + "/lastActive").set(now);
        }
    }
    updateLastActive();
    setInterval(updateLastActive, 60000); // Update every minute

    // === DOM ===
    const $ = id => document.getElementById(id);
    const welcomeScreen = $("welcomeScreen");
    const chatListScreen = $("chatListScreen");
    const chatScreen = $("chatScreen");
    const savedChatView = $("savedChatView");

    const welcomeUsername = $("welcomeNameInput");
    const btnEnterApp = $("btnEnterApp");
    const listIdentity = $("listIdentity");
    const btnJoinRoom = $("btnJoinRoom");
    const savedChatsList = $("savedChatsList");
    const noSavedChats = $("noSavedChats");

    const identityBadge = $("identityBadge");
    const onlineCount = $("onlineCount");
    const chatHeaderTitle = $("chatHeaderTitle");
    const headerOnline = $("headerOnline");
    const messagesContainer = $("messagesContainer");
    const emptyState = $("emptyState");
    const messageInput = $("messageInput");
    const btnSend = $("btnSend");
    const btnSaveChat = $("btnSaveChat");
    const btnLeaveChat = $("btnLeaveChat");
    const btnNewChat = $("btnNewChat");
    const btnBackToList = $("btnBackToList");

    const savedMessagesContainer = $("savedMessagesContainer");
    const savedChatTitle = $("savedChatTitle");
    const savedChatStatus = $("savedChatStatus");
    const savedChatAvatar = $("savedChatAvatar");
    const btnBackFromSaved = $("btnBackFromSaved");
    const btnDeleteSaved = $("btnDeleteSaved");
    const btnSavedMenu = $("btnSavedMenu");
    const savedMenuDropdown = $("savedMenuDropdown");
    const btnDeleteFromMenu = $("btnDeleteFromMenu");
    const btnBlockUser = $("btnBlockUser");
    const savedMessageInput = $("savedMessageInput");
    const btnSavedSend = $("btnSavedSend");

    let currentSavedChatId = null;

    // === PROFILE DOM ===
    const tabChats = $("tabChats");
    const tabProfile = $("tabProfile");
    const chatsSection = $("chatsSection");
    const profileSection = $("profileSection");
    const profileName = $("profileName");
    const profileGender = $("profileGender");
    const profileAge = $("profileAge");
    const profileCity = $("profileCity");
    const nameError = $("nameError");
    const ageError = $("ageError");
    const cityError = $("cityError");
    const btnSaveProfile = $("btnSaveProfile");
    const profileSaveStatus = $("profileSaveStatus");

    // === PROFILE TAB SWITCHING ===
    tabChats.addEventListener("click", () => {
        tabChats.classList.add("tab-active");
        tabProfile.classList.remove("tab-active");
        chatsSection.classList.remove("hidden");
        profileSection.classList.add("hidden");
    });

    tabProfile.addEventListener("click", () => {
        tabProfile.classList.add("tab-active");
        tabChats.classList.remove("tab-active");
        profileSection.classList.remove("hidden");
        chatsSection.classList.add("hidden");
    });

    // === PROFILE PICTURE ===
    const avatarInput = $("avatarInput");
    const avatarImage = $("avatarImage");
    const avatarPlaceholder = $("avatarPlaceholder");
    const profileUid = $("profileUid");
    const AVATAR_KEY = "anonchat_avatar_" + accountId;

    // Show unique ID below profile picture (not editable)
    profileUid.textContent = "ID: " + accountId;

    // Load saved avatar from localStorage
    const savedAvatar = localStorage.getItem(AVATAR_KEY);
    if (savedAvatar) {
        avatarImage.src = savedAvatar;
        avatarImage.classList.remove("hidden");
        avatarPlaceholder.classList.add("hidden");
    }

    avatarInput.addEventListener("change", function(e) {
        const file = e.target.files[0];
        if (!file) return;

        // Validate file type and size (max 2MB)
        if (!file.type.startsWith("image/")) {
            alert("Please select an image file.");
            return;
        }
        if (file.size > 2 * 1024 * 1024) {
            alert("Image must be less than 2MB.");
            return;
        }

        const reader = new FileReader();
        reader.onload = function(event) {
            const dataUrl = event.target.result;
            avatarImage.src = dataUrl;
            avatarImage.classList.remove("hidden");
            avatarPlaceholder.classList.add("hidden");

            // Save to localStorage (base64)
            localStorage.setItem(AVATAR_KEY, dataUrl);

            // Also save to Firebase so other users can view it
            if (!TEST_MODE && profileRef) {
                firebase.database().ref("/users/" + accountId + "/avatar").set(dataUrl);
            }
        };
        reader.readAsDataURL(file);
    });

    // === PROFILE READ ON INIT ===
    const PROFILE_DATA_KEY = TestSession.profileKey(accountId);
    let profileRef = null;

    if (!TEST_MODE) {
        profileRef = firebase.database().ref("/users/" + accountId + "/profile");
    }

    function loadProfile() {
        if (TEST_MODE) {
            // Load from localStorage in test mode
            const savedProfile = localStorage.getItem(PROFILE_DATA_KEY);
            if (savedProfile) {
                const data = JSON.parse(savedProfile);
                profileName.value = data.displayName || "AnnoUser";
                profileGender.value = data.gender || "";
                profileAge.value = (data.age !== null && data.age !== undefined) ? data.age : "";
                profileCity.value = data.city || "";
                userName = data.displayName || "AnnoUser";
            } else {
                profileName.value = "AnnoUser";
                profileGender.value = "";
                profileAge.value = "";
                profileCity.value = "";
                userName = "AnnoUser";
            }
            return Promise.resolve();
        }

        return profileRef.once("value").then(function (snapshot) {
            const data = snapshot.val();
            if (data) {
                profileName.value = data.displayName || "AnnoUser";
                profileGender.value = data.gender || "";
                profileAge.value = (data.age !== null && data.age !== undefined) ? data.age : "";
                profileCity.value = data.city || "";
                userName = data.displayName || "AnnoUser";
            } else {
                profileName.value = "AnnoUser";
                profileGender.value = "";
                profileAge.value = "";
                profileCity.value = "";
                userName = "AnnoUser";
            }
        }).catch(function () {
            // Database unreachable — fall back to the locally cached name.
            userName = TestSession.cachedDisplayName(accountId) || "AnnoUser";
            profileName.value = userName;
        });
    }

    // === PROFILE VALIDATION ===
    function validateProfile() {
        let valid = true;
        nameError.textContent = "";
        ageError.textContent = "";
        cityError.textContent = "";

        const name = profileName.value.trim();
        const age = profileAge.value.trim();
        const city = profileCity.value.trim();

        // Name: required, max 50 chars
        if (!name) {
            nameError.textContent = "Display name is required";
            valid = false;
        } else if (name.length > 50) {
            nameError.textContent = "Max 50 characters";
            valid = false;
        }

        // Age: 13-120 or empty
        if (age !== "") {
            const ageNum = parseInt(age, 10);
            if (isNaN(ageNum) || ageNum < 13 || ageNum > 120) {
                ageError.textContent = "Age must be between 13 and 120";
                valid = false;
            }
        }

        // City: max 100 chars or empty
        if (city.length > 100) {
            cityError.textContent = "Max 100 characters";
            valid = false;
        }

        return valid;
    }

    // === PROFILE SAVE ===
    btnSaveProfile.addEventListener("click", function () {
        profileSaveStatus.textContent = "";

        if (!validateProfile()) {
            return;
        }

        const name = profileName.value.trim();
        const gender = profileGender.value || null;
        const age = profileAge.value.trim() ? parseInt(profileAge.value.trim(), 10) : null;
        const city = profileCity.value.trim() || null;

        const profileData = {
            displayName: name,
            gender: gender,
            age: age,
            city: city
        };

        btnSaveProfile.disabled = true;
        profileSaveStatus.textContent = "Saving...";

        if (TEST_MODE) {
            // Save to localStorage in test mode
            localStorage.setItem(PROFILE_DATA_KEY, JSON.stringify(profileData));
            userName = name;
            profileSaveStatus.textContent = "Profile saved!";
            btnSaveProfile.disabled = false;
            if (welcomeUsername) welcomeUsername.value = userName;
            listIdentity.textContent = userName;
        } else {
            profileRef.set(profileData).then(function () {
                userName = name;
                // Cache locally so the name survives a reload without a database round trip.
                localStorage.setItem(PROFILE_DATA_KEY, JSON.stringify(profileData));
                profileSaveStatus.textContent = "Profile saved!";
                btnSaveProfile.disabled = false;
                if (welcomeUsername) welcomeUsername.value = userName;
                listIdentity.textContent = userName;
            }).catch(function () {
                profileSaveStatus.textContent = "Failed to save. Please try again.";
                btnSaveProfile.disabled = false;
            });
        }
    });

    // Load profile on init — then set up displays
    loadProfile().then(function () {
        if (welcomeUsername) welcomeUsername.value = userName;
    });

    // === BLOCKED USERS LIST ===
    const btnShowBlocked = document.getElementById("btnShowBlocked");
    const blockedScreen = document.getElementById("blockedScreen");
    const btnBackFromBlocked = document.getElementById("btnBackFromBlocked");
    const blockedListContainer = document.getElementById("blockedUsersList");

    btnShowBlocked.addEventListener("click", () => {
        // Show the blocked screen, hide profile and chats sections
        profileSection.classList.add("hidden");
        chatsSection.classList.add("hidden");
        blockedScreen.classList.remove("hidden");
        renderBlockedUsers();
    });

    btnBackFromBlocked.addEventListener("click", () => {
        blockedScreen.classList.add("hidden");
        profileSection.classList.remove("hidden");
    });

    function renderBlockedUsers() {
        const blockedList = document.getElementById("blockedUsersList");
        const noBlocked = document.getElementById("noBlockedUsers");
        const blocked = JSON.parse(localStorage.getItem("anonchat_blocked") || "[]");

        // Remove old items
        blockedList.querySelectorAll(".blocked-user-item").forEach(el => el.remove());

        if (blocked.length === 0) {
            noBlocked.style.display = "block";
            return;
        }
        noBlocked.style.display = "none";

        blocked.forEach(uid => {
            const item = document.createElement("div");
            item.className = "blocked-user-item";
            item.innerHTML = `
                <span class="blocked-user-id">${uid}</span>
                <button class="btn-unblock" data-uid="${uid}">Unblock</button>
            `;
            blockedList.appendChild(item);
        });

        // Unblock handlers
        blockedList.querySelectorAll(".btn-unblock").forEach(btn => {
            btn.addEventListener("click", function () {
                const uidToUnblock = this.getAttribute("data-uid");
                const updated = JSON.parse(localStorage.getItem("anonchat_blocked") || "[]")
                    .filter(id => id !== uidToUnblock);
                localStorage.setItem("anonchat_blocked", JSON.stringify(updated));
                renderBlockedUsers();
            });
        });
    }

    // === INIT ===

    // === SCREEN NAVIGATION ===
    function showScreen(screen) {
        [welcomeScreen, chatListScreen, chatScreen, savedChatView].forEach(s => s.classList.add("hidden"));
        screen.classList.remove("hidden");
    }

    // Show welcome screen after auth (only on first login, skip if profile already exists).
    // In test mode the Profile screen is the only place a display name is set, matching Android.
    const hasProfile = TEST_MODE ? true : TestSession.cachedDisplayName(accountId);
    if (hasProfile && hasProfile !== "AnnoUser") {
        // Returning user — skip welcome, go to chat list
        showScreen(chatListScreen);
        listIdentity.textContent = userName;
        renderSavedChats();
    } else {
        // First login — show welcome to set display name
        showScreen(welcomeScreen);
        const welcomeNameInput = document.getElementById("welcomeNameInput");
        welcomeNameInput.value = userName === "AnnoUser" ? "" : userName;
    }

    // === WELCOME → CHAT LIST ===
    btnEnterApp.addEventListener("click", () => {
        const welcomeNameInput = document.getElementById("welcomeNameInput");
        const enteredName = welcomeNameInput.value.trim() || "AnnoUser";

        // Save the display name
        userName = enteredName;

        // Also save to profile data
        const existingProfile = TestSession.cachedProfile(accountId) || {};
        existingProfile.displayName = userName;
        localStorage.setItem(PROFILE_DATA_KEY, JSON.stringify(existingProfile));

        // Update profile form if loaded
        const profileNameEl = document.getElementById("profileName");
        if (profileNameEl) profileNameEl.value = userName;

        showScreen(chatListScreen);
        listIdentity.textContent = userName;
        renderSavedChats();
    });

    // === LOGOUT ===
    const btnLogout = document.getElementById("btnLogout");
    btnLogout.addEventListener("click", () => {
        if (confirm("Logout? You'll need to verify your phone again.")) {
            if (TEST_MODE) {
                // Clears only the active flag; uid and cached profile survive for re-login.
                TestSession.signOut();
                window.location.reload();
            } else {
                firebase.auth().signOut().then(() => {
                    window.location.reload();
                }).catch(() => {
                    window.location.reload();
                });
            }
        }
    });

    // === CHAT LIST → SEARCH FOR PARTNER ===
    btnJoinRoom.addEventListener("click", () => {
        showScreen(chatScreen);
        identityBadge.textContent = userName;
        allMessages = [];
        messagesContainer.querySelectorAll(".message-row").forEach(el => el.remove());
        currentSessionId = null;
        currentPartner = null;
        currentPartnerAccountId = null;
        messageInput.disabled = true;
        btnSend.disabled = true;
        btnSaveChat.style.display = "none";
        btnNewChat.style.display = "none";
        showSearchingState();
        startSearching();
    });

    function showSearchingState() {
        // Show loader in the messages area
        chatHeaderTitle.textContent = "Searching...";
        headerOnline.style.display = "none";
        emptyState.style.display = "flex";
        emptyState.querySelector(".empty-title").textContent = "";
        emptyState.querySelector(".empty-subtitle").textContent = "Looking for a random stranger";
        // Add a spinner to empty state
        let spinner = emptyState.querySelector(".search-spinner");
        if (!spinner) {
            spinner = document.createElement("div");
            spinner.className = "search-spinner";
            emptyState.insertBefore(spinner, emptyState.firstChild);
        }
        spinner.style.display = "block";
        // Hide the default SVG icon
        const svgIcon = emptyState.querySelector("svg");
        if (svgIcon) svgIcon.style.display = "none";
    }

    function showConnectedState(partnerName) {
        // Show partner name in header, hide "Chat Room"
        chatHeaderTitle.textContent = partnerName;
        headerOnline.style.display = "flex";
        onlineCount.textContent = "Online";

        // Hide empty state and spinner
        emptyState.style.display = "none";
        const spinner = emptyState.querySelector(".search-spinner");
        if (spinner) spinner.style.display = "none";
        const svgIcon = emptyState.querySelector("svg");
        if (svgIcon) svgIcon.style.display = "";

        messageInput.disabled = false;
        messageInput.focus();
        btnSaveChat.style.display = "";
        btnNewChat.style.display = "none";
    }

    // === MATCHING SYSTEM (Firebase Realtime Database) ===
    var dbRef = null;
    var queueRef = null;
    var sessionsRef = null;

    if (!TEST_MODE) {
        dbRef = firebase.database();
        queueRef = dbRef.ref("queue");
        sessionsRef = dbRef.ref("sessions");
    }

    function startSearching() {
        if (TEST_MODE) {
            // Test mode: use localStorage + BroadcastChannel (same browser only)
            startSearchingLocal();
            return;
        }

        // Firebase matchmaking (cross-device)
        queueRef.orderByChild("joinedAt").limitToFirst(1)
            .once("value").then(function(snapshot) {
                if (snapshot.numChildren() > 0) {
                    var partnerKey = null;
                    var partnerData = null;
                    snapshot.forEach(function(child) {
                        partnerKey = child.key;
                        partnerData = child.val();
                    });

                    // Don't match with self
                    if (partnerData.userId === userId) {
                        // Remove stale self-entry and re-add
                        queueRef.child(partnerKey).remove();
                        addSelfToQueue();
                        return;
                    }

                    // Remove partner from queue
                    queueRef.child(partnerKey).remove();

                    // Create session
                    var sessionId = generateId();
                    var sessionData = {
                        user1: { userId: userId, userName: userName, accountId: accountId },
                        user2: { userId: partnerData.userId, userName: partnerData.userName, accountId: partnerData.accountId || "" },
                        createdAt: firebase.database.ServerValue.TIMESTAMP,
                        active: true
                    };
                    sessionsRef.child(sessionId).set(sessionData);

                    currentPartnerAccountId = partnerData.accountId || null;
                    connectToSession(sessionId, partnerData.userName);
                } else {
                    // No one waiting — add self to queue and wait
                    addSelfToQueue();
                }
            });
    }

    function addSelfToQueue() {
        queueRef.child(userId).set({
            userId: userId,
            userName: userName,
            accountId: accountId,
            joinedAt: firebase.database.ServerValue.TIMESTAMP
        });
        waitForMatch();
    }

    function waitForMatch() {
        // Listen for sessions where we are user2
        sessionsRef.orderByChild("user2/userId").equalTo(userId)
            .on("child_added", function(snapshot) {
                var session = snapshot.val();
                if (!session || !session.active) return;
                var sessionId = snapshot.key;
                var partnerName = session.user1.userName || "Stranger";
                currentPartnerAccountId = session.user1.accountId || null;
                connectToSession(sessionId, partnerName);
            });
    }

    // localStorage fallback for TEST_MODE
    function startSearchingLocal() {
        const queue = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
        const now = Date.now();
        const freshQueue = queue.filter(q => now - q.joinedAt < 15000 && q.userId !== userId);

        if (freshQueue.length > 0) {
            const partner = freshQueue.shift();
            localStorage.setItem(QUEUE_KEY, JSON.stringify(freshQueue));

            const sessionId = generateId();
            const sessions = JSON.parse(localStorage.getItem(SESSIONS_KEY) || "{}");
            sessions[sessionId] = { id: sessionId, users: [{ userId, userName }, { userId: partner.userId, userName: partner.userName }], createdAt: Date.now() };
            localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));

            channel.postMessage({ type: "matched", sessionId, initiator: { userId, userName }, partner: { userId: partner.userId, userName: partner.userName } });
            connectToSession(sessionId, partner.userName);
        } else {
            freshQueue.push({ userId, userName, joinedAt: Date.now() });
            localStorage.setItem(QUEUE_KEY, JSON.stringify(freshQueue));

            searchInterval = setInterval(() => {
                const q = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
                const stillWaiting = q.find(item => item.userId === userId);
                if (!stillWaiting && !currentSessionId) {}
            }, 1000);
        }
    }

    function connectToSession(sessionId, partnerName) {
        if (searchInterval) { clearInterval(searchInterval); searchInterval = null; }
        // Remove self from queue
        if (!TEST_MODE) {
            queueRef.child(userId).remove();
            // Stop listening for matches
            sessionsRef.orderByChild("user2/userId").equalTo(userId).off();
        }

        currentSessionId = sessionId;
        currentPartner = partnerName;
        showConnectedState(partnerName);

        // Share profile (BroadcastChannel for same browser, Firebase for cross-device)
        if (TEST_MODE) {
            channel.postMessage({
                type: "profileShare", sessionId: sessionId, senderId: userId,
                accountId: accountId, profile: TestSession.cachedProfile(accountId)
            });
        } else {
            // Store our profile in the session for the partner to read
            sessionsRef.child(sessionId).child("profiles").child(accountId).set(
                TestSession.cachedProfile(accountId) || { displayName: userName }
            );
            // Listen for partner's profile
            sessionsRef.child(sessionId).child("profiles").on("child_added", function(snap) {
                if (snap.key !== accountId) {
                    currentPartnerAccountId = snap.key;
                    var profile = snap.val();
                    if (profile) {
                        try { localStorage.setItem(TestSession.PROFILE_PREFIX + snap.key, JSON.stringify(profile)); } catch(e) {}
                    }
                }
            });
        }

        // Listen for messages via Firebase
        if (!TEST_MODE) {
            listenForFirebaseMessages(sessionId);
            listenForPartnerDisconnect(sessionId);
            listenForBothSaved(sessionId);
        }

        heartbeatInterval = setInterval(() => {}, 5000);
    }

    function listenForFirebaseMessages(sessionId) {
        sessionsRef.child(sessionId).child("messages").orderByChild("timestamp")
            .on("child_added", function(snapshot) {
                var msg = snapshot.val();
                if (!msg || msg.senderId === userId) return;
                // Avoid duplicates
                if (allMessages.find(m => m.id === msg.id)) return;
                allMessages.push(msg);
                addMessageToUI(messagesContainer, msg, userId);
                // Mark as delivered + read (we're viewing the chat)
                sessionsRef.child(sessionId).child("messages").child(snapshot.key).update({ status: "read" });
                // Update tick for sender
                updateTickInUI(msg.id, "read");
            });

        // Listen for status changes on our own messages
        sessionsRef.child(sessionId).child("messages").on("child_changed", function(snapshot) {
            var msg = snapshot.val();
            if (msg && msg.senderId === userId && msg.status) {
                updateTickInUI(msg.id, msg.status);
                var local = allMessages.find(m => m.id === msg.id);
                if (local) local.status = msg.status;
            }
        });
    }

    function listenForPartnerDisconnect(sessionId) {
        sessionsRef.child(sessionId).child("active").on("value", function(snapshot) {
            var active = snapshot.val();
            if (active === false && currentSessionId === sessionId) {
                addSystemMessage("User has left the chat");
                messageInput.disabled = true;
                btnSend.disabled = true;
                btnSaveChat.style.display = "none";
                btnNewChat.style.display = "";
                onlineCount.textContent = "Disconnected";
                headerOnline.style.display = "flex";
                currentSessionId = null;
                currentPartner = null;
            }
        });
    }

    function listenForBothSaved(sessionId) {
        firebase.database().ref("sessions/" + sessionId + "/savedBy").on("value", function(snapshot) {
            var savers = [];
            snapshot.forEach(function(child) { savers.push(child.key); });
            var iSaved = savers.includes(userId);
            var partnerSaved = savers.some(function(id) { return id !== userId; });

            if (iSaved && partnerSaved) {
                // Both agreed — save locally
                performSaveChat();
                addSystemMessage("✅ Chat saved by both users");
            } else if (partnerSaved && !iSaved) {
                addGreenSystemMessage("💾 Partner has saved chat");
                btnSaveChat.style.display = "";  // Re-show save button so user can confirm
            }
        });
    }

    function addGreenSystemMessage(text) {
        const row = document.createElement("div");
        row.className = "message-row";
        row.style.alignSelf = "center";
        row.style.maxWidth = "100%";
        const bubble = document.createElement("div");
        bubble.className = "message-bubble";
        bubble.style.background = "#E8F5E9";
        bubble.style.color = "#2E7D32";
        bubble.style.borderRadius = "12px";
        bubble.style.fontSize = "0.85rem";
        bubble.style.textAlign = "center";
        bubble.style.fontWeight = "600";
        bubble.textContent = text;
        row.appendChild(bubble);
        messagesContainer.appendChild(row);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    // === BROADCAST CHANNEL LISTENER ===
    channel.onmessage = (event) => {
        const data = event.data;

        if (data.type === "matched") {
            if (data.partner.userId === userId) {
                const q = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
                const filtered = q.filter(item => item.userId !== userId);
                localStorage.setItem(QUEUE_KEY, JSON.stringify(filtered));
                connectToSession(data.sessionId, data.initiator.userName);
            }
        }

        if (data.type === "sessionMessage" && data.sessionId === currentSessionId) {
            if (data.message.senderId !== userId) {
                allMessages.push(data.message);
                addMessageToUI(messagesContainer, data.message, userId);
                // Send delivery receipt
                channel.postMessage({ type: "msgDelivered", sessionId: currentSessionId, msgId: data.message.id, to: data.message.senderId });
                // Chat is visible, so also send read receipt
                channel.postMessage({ type: "msgRead", sessionId: currentSessionId, msgId: data.message.id, to: data.message.senderId });
            }
        }

        // Handle delivery receipt — update tick to ✓✓
        if (data.type === "msgDelivered" && data.sessionId === currentSessionId && data.to === userId) {
            const m = allMessages.find(msg => msg.id === data.msgId);
            if (m && m.status !== "read") {
                m.status = "delivered";
                updateTickInUI(data.msgId, "delivered");
            }
        }

        // Handle read receipt — update tick to ✓✓ green
        if (data.type === "msgRead" && data.sessionId === currentSessionId && data.to === userId) {
            const m = allMessages.find(msg => msg.id === data.msgId);
            if (m) {
                m.status = "read";
                updateTickInUI(data.msgId, "read");
            }
        }

        // Receive partner's stable accountId and profile (no avatar exchanged)
        if (data.type === "profileShare" && data.sessionId === currentSessionId) {
            if (data.senderId !== userId) {
                currentPartnerAccountId = data.accountId;
                // Cache partner's profile locally so it's available when saving
                if (data.profile) {
                    try { localStorage.setItem(TestSession.PROFILE_PREFIX + data.accountId, JSON.stringify(data.profile)); } catch(e) {}
                }
            }
        }

        if (data.type === "partnerLeft" && data.sessionId === currentSessionId) {
            addSystemMessage("User has left the chat");
            messageInput.disabled = true;
            btnSend.disabled = true;
            btnSaveChat.style.display = "none";
            btnNewChat.style.display = "";
            onlineCount.textContent = "Disconnected";
            headerOnline.style.display = "flex";
            currentSessionId = null;
            currentPartner = null;
        }

        // Partner saved the chat — notify this user
        if (data.type === "chatSaved" && data.sessionId === currentSessionId) {
            if (data.saverId !== userId) {
                addSystemMessage("Partner has saved the chat");
            }
        }
    };

    function addSystemMessage(text) {
        const row = document.createElement("div");
        row.className = "message-row";
        row.style.alignSelf = "center";
        row.style.maxWidth = "100%";
        const bubble = document.createElement("div");
        bubble.className = "message-bubble";
        bubble.style.background = "#f0f0f0";
        bubble.style.color = "#666";
        bubble.style.borderRadius = "12px";
        bubble.style.fontSize = "0.85rem";
        bubble.style.textAlign = "center";
        bubble.textContent = text;
        row.appendChild(bubble);
        messagesContainer.appendChild(row);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    // === SEND MESSAGE ===
    function sendMessage() {
        const text = messageInput.value.trim();
        if (!text || !currentSessionId) return;
        const msg = {
            id: generateId(), senderId: userId, senderName: userName,
            message: text, timestamp: Date.now(), status: "sent"
        };
        allMessages.push(msg);
        addMessageToUI(messagesContainer, msg, userId);

        if (TEST_MODE) {
            channel.postMessage({ type: "sessionMessage", sessionId: currentSessionId, message: msg });
        } else {
            // Write to Firebase — partner receives via child_added listener
            sessionsRef.child(currentSessionId).child("messages").push().set(msg);
        }

        messageInput.value = "";
        btnSend.disabled = true;
    }

    // === INPUT ===
    messageInput.addEventListener("input", () => {
        btnSend.disabled = !messageInput.value.trim() || !currentSessionId;
    });
    messageInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });
    btnSend.addEventListener("click", sendMessage);

    // === LEAVE / BACK ===
    btnBackToList.addEventListener("click", () => { leaveRoom(); showScreen(chatListScreen); renderSavedChats(); });
    btnLeaveChat.addEventListener("click", () => {
        if (!confirm("Leave this chat?")) return;
        leaveRoom();
        showScreen(chatListScreen);
        renderSavedChats();
    });

    // === NEW CHAT ===
    btnNewChat.addEventListener("click", () => {
        if (currentSessionId) {
            if (TEST_MODE) {
                channel.postMessage({ type: "partnerLeft", sessionId: currentSessionId, userId });
            } else {
                sessionsRef.child(currentSessionId).child("active").set(false);
                sessionsRef.child(currentSessionId).child("messages").off();
                sessionsRef.child(currentSessionId).child("active").off();
                sessionsRef.child(currentSessionId).child("profiles").off();
            }
        }
        if (searchInterval) { clearInterval(searchInterval); searchInterval = null; }
        if (heartbeatInterval) { clearInterval(heartbeatInterval); heartbeatInterval = null; }
        currentSessionId = null;
        currentPartner = null;
        currentPartnerAccountId = null;
        allMessages = [];
        messagesContainer.querySelectorAll(".message-row").forEach(el => el.remove());
        messageInput.disabled = true;
        btnSend.disabled = true;
        btnSaveChat.style.display = "none";
        showSearchingState();
        startSearching();
    });

    function leaveRoom() {
        if (currentSessionId) {
            if (TEST_MODE) {
                channel.postMessage({ type: "partnerLeft", sessionId: currentSessionId, userId });
            } else {
                // Mark session as inactive in Firebase
                sessionsRef.child(currentSessionId).child("active").set(false);
                // Stop listeners
                sessionsRef.child(currentSessionId).child("messages").off();
                sessionsRef.child(currentSessionId).child("active").off();
                sessionsRef.child(currentSessionId).child("profiles").off();
            }
        }
        if (TEST_MODE) {
            const q = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
            localStorage.setItem(QUEUE_KEY, JSON.stringify(q.filter(item => item.userId !== userId)));
        } else {
            queueRef.child(userId).remove();
        }

        if (searchInterval) { clearInterval(searchInterval); searchInterval = null; }
        if (heartbeatInterval) { clearInterval(heartbeatInterval); heartbeatInterval = null; }
        currentSessionId = null;
        currentPartner = null;
        currentPartnerAccountId = null;
        messageInput.disabled = true;
        btnSaveChat.style.display = "none";
    }

    // === SAVE CHAT ===
    btnSaveChat.addEventListener("click", () => {
        if (allMessages.length === 0) { alert("No messages to save!"); return; }
        if (!currentSessionId) { alert("Chat session has ended."); return; }

        // Mark this user as wanting to save. Actual save happens when BOTH users tap Save.
        if (TEST_MODE) {
            // Test mode: save immediately (no partner coordination)
            performSaveChat();
            addSystemMessage("You have saved the chat");
            channel.postMessage({ type: "chatSaved", sessionId: currentSessionId, saverId: userId });
        } else {
            firebase.database().ref("sessions/" + currentSessionId + "/savedBy/" + userId).set(true);
            addSystemMessage("Waiting for partner to save too…");
        }
        btnSaveChat.style.display = "none";
    });

    // Helper to actually save the chat locally
    function performSaveChat() {
        const saved = JSON.parse(localStorage.getItem(SAVED_KEY) || "[]");

        // Use the stable partner accountId (shared via profileShare) for profile lookup
        const partnerAccId = currentPartnerAccountId || allMessages.find(m => m.senderId !== userId)?.senderId;
        let partnerProfile = null;
        if (partnerAccId) {
            try {
                partnerProfile = JSON.parse(localStorage.getItem(TestSession.PROFILE_PREFIX + partnerAccId) || "null");
            } catch (e) {}
        }

        const chatData = {
            id: generateId(),
            savedAt: Date.now(),
            userName: userName,
            partnerName: currentPartner || "AnnoUser",
            partnerAccountId: currentPartnerAccountId || null,
            partnerProfile: partnerProfile,
            messages: [...allMessages]
        };
        saved.unshift(chatData);
        if (saved.length > 20) saved.pop();
        localStorage.setItem(SAVED_KEY, JSON.stringify(saved));
        btnSaveChat.disabled = false;
    }

    // === RENDER MESSAGE BUBBLE ===
    function addMessageToUI(container, msg, myId) {
        const emptyEl = container.querySelector(".empty-state");
        if (emptyEl) emptyEl.style.display = "none";

        const isMine = msg.senderId === myId;
        const row = document.createElement("div");
        row.className = `message-row ${isMine ? "mine" : "other"}`;
        row.setAttribute("data-msg-id", msg.id);

        const bubble = document.createElement("div");
        bubble.className = "message-bubble";

        if (!isMine) {
            // No sender name on individual messages — it's shown in the chat header
        }

        const textEl = document.createElement("div");
        textEl.className = "message-text";
        textEl.textContent = msg.message;
        bubble.appendChild(textEl);

        const metaEl = document.createElement("div");
        metaEl.className = "message-meta";

        const time = document.createElement("span");
        time.className = "message-time";
        time.textContent = formatTime(msg.timestamp);
        metaEl.appendChild(time);

        // Show ticks only for my messages
        if (isMine) {
            const tick = document.createElement("span");
            tick.className = "message-tick";
            tick.setAttribute("data-tick-id", msg.id);
            const status = msg.status || "sent";
            tick.innerHTML = getTickHtml(status);
            metaEl.appendChild(tick);
        }

        bubble.appendChild(metaEl);
        row.appendChild(bubble);
        container.appendChild(row);
        container.scrollTop = container.scrollHeight;
    }

    function getTickHtml(status) {
        if (status === "read") return '<span class="ticks ticks-read">✓✓</span>';
        if (status === "delivered") return '<span class="ticks ticks-delivered">✓✓</span>';
        return '<span class="ticks ticks-sent">✓</span>';
    }

    function updateTickInUI(msgId, status) {
        const tickEls = document.querySelectorAll('[data-tick-id="' + msgId + '"]');
        tickEls.forEach(el => { el.innerHTML = getTickHtml(status); });
    }

    // === SAVED CHATS LIST ===
    function renderSavedChats() {
        const saved = JSON.parse(localStorage.getItem(SAVED_KEY) || "[]");
        const readTimes = JSON.parse(localStorage.getItem("anonchat_read_times") || "{}");
        savedChatsList.querySelectorAll(".saved-chat-item").forEach(el => el.remove());

        if (saved.length === 0) { noSavedChats.style.display = "block"; return; }
        noSavedChats.style.display = "none";

        // Sort by latest message timestamp (most recent first)
        saved.sort((a, b) => {
            const aTime = a.messages.length ? a.messages[a.messages.length - 1].timestamp : a.savedAt;
            const bTime = b.messages.length ? b.messages[b.messages.length - 1].timestamp : b.savedAt;
            return bTime - aTime;
        });
        localStorage.setItem(SAVED_KEY, JSON.stringify(saved));

        saved.forEach(chat => {
            const lastMsg = chat.messages[chat.messages.length - 1];
            const item = document.createElement("div");
            item.className = "saved-chat-item";

            // Calculate unread count
            const lastRead = readTimes[chat.id] || 0;
            const unreadCount = chat.messages.filter(m => m.timestamp > lastRead && m.senderId !== userId).length;

            item.onclick = () => openSavedChat(chat.id);
            const myIdInChat = chat.messages.find(m => m.senderName === chat.userName)?.senderId;
            const partnerMsg = chat.messages.find(m => m.senderId !== myIdInChat);
            const partnerLabel = partnerMsg ? partnerMsg.senderName : (chat.partnerName || "AnnoUser");

            // Gender-based default avatar
            const gender = (chat.partnerProfile && chat.partnerProfile.gender) || null;
            let avatarColor = "#8e8e93";
            if (gender === "Male") avatarColor = "#1B72C0";
            else if (gender === "Female") avatarColor = "#E91E63";

            // Last message time
            const lastMsgTime = lastMsg ? formatTime(lastMsg.timestamp) : "";
            const lastMsgDate = lastMsg ? formatDate(lastMsg.timestamp) : formatDate(chat.savedAt);

            item.innerHTML = `
                <div class="saved-chat-avatar" data-partner-id="${chat.partnerAccountId || ''}">
                    <svg viewBox="0 0 24 24" width="28" height="28" fill="${avatarColor}">
                        <path d="M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zm0,2c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"/>
                    </svg>
                </div>
                <div class="saved-chat-info">
                    <div class="saved-chat-name">${partnerLabel}</div>
                    <div class="saved-chat-preview">${lastMsg ? lastMsg.senderName + ': ' + lastMsg.message : 'No messages'}</div>
                </div>
                <div class="saved-chat-meta">
                    <div class="saved-chat-date">${lastMsgDate} ${lastMsgTime}</div>
                    ${unreadCount > 0 ? `<div class="saved-chat-unread-badge">${unreadCount}</div>` : ''}
                </div>
            `;
            savedChatsList.appendChild(item);

            // Fetch partner's avatar on demand from Firebase
            const pId = chat.partnerAccountId;
            if (pId) {
                const cachedAvatar = localStorage.getItem("anonchat_avatar_" + pId);
                if (cachedAvatar) {
                    setListAvatar(item, cachedAvatar);
                } else if (!TEST_MODE) {
                    firebase.database().ref("/users/" + pId + "/avatar").once("value").then(function(snap) {
                        const av = snap.val();
                        if (av) setListAvatar(item, av);
                    }).catch(function() {});
                }
            }
        });
    }

    function setListAvatar(item, avatarSrc) {
        const container = item.querySelector(".saved-chat-avatar");
        if (!container) return;
        container.innerHTML = `<img src="${avatarSrc}" class="saved-chat-avatar-img" alt=""/>`;
    }

    // === VIEW SAVED CHAT ===
    function openSavedChat(chatId) {
        const saved = JSON.parse(localStorage.getItem(SAVED_KEY) || "[]");
        const chat = saved.find(c => c.id === chatId);
        if (!chat) return;

        // Mark as read
        const readTimes = JSON.parse(localStorage.getItem("anonchat_read_times") || "{}");
        readTimes[chatId] = Date.now();
        localStorage.setItem("anonchat_read_times", JSON.stringify(readTimes));

        currentSavedChatId = chatId;
        // Find partner using senderId (works even when both users have same display name)
        const myIdInChat = chat.messages.find(m => m.senderName === chat.userName)?.senderId || userId;
        const partnerMsg = chat.messages.find(m => m.senderId !== myIdInChat);
        const partnerLabel = partnerMsg ? partnerMsg.senderName : (chat.partnerName || "AnnoUser");
        savedChatTitle.textContent = partnerLabel;
        savedChatTitle.style.cursor = "pointer";
        savedChatTitle.onclick = function () {
            showPartnerProfileCard(chat);
        };
        savedChatStatus.textContent = "last seen...";

        // Load partner avatar in header
        savedChatAvatar.src = "";
        savedChatAvatar.style.display = "none";
        const pAccId = chat.partnerAccountId;

        // Fetch partner's last active time
        if (pAccId) {
            const localActive = localStorage.getItem("anonchat_last_active_" + pAccId);
            if (localActive) {
                savedChatStatus.textContent = formatLastActive(parseInt(localActive));
            }
            if (!TEST_MODE) {
                firebase.database().ref("/users/" + pAccId + "/lastActive").once("value").then(function(snap) {
                    const ts = snap.val();
                    if (ts) savedChatStatus.textContent = formatLastActive(ts);
                }).catch(function() {});
            }
        }

        if (pAccId) {
            const cached = localStorage.getItem("anonchat_avatar_" + pAccId);
            if (cached) {
                savedChatAvatar.src = cached;
                savedChatAvatar.style.display = "block";
            } else if (!TEST_MODE) {
                firebase.database().ref("/users/" + pAccId + "/avatar").once("value").then(function(snap) {
                    const av = snap.val();
                    if (av) {
                        savedChatAvatar.src = av;
                        savedChatAvatar.style.display = "block";
                    }
                }).catch(function() {});
            }
        }
        savedMessagesContainer.innerHTML = "";

        chat.messages.forEach(msg => {
            // All saved messages were delivered and read in the past
            if (!msg.status) msg.status = "read";
            addMessageToUI(savedMessagesContainer, msg, myIdInChat);
        });

        savedMessageInput.disabled = false;
        savedMessageInput.value = "";
        btnSavedSend.disabled = true;

        showScreen(savedChatView);
        savedMessagesContainer.scrollTop = savedMessagesContainer.scrollHeight;
    }

    // === PARTNER PROFILE CARD ===
    function showPartnerProfileCard(chat) {
        const partnerName = chat.partnerName || "AnnoUser";
        const profile = chat.partnerProfile || {};

        // Remove any existing card
        const existing = document.getElementById("profileCardOverlay");
        if (existing) existing.remove();

        const overlay = document.createElement("div");
        overlay.id = "profileCardOverlay";
        overlay.className = "profile-card-overlay";
        overlay.onclick = function (e) { if (e.target === overlay) overlay.remove(); };

        const card = document.createElement("div");
        card.className = "profile-card";

        // Default avatar based on gender
        const gender = profile.gender || null;
        let avatarSvg;
        if (gender === "Female") {
            avatarSvg = `<svg viewBox="0 0 24 24" width="44" height="44" fill="#E91E63"><path d="M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2M12,4A4,4 0 0,1 16,8A4,4 0 0,1 12,12A4,4 0 0,1 8,8A4,4 0 0,1 12,4M12,14C14.67,14 20,15.34 20,18V20H4V18C4,15.34 9.33,14 12,14Z"/></svg>`;
        } else if (gender === "Male") {
            avatarSvg = `<svg viewBox="0 0 24 24" width="44" height="44" fill="#1B72C0"><path d="M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2M12,4A4,4 0 0,1 16,8A4,4 0 0,1 12,12A4,4 0 0,1 8,8A4,4 0 0,1 12,4M12,14C14.67,14 20,15.34 20,18V20H4V18C4,15.34 9.33,14 12,14Z"/></svg>`;
        } else {
            avatarSvg = `<svg viewBox="0 0 24 24" width="44" height="44" fill="#8e8e93"><path d="M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zm0,2c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"/></svg>`;
        }

        const displayName = profile.displayName || partnerName;
        const genderLabel = gender || "Not specified";
        const age = (profile.age !== null && profile.age !== undefined) ? profile.age : "Not specified";
        const city = profile.city || "Not specified";

        // Try to load partner's avatar from Firebase (on-demand, not exchanged during chat)
        const partnerAccId = currentPartnerAccountId || chat.partnerAccountId || null;

        card.innerHTML = `
            <div class="profile-card-avatar-placeholder" id="partnerAvatarContainer">${avatarSvg}</div>
            <h3 class="profile-card-name">${displayName}</h3>
            <div class="profile-card-fields">
                <div class="profile-card-field"><span class="field-label">Gender</span><span class="field-value">${genderLabel}</span></div>
                <div class="profile-card-field"><span class="field-label">Age</span><span class="field-value">${age}</span></div>
                <div class="profile-card-field"><span class="field-label">City</span><span class="field-value">${city}</span></div>
            </div>
            <button class="profile-card-close" onclick="this.closest('.profile-card-overlay').remove()">Close</button>
        `;

        overlay.appendChild(card);
        document.body.appendChild(overlay);

        // Fetch avatar on demand from Firebase or localStorage
        if (partnerAccId) {
            const cachedAvatar = localStorage.getItem("anonchat_avatar_" + partnerAccId);
            if (cachedAvatar) {
                showAvatarInCard(card, cachedAvatar);
            } else if (!TEST_MODE) {
                // Fetch from Firebase /users/{uid}/avatar
                firebase.database().ref("/users/" + partnerAccId + "/avatar").once("value").then(function(snap) {
                    const avatarData = snap.val();
                    if (avatarData) {
                        showAvatarInCard(card, avatarData);
                    }
                }).catch(function() {});
            }
        }
    }

    function showAvatarInCard(card, avatarSrc) {
        const container = card.querySelector("#partnerAvatarContainer");
        if (!container) return;
        container.innerHTML = `<img src="${avatarSrc}" class="profile-card-avatar clickable-avatar" alt="Profile"/>`;
        const imgEl = container.querySelector(".clickable-avatar");
        if (imgEl) {
            imgEl.addEventListener("click", function() {
                showFullscreenPhoto(avatarSrc);
            });
        }
    }

    // === FULLSCREEN PHOTO VIEWER (screenshot-protected) ===
    function showFullscreenPhoto(imageSrc) {
        if (!imageSrc) return;

        const viewer = document.createElement("div");
        viewer.id = "photoViewer";
        viewer.className = "photo-viewer-overlay";

        viewer.innerHTML = `
            <div class="photo-viewer-content" oncontextmenu="return false;" onselectstart="return false;" ondragstart="return false;">
                <img src="${imageSrc}" class="photo-viewer-img" draggable="false" />
                <div class="photo-viewer-watermark">AnonChat</div>
            </div>
            <button class="photo-viewer-close" onclick="this.closest('.photo-viewer-overlay').remove()">✕</button>
        `;

        viewer.addEventListener("contextmenu", function (e) { e.preventDefault(); });
        viewer.addEventListener("click", function (e) {
            if (e.target === viewer) viewer.remove();
        });

        document.body.appendChild(viewer);
    }

    // Send message in saved chat
    savedMessageInput.addEventListener("input", () => {
        btnSavedSend.disabled = !savedMessageInput.value.trim();
    });
    savedMessageInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendSavedMessage(); }
    });
    btnSavedSend.addEventListener("click", sendSavedMessage);

    function sendSavedMessage() {
        const text = savedMessageInput.value.trim();
        if (!text || !currentSavedChatId) return;

        const msg = {
            id: generateId(), senderId: userId, senderName: userName,
            message: text, timestamp: Date.now(), status: "sent"
        };

        addMessageToUI(savedMessagesContainer, msg, userId);

        const saved = JSON.parse(localStorage.getItem(SAVED_KEY) || "[]");
        const chat = saved.find(c => c.id === currentSavedChatId);
        if (chat) {
            chat.messages.push(msg);
            localStorage.setItem(SAVED_KEY, JSON.stringify(saved));
        }

        channel.postMessage({ type: "savedChatMessage", chatId: currentSavedChatId, message: msg });

        savedMessageInput.value = "";
        btnSavedSend.disabled = true;
    }

    // Listen for messages in saved chats from other tabs
    channel.addEventListener("message", (event) => {
        const data = event.data;
        if (data.type === "savedChatMessage") {
            if (data.chatId === currentSavedChatId && data.message.senderId !== userId) {
                // Currently viewing this chat — show the message
                data.message.status = "read";
                addMessageToUI(savedMessagesContainer, data.message, userId);
                // Send read receipt
                channel.postMessage({ type: "savedMsgRead", chatId: data.chatId, msgId: data.message.id, to: data.message.senderId });
                // Update read time since user is looking at it
                const readTimes = JSON.parse(localStorage.getItem("anonchat_read_times") || "{}");
                readTimes[data.chatId] = Date.now();
                localStorage.setItem("anonchat_read_times", JSON.stringify(readTimes));
            }
            // If viewing the chat list, re-render to show unread badge
            if (!chatListScreen.classList.contains("hidden")) {
                renderSavedChats();
            }
        }

        // Handle read receipt for saved chat messages
        if (data.type === "savedMsgRead" && data.to === userId) {
            updateTickInUI(data.msgId, "read");
        }
    });

    btnBackFromSaved.addEventListener("click", () => { savedMenuDropdown.classList.add("hidden"); showScreen(chatListScreen); renderSavedChats(); });

    // Three-dot menu toggle
    btnSavedMenu.addEventListener("click", (e) => {
        e.stopPropagation();
        savedMenuDropdown.classList.toggle("hidden");
    });
    // Close menu when clicking outside
    document.addEventListener("click", () => { savedMenuDropdown.classList.add("hidden"); });

    btnDeleteFromMenu.addEventListener("click", () => {
        savedMenuDropdown.classList.add("hidden");
        if (!confirm("Delete this saved chat?")) return;
        const saved = JSON.parse(localStorage.getItem(SAVED_KEY) || "[]");
        localStorage.setItem(SAVED_KEY, JSON.stringify(saved.filter(c => c.id !== currentSavedChatId)));
        showScreen(chatListScreen);
        renderSavedChats();
    });

    btnBlockUser.addEventListener("click", () => {
        savedMenuDropdown.classList.add("hidden");
        if (!confirm("Block this user? You won't be matched with them again.")) return;
        // Get partner account id from the current saved chat
        const saved = JSON.parse(localStorage.getItem(SAVED_KEY) || "[]");
        const chat = saved.find(c => c.id === currentSavedChatId);
        if (chat && chat.partnerAccountId) {
            const blocked = JSON.parse(localStorage.getItem("anonchat_blocked") || "[]");
            if (!blocked.includes(chat.partnerAccountId)) {
                blocked.push(chat.partnerAccountId);
                localStorage.setItem("anonchat_blocked", JSON.stringify(blocked));
            }
        }
        // Also delete the chat
        localStorage.setItem(SAVED_KEY, JSON.stringify(saved.filter(c => c.id !== currentSavedChatId)));
        showScreen(chatListScreen);
        renderSavedChats();
    });

    // === CLEANUP ===
    window.addEventListener("beforeunload", () => {
        if (TEST_MODE) {
            const q = JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
            localStorage.setItem(QUEUE_KEY, JSON.stringify(q.filter(item => item.userId !== userId)));
            if (currentSessionId) {
                channel.postMessage({ type: "partnerLeft", sessionId: currentSessionId, userId });
            }
        } else {
            // Remove from queue and mark session inactive on Firebase
            queueRef.child(userId).remove();
            if (currentSessionId) {
                sessionsRef.child(currentSessionId).child("active").set(false);
            }
        }
    });
}

// === UTILITIES (global — used by both auth and chat) ===
function generateId() {
    return Date.now().toString(36) + Math.random().toString(36).substring(2, 8);
}

function generateAnonName() {
    // Legacy function — kept only for backward compatibility
    // Should not be called in the auth flow; display name comes from profile
    return "AnnoUser";
}

function formatTime(ts) {
    if (!ts) return "";
    const d = new Date(ts);
    return d.getHours().toString().padStart(2, "0") + ":" + d.getMinutes().toString().padStart(2, "0");
}

function formatDate(ts) {
    if (!ts) return "";
    const d = new Date(ts);
    const today = new Date();
    if (d.toDateString() === today.toDateString()) return "Today";
    const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
    if (d.toDateString() === yesterday.toDateString()) return "Yesterday";
    return d.getDate() + "/" + (d.getMonth()+1) + "/" + d.getFullYear();
}

function formatLastActive(ts) {
    if (!ts) return "last seen recently";
    const now = Date.now();
    const diff = now - ts;
    const minutes = Math.floor(diff / 60000);
    if (minutes < 1) return "Active now";
    if (minutes < 60) return "last seen " + minutes + " min ago";
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return "last seen " + hours + "h ago";
    const d = new Date(ts);
    const today = new Date();
    const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
    if (d.toDateString() === yesterday.toDateString()) return "last seen yesterday";
    return "last seen " + d.getDate() + "/" + (d.getMonth()+1) + "/" + d.getFullYear();
}
