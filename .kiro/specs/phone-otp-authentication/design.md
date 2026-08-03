# Technical Design

## Overview

This feature replaces the current immediate-access flow (random anonymous name → chat) with a mandatory phone OTP verification step. After verification, users are identified by a persistent Firebase UID (Account_ID) and can maintain a private profile. The display name from the profile is shown in chat messages alongside a short UID identifier. Both the Web (HTML/CSS/JS + Firebase SDK) and Android (Kotlin + Firebase) platforms are updated in parallel.

## Components and Interfaces

| Component | Role |
|-----------|------|
| **Firebase Authentication (Phone Provider)** | Manages phone number verification, OTP delivery via SMS, session tokens, and UID assignment. Same phone always yields same UID. |
| **Firebase Realtime Database** | Stores user profiles (`/users/{uid}/profile`), chat queue, sessions, and messages. |
| **Web Frontend** | Single-page HTML/JS app using Firebase JS SDK for auth + RTDB. Uses `BroadcastChannel` for local multi-tab chat and Firebase for cross-platform sync. |
| **Android App** | Kotlin app with Material Design 3, Firebase Auth SDK, Firebase RTDB SDK, and SMS Retriever API for auto-read OTP. |
| **reCAPTCHA (Web only)** | Invisible reCAPTCHA widget attached to "Send Code" button to prevent bot abuse. |
| **SMS Retriever API (Android only)** | Hash-based SMS auto-read without READ_SMS permission. |

## Data Models

### User Account (Firebase Auth)

Firebase Authentication manages the user record internally. Key fields:

```
Firebase Auth User:
  uid: string          // Account_ID — persistent, derived from phone number
  phoneNumber: string  // E.164 format, never exposed in chat data
  metadata:
    creationTime: timestamp
    lastSignInTime: timestamp
```

### User Profile (Firebase Realtime Database)

Stored at `/users/{uid}/profile`:

```json
{
  "displayName": "AnnoUser",    // string, max 50 chars, default "AnnoUser"
  "gender": null,               // "Male" | "Female" | "Other" | "Prefer not to say" | null
  "age": null,                  // number 13-120 | null
  "city": null                  // string, max 100 chars | null
}
```

- `displayName` is the sender name visible to others in chat.
- `gender`, `age`, `city` are strictly private — never included in messages or any shared data path.
- Default values on account creation: `displayName = "AnnoUser"`, all others `null`.

### Chat Messages (Firebase Realtime Database)

Stored at `/sessions/{sessionId}/messages/{messageId}`:

```json
{
  "id": "msg_abc123",
  "senderId": "firebaseUID123",        // Account_ID (UID)
  "senderName": "AnnoUser",            // display name from profile
  "message": "Hello!",
  "timestamp": 1700000000000
}
```

- `senderId` is the Firebase UID — displayed as a short identifier below the name in chat bubbles (first 8 chars).
- `senderName` is the display name from the user's profile at the time of sending.
- Gender, age, city are **never** included in message payloads.

### Security Rules

```json
{
  "rules": {
    "users": {
      "$uid": {
        "profile": {
          ".read": "auth != null && auth.uid === $uid",
          ".write": "auth != null && auth.uid === $uid"
        }
      }
    },
    "queue": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "sessions": {
      ".read": "auth != null",
      ".write": "auth != null",
      "$sessionId": {
        "messages": {
          ".indexOn": ["timestamp"]
        }
      }
    },
    "presence": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```

Key changes from current rules:
- All paths now require `auth != null` (authenticated users only).
- New `/users/{uid}/profile` path with owner-only access (`auth.uid === $uid`).

## Architecture

### Screen Flow

```
┌─────────────────┐         ┌──────────────────────┐
│   Auth Screen   │────────▶│   Chat List Screen   │
│ (Phone + OTP)   │         │  (with Profile tab)  │
└─────────────────┘         └──────────┬───────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
                    ▼                  ▼                  ▼
           ┌──────────────┐  ┌────────────────┐  ┌────────────┐
           │  Live Chat   │  │  Saved Chats   │  │  Profile   │
           │    Room      │  │    (view)      │  │  Screen    │
           └──────────────┘  └────────────────┘  └────────────┘
```

**Navigation transitions:**
1. App Launch → Auth Screen (if no valid session) OR → Chat List (if session exists)
2. Auth Screen → Phone input → OTP input → Chat List (on success)
3. Chat List → Live Chat / Saved Chat / Profile (tab navigation)
4. Profile → Edit fields → Save → stays on Profile
5. Logout (from overflow menu) → Auth Screen

## Detailed Design

### Authentication Flow (Web)

**Firebase Phone Auth with reCAPTCHA:**

```javascript
// 1. Initialize invisible reCAPTCHA on Auth Screen load
const recaptchaVerifier = new firebase.auth.RecaptchaVerifier('btn-send-code', {
  size: 'invisible',
  callback: (response) => { /* reCAPTCHA solved, enable send */ },
  'expired-callback': () => { /* re-render */ }
});

// 2. Send OTP
async function sendOTP(phoneNumber) {
  const confirmationResult = await firebase.auth().signInWithPhoneNumber(
    phoneNumber,
    recaptchaVerifier
  );
  window.confirmationResult = confirmationResult;
}

// 3. Verify OTP
async function verifyOTP(code) {
  const result = await window.confirmationResult.confirm(code);
  const user = result.user; // user.uid is the Account_ID
}
```

**Session persistence:**
- Firebase Auth persistence set to `firebase.auth.Auth.Persistence.LOCAL` (survives browser restarts).
- On page load, `firebase.auth().onAuthStateChanged()` determines if user is authenticated.
- If authenticated → skip Auth Screen → load profile → navigate to Chat List.
- If not → show Auth Screen.

**reCAPTCHA behavior:**
- Invisible by default; visible challenge shown only when risk score is high.
- If reCAPTCHA script fails to load within 10 seconds, show error with suggestion to check extensions/network.
- "Send Code" button stays disabled until reCAPTCHA signals readiness.

### Authentication Flow (Android)

**Firebase Phone Auth with SMS Retriever API:**

```kotlin
// 1. Send OTP
val options = PhoneAuthOptions.newBuilder(auth)
    .setPhoneNumber(phoneNumber)       // E.164 format
    .setTimeout(120L, TimeUnit.SECONDS)
    .setActivity(this)
    .setCallbacks(callbacks)           // PhoneAuthProvider.OnVerificationStateChangedCallbacks
    .build()
PhoneAuthProvider.verifyPhoneNumber(options)

// 2. Callbacks
val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
        // Auto-verification (instant verification or auto-read)
        signInWithCredential(credential)
    }
    override fun onVerificationFailed(e: FirebaseException) {
        // Show error
    }
    override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
        // Store verificationId, show OTP input
        storedVerificationId = verificationId
        resendToken = token
    }
}

// 3. Verify manually entered code
val credential = PhoneAuthProvider.getCredential(storedVerificationId, userEnteredCode)
auth.signInWithCredential(credential)
```

**SMS Retriever API (auto-read OTP):**
- Uses `SmsRetrieverClient` to listen for SMS containing the app hash.
- Does NOT require `READ_SMS` permission.
- On SMS received, extract 6-digit code and auto-populate `OTP_Input` field (without auto-submitting).
- If auto-read fails, manual entry remains fully functional.

**Session persistence:**
- Firebase Auth state persists automatically via `FirebaseAuth.getInstance().currentUser`.
- On app launch, check `currentUser != null` → skip Auth → navigate to Chat List.
- Token refresh handled automatically by Firebase SDK.

### Profile Management

**Data location:** `/users/{uid}/profile` in Firebase Realtime Database.

**Read/write flow:**
1. On auth success or app resume → read profile from RTDB.
2. If no profile exists → initialize with defaults (`displayName: "AnnoUser"`, others `null`).
3. User edits fields on Profile Screen → local validation → write to RTDB on "Save".
4. Display name is also cached locally for use in chat messages without extra network calls.

**Validation rules:**
- `displayName`: non-empty, max 50 characters. Default: "AnnoUser".
- `gender`: one of `["Male", "Female", "Other", "Prefer not to say", null]`.
- `age`: integer between 13 and 120, or null.
- `city`: max 100 characters, or null.

**Offline support:**
- Android: Firebase RTDB disk persistence enabled (`setPersistenceEnabled(true)`).
- Web: Firebase RTDB enables offline persistence by default for active listeners.
- If user saves profile while offline, data is cached locally and synced when connectivity is restored.

**Security:** Only the owning user (`auth.uid === $uid`) can read or write their own profile node.

### Account Identity

- **Firebase UID = Account_ID**: The same phone number always resolves to the same UID. Firebase handles this internally — re-verifying an existing phone number returns the same user record.
- **All user data keyed by UID**: Profile, saved chats, and session references use `uid` as the primary key.
- **Local data separation**: On Android, locally cached data (saved chats, profile) is keyed by UID so multiple accounts on the same device don't collide.
- **Logout behavior**: Clears auth session. On next login with same phone, same UID is returned and persisted data (profile, saved chats in RTDB) is restored. A new Anonymous_Name is NOT generated (replaced by profile display name).

### Chat Integration

**Message structure sent to Firebase:**
```json
{
  "id": "<generated>",
  "senderId": "<firebase_uid>",
  "senderName": "<displayName from profile>",
  "message": "<text>",
  "timestamp": <server_timestamp>
}
```

**Display in chat bubbles:**
- Primary: `senderName` (the profile display name, e.g., "AnnoUser").
- Secondary (smaller font below name): short UID — first 8 characters of `senderId` (e.g., "abc12def").
- This allows users to distinguish between people with the same display name across sessions.

**Privacy enforcement:**
- Gender, age, city are **never** included in message payloads or any shared data structure.
- Phone number is never written to RTDB or any client-accessible path.
- Only `senderId` (UID) and `senderName` (display name) identify a user in chat.

### Firebase Security Rules

```json
{
  "rules": {
    "users": {
      "$uid": {
        "profile": {
          ".read": "auth != null && auth.uid === $uid",
          ".write": "auth != null && auth.uid === $uid",
          ".validate": "newData.hasChildren(['displayName'])",
          "displayName": {
            ".validate": "newData.isString() && newData.val().length > 0 && newData.val().length <= 50"
          },
          "gender": {
            ".validate": "newData.val() === null || newData.val() === 'Male' || newData.val() === 'Female' || newData.val() === 'Other' || newData.val() === 'Prefer not to say'"
          },
          "age": {
            ".validate": "newData.val() === null || (newData.isNumber() && newData.val() >= 13 && newData.val() <= 120)"
          },
          "city": {
            ".validate": "newData.val() === null || (newData.isString() && newData.val().length <= 100)"
          }
        }
      }
    },
    "queue": {
      ".read": "auth != null",
      ".write": "auth != null"
    },
    "sessions": {
      ".read": "auth != null",
      ".write": "auth != null",
      "$sessionId": {
        "messages": {
          ".indexOn": ["timestamp"]
        }
      }
    },
    "presence": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```

**Rule explanations:**
- `/users/{uid}/profile`: Only the authenticated owner can read/write. Validation enforces data constraints server-side.
- `/queue`: Any authenticated user can read/write (for matchmaking).
- `/sessions`: Any authenticated user can read/write (for sending/receiving messages).
- `/presence`: Any authenticated user can read/write (for online status tracking).

## File Changes

### Web

| File | Change |
|------|--------|
| `web/index.html` | Add Auth Screen HTML (phone input, OTP input, reCAPTCHA container). Add Profile tab/screen HTML in Chat List section. Add Firebase Auth SDK script tag. |
| `web/app.js` | Add Firebase Auth initialization, `signInWithPhoneNumber()` flow, `onAuthStateChanged()` listener, profile CRUD logic, logout handler. Update message sending to use UID + profile display name. Remove `generateAnonName()` as primary identity. |
| `web/style.css` | Add styles for Auth Screen (phone input, OTP input, loading states, error messages, countdown timer). Add Profile Screen/tab styles. |

### Android

| File | Type | Change |
|------|------|--------|
| `app/src/main/java/com/anonchat/app/AuthActivity.kt` | **New** | Phone number input + OTP verification screen. SMS Retriever integration. Handles reCAPTCHA-free phone auth flow. |
| `app/src/main/java/com/anonchat/app/ProfileActivity.kt` | **New** | Profile view/edit screen with form fields for displayName, gender, age, city. Reads/writes to `/users/{uid}/profile`. |
| `app/src/main/res/layout/activity_auth.xml` | **New** | Layout: phone input with country code selector, OTP input (6 digits), Send Code button, Verify button, countdown timer, loading indicator. |
| `app/src/main/res/layout/activity_profile.xml` | **New** | Layout: TextInputLayout fields for display name, gender spinner/dropdown, age number input, city text input, Save button. |
| `app/src/main/java/com/anonchat/app/ChatListActivity.kt` | **Modified** | Add bottom navigation or tab for Profile. Add overflow menu with Logout option. Load display name from profile instead of intent extra. |
| `app/src/main/java/com/anonchat/app/WelcomeActivity.kt` | **Modified** | Replace random name generation with auth check. If `FirebaseAuth.currentUser != null` → navigate to ChatList. Else → navigate to AuthActivity. Acts as a splash/router. |
| `app/src/main/java/com/anonchat/app/model/ChatMessage.kt` | **Modified** | No structural change needed — already has `senderId` and `senderName` fields. Ensure `senderId` stores UID. |
| `app/src/main/java/com/anonchat/app/adapter/MessageAdapter.kt` | **Modified** | Display short UID (first 8 chars of `senderId`) below `senderName` in chat bubbles. |
| `app/src/main/AndroidManifest.xml` | **Modified** | Register `AuthActivity` and `ProfileActivity`. Add `INTERNET` permission (already present). |
| `app/build.gradle.kts` | **Modified** | Add `firebase-auth-ktx` dependency. Add `com.google.android.gms:play-services-auth` for SMS Retriever. |

### Firebase

| File | Change |
|------|--------|
| `firebase-database-rules.json` | Replace permissive rules with authenticated-only access. Add `/users/{uid}/profile` with owner-only read/write and validation rules. |

## Dependencies

### Android

| Dependency | Purpose |
|------------|---------|
| `com.google.firebase:firebase-auth-ktx` | Firebase Phone Authentication SDK |
| `com.google.android.gms:play-services-auth:20.7.0` | Google Play Services (SMS Retriever API) |
| `com.google.android.gms:play-services-auth-api-phone:18.0.2` | SMS Retriever client for auto-read OTP |

### Web

| Dependency | Purpose |
|------------|---------|
| `firebase/auth` (from Firebase JS SDK) | Firebase Phone Authentication for web |
| Firebase JS SDK (already partially included) | Auth module addition to existing Firebase setup |

### No New External Dependencies

- reCAPTCHA is loaded from Google CDN as part of Firebase Auth — no separate install.
- SMS Retriever is part of Google Play Services — no separate APK.
- Country code selector: Android uses a simple spinner with country data; Web uses a `<select>` dropdown. No third-party library required.

## Error Handling

| Scenario | Web Behavior | Android Behavior |
|----------|-------------|-----------------|
| No internet on Send OTP | Show "No internet connection" error, preserve input | Show "No internet connection" error, preserve input |
| OTP timeout (15s) | Cancel request, re-enable Send Code, show timeout error | Cancel request, re-enable Send Code, show timeout error |
| Invalid OTP code | Show "Invalid code. Please try again.", keep OTP input | Show "Invalid code. Please try again.", keep OTP input |
| Expired OTP code | Show "Code expired", clear OTP input, enable Resend immediately | Show "Code expired", clear OTP input, enable Resend immediately |
| Rate limited | Show "Too many attempts", disable Resend until reset | Show "Too many attempts", disable Resend until reset |
| reCAPTCHA failed (Web only) | Show "Verification failed. Please refresh.", disable Send Code | N/A |
| Profile save offline | Cache locally, sync on reconnect | Cache locally via RTDB disk persistence, auto-sync |
| Firebase Auth token expired | Clear session, redirect to Auth Screen | Clear session, redirect to Auth Screen |

## Correctness Properties

### Property 1: Same phone yields same UID
Firebase guarantees the same phone number always yields the same UID. No application-level logic needed.

**Validates: Requirements 10.1**

### Property 2: Profile privacy enforcement
Firebase security rules enforce that only `auth.uid === $uid` can read/write `/users/{uid}/profile`. Server-side validation prevents bypass.

**Validates: Requirements 11.4**

### Property 3: No data leakage in chat
Chat messages only contain `senderId` (UID) and `senderName` (display name). Gender, age, city, and phone number are never written to any shared path.

**Validates: Requirements 11.5**

### Property 4: Session integrity
Firebase Auth tokens are cryptographically signed and validated server-side. Expired/revoked tokens are rejected.

**Validates: Requirements 6.1**

### Property 5: OTP expiration
Firebase enforces code expiration server-side (default 5 minutes). Client-side countdown is informational only.

**Validates: Requirements 8.3**

## Testing Strategy

| Test Area | Approach |
|-----------|----------|
| Phone Auth flow (Web) | Manual testing with Firebase Auth Emulator (supports test phone numbers without real SMS) |
| Phone Auth flow (Android) | Manual testing with Firebase Auth Emulator + test phone numbers in Firebase Console |
| Profile CRUD | Unit test validation logic. Integration test read/write with Firebase Emulator |
| Security rules | Firebase Emulator Rules testing (`firebase emulators:start`) with security rules unit tests |
| Offline profile save | Disable network, save profile, re-enable network, verify sync |
| Chat message privacy | Verify no gender/age/city/phone fields in `/sessions/{id}/messages` path |
| Session persistence | Kill and restart app, verify user stays logged in |
| Logout flow | Verify session cleared, profile data preserved in RTDB for next login |
| SMS Retriever (Android) | Manual testing on physical device with real SMS |
| reCAPTCHA (Web) | Manual testing in browser; verify invisible flow and error handling |

## Test Mode (Development Bypass)

While the project is still being built, both clients run with an OTP bypass so the rest of the app is reachable before Firebase phone auth is provisioned. The bypass is controlled by one build-time flag per platform:

| Platform | Flag |
|----------|------|
| Web | `const TEST_MODE = true;` at the top of `web/app.js` |
| Android | `AuthActivity.TEST_MODE` (companion object constant) |

**Validates: Requirements 12.1 through 12.11**

### Shared Contract

Both platforms implement the same contract so behavior does not drift.

**Input handling.** With Test_Mode active, Phone_Input and OTP_Input accept anything, including empty. No E.164 check, no 6-digit check, no inline validation errors, and both the "Send Code" and "Verify" controls stay enabled. The resend countdown still runs, since it is pure UI and worth exercising.

**Test_Account_ID derivation.** Identical on both platforms:

```
digits = all [0-9] characters of the full E.164 phone number (country code + entered number)
Test_Account_ID = digits.isEmpty ? "test_anonymous" : "test_" + digits
```

No truncation, no random component. The same phone number typed into the browser and into the Android app produces the same Test_Account_ID, which is what makes cross-platform testing meaningful once a shared backend is in place.

**Test_Session storage.** Same key names on both platforms (localStorage on Web, SharedPreferences on Android):

| Key | Value |
|-----|-------|
| `anonchat_test_active` | `"true"` while signed in, `"false"` or absent otherwise |
| `anonchat_test_uid` | Test_Account_ID |
| `anonchat_test_phone` | Full E.164 phone number as entered |
| `anonchat_test_profile_<uid>` | Cached Profile_Data for that Test_Account_ID |

Logout clears only `anonchat_test_active`. The uid and cached profile survive, so signing back in with the same number restores the same local data. This mirrors Requirement 10.5 behavior without a real backend.

**Session routing.** On launch, an active Test_Session skips the Auth_Screen and goes straight to the Chat List. There is no extra name-entry step in either client; the Profile_Screen is the only place a display name is set.

**Visible notice.** Both Auth_Screens show a persistent notice while Test_Mode is active, stating that verification is bypassed and any phone number and code are accepted. This prevents the bypass from shipping unnoticed.

**Removability.** Test_Mode code lives behind the flag in clearly marked blocks (`TestSession.kt` on Android, a marked section in `web/app.js`). Setting the flag to false restores the flows in Requirements 1 through 11 and causes any stored Test_Session to be ignored.

### Accepted Exception: Web Matchmaking Identity

The web client appends a random per-tab suffix to the identity it puts in the matchmaking queue, because all browser tabs share one `localStorage` and would otherwise match against themselves. That suffix applies **only** to the queue/session identity, never to the Test_Account_ID used for the Test_Session or for Profile_Data keys.

This exception exists only because web matchmaking runs on `localStorage` + `BroadcastChannel` instead of Firebase. It disappears when the transport divergence noted as out of scope in Requirement 12 is resolved.

### Web Serving Fix

`web/server.js` currently serves static files from `web/public/`, but the Auth_Screen implementation lives at the web root (`web/index.html`, `web/app.js`, `web/style.css`). `web/public/index.html` is an older self-contained copy with no Auth_Screen, so `npm start` serves an app that predates this feature.

Fix: point the static root at `web/` so the current implementation is what gets served.

```javascript
// before
filePath = path.join(__dirname, "public", filePath);
// after
filePath = path.join(__dirname, filePath);
```

`web/public/index.html` and `web/public/style.css` then become dead files. They should be deleted so the duplicate cannot drift again, but that deletion is called out as a separate task requiring confirmation.

### Test Mode File Changes

| File | Change |
|------|--------|
| `web/app.js` | Drop the empty-code rejection in `testModeVerify()`. Drop the input-gated disabling of "Send Code". Replace the truncated uid derivation with the shared scheme. Adopt the shared storage keys plus the explicit active flag. Key the cached profile by uid. Confine the per-tab suffix to the matchmaking identity. |
| `web/index.html` | Add the Test_Mode notice element to the Auth_Screen. |
| `web/style.css` | Style the Test_Mode notice. |
| `web/server.js` | Serve static files from the web root instead of `web/public/`. |
| `web/public/index.html`, `web/public/style.css` | Delete (stale duplicates), pending confirmation. |
| `app/src/main/java/com/anonchat/app/TestSession.kt` | Switch to the shared key names and the phone-derived uid scheme. |
| `app/src/main/java/com/anonchat/app/AuthActivity.kt` | Pass the entered phone number into the shared derivation; no other behavior change needed. |
