# Implementation Plan: Phone OTP Authentication

## Overview

Replace the current immediate-access anonymous chat flow with mandatory phone OTP verification using Firebase Authentication. Both Web (HTML/CSS/JS) and Android (Kotlin) platforms are updated in parallel. After verification, users are identified by a persistent Firebase UID with a private profile (display name, gender, age, city). The display name is shown in chat; private fields are never exposed.

## Tasks

- [x] 1. Update Firebase security rules and project dependencies
  - [x] 1.1 Update Firebase Realtime Database security rules
    - Replace `firebase-database-rules.json` with authenticated-only rules
    - Add `/users/{uid}/profile` node with owner-only read/write and field validation
    - Add `.validate` rules for displayName (string, 1-50 chars), gender (enum), age (13-120), city (string, max 100)
    - Require `auth != null` on `/queue`, `/sessions`, `/presence`
    - _Requirements: 11.4, 11.5_

  - [x] 1.2 Add Firebase Auth dependency to Android build
    - Add `firebase-auth-ktx` to `app/build.gradle.kts` dependencies
    - Add `com.google.android.gms:play-services-auth:20.7.0` for SMS Retriever
    - Add `com.google.android.gms:play-services-auth-api-phone:18.0.2`
    - _Requirements: 2.1, 9.1_

  - [x] 1.3 Add Firebase Auth SDK script to web HTML
    - Add Firebase Auth JS SDK script tag to `web/index.html`
    - Ensure Firebase Auth module is available alongside existing Firebase RTDB
    - _Requirements: 2.1, 5.1_

- [x] 2. Implement Web Authentication Flow
  - [x] 2.1 Create Auth Screen HTML in web/index.html
    - Add Auth Screen section with phone input field, country code selector (`<select>`), "Send Code" button, OTP input field, "Verify" button, "Resend Code" button with countdown, loading indicators, and error message container
    - Include reCAPTCHA container element
    - Add AnonChat logo, heading "Verify your phone", and privacy subtitle
    - Auth Screen should be the first visible screen (replace Welcome as entry point)
    - _Requirements: 1.1, 1.3, 2.2, 2.5, 3.4, 4.1_

  - [x] 2.2 Add Auth Screen styles to web/style.css
    - Style phone input with country code selector, OTP input (6-digit), buttons, loading states, error messages, and countdown timer
    - Style the reCAPTCHA container (invisible positioning)
    - _Requirements: 1.1, 2.3, 3.4_

  - [x] 2.3 Implement Firebase Auth initialization and reCAPTCHA setup in web/app.js
    - Initialize Firebase Auth with `LOCAL` persistence
    - Set up invisible reCAPTCHA verifier attached to "Send Code" button
    - Keep "Send Code" button disabled until reCAPTCHA signals ready
    - Handle reCAPTCHA load failure (10s timeout → show error)
    - Handle reCAPTCHA challenge dismissal
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 2.4 Implement phone number submission and OTP send flow in web/app.js
    - Validate phone number input (E.164 format: "+" followed by 7-15 digits)
    - Show inline error for invalid/empty input
    - Call `signInWithPhoneNumber()` with reCAPTCHA verifier
    - Show loading indicator, disable "Send Code" during request
    - Implement 15-second timeout with cancellation
    - On success: transition to OTP input view, start 60-second resend countdown
    - On failure: show error, re-enable button
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [x] 2.5 Implement OTP verification flow in web/app.js
    - Accept 6-digit numeric input only
    - Call `confirmationResult.confirm(code)` on "Verify" tap
    - Show loading indicator, disable "Verify" during verification
    - On success: create auth session, proceed to profile load/chat list
    - On invalid code: show "Invalid code. Please try again."
    - On expired code: show expiry message, clear input, enable Resend
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 8.3_

  - [x] 2.6 Implement OTP resend functionality in web/app.js
    - Show 60-second countdown timer on "Resend Code" button
    - Enable button when countdown reaches zero
    - On resend: send new OTP, clear OTP input, restart countdown
    - Handle rate limiting (show error, disable button)
    - Handle network failures (show error, keep button enabled)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

  - [x] 2.7 Implement session persistence and auth state listener in web/app.js
    - Use `onAuthStateChanged()` to check auth state on page load
    - If authenticated: skip Auth Screen, load profile, navigate to Chat List
    - If not authenticated: show Auth Screen
    - Handle expired/invalid sessions (clear and show Auth Screen)
    - Handle network error during session check (10s timeout → show Auth Screen)
    - _Requirements: 1.2, 1.4, 6.1, 6.6_

  - [x] 2.8 Implement logout functionality in web/app.js
    - Add logout option to Chat List screen (overflow menu)
    - Show confirmation prompt before logout
    - On confirm: call `firebase.auth().signOut()`, clear session, navigate to Auth Screen
    - Retain saved chats locally after logout
    - _Requirements: 6.2, 6.3, 6.4, 6.5_

  - [x] 2.9 Implement error handling for network/service failures in web/app.js
    - Detect no-network state and show "No internet connection" with guidance
    - Preserve user-entered data in Phone_Input and OTP_Input on errors
    - Show generic error with retry button for non-specific failures
    - Keep errors visible until user initiates new action
    - _Requirements: 8.1, 8.2, 8.4_

- [x] 3. Checkpoint - Web Auth flow complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement Android Authentication Flow
  - [x] 4.1 Create AuthActivity layout (activity_auth.xml)
    - Phone input with country code spinner, "Send Code" button
    - OTP input (6 digits, numeric only), "Verify" button
    - "Resend Code" button with countdown timer text
    - Loading indicator (ProgressBar), error message TextView
    - AnonChat logo, heading "Verify your phone", privacy subtitle
    - _Requirements: 1.1, 1.3, 2.2, 2.5, 3.4, 4.1_

  - [x] 4.2 Implement AuthActivity.kt with Firebase Phone Auth
    - Validate phone number (E.164 format)
    - Show inline error for invalid input
    - Call `PhoneAuthProvider.verifyPhoneNumber()` with 120-second timeout
    - Handle callbacks: `onCodeSent`, `onVerificationCompleted`, `onVerificationFailed`
    - Show loading, disable "Send Code" during request with 15s app-level timeout
    - On `onCodeSent`: show OTP input, start 60-second resend countdown
    - On failure: show error, re-enable button
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [x] 4.3 Implement OTP verification in AuthActivity.kt
    - Accept 6-digit numeric code
    - Create credential with `PhoneAuthProvider.getCredential(verificationId, code)`
    - Sign in with `auth.signInWithCredential(credential)`
    - Show loading, disable "Verify" during verification
    - On success: navigate to ChatListActivity
    - On invalid code: show error, allow retry
    - On expired code: show expiry message, clear input, enable Resend
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 8.3_

  - [x] 4.4 Implement OTP resend and rate limiting in AuthActivity.kt
    - 60-second countdown timer (CountDownTimer) on "Resend Code"
    - Store `ForceResendingToken` from initial send
    - On resend: use resend token, clear OTP input, restart countdown
    - Handle rate limiting errors (show message, disable button)
    - Handle network failures (show error, keep button enabled)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

  - [x] 4.5 Implement SMS Retriever API auto-read in AuthActivity.kt
    - Start `SmsRetrieverClient` when OTP input is shown
    - Listen for SMS with app hash, extract 6-digit code
    - Auto-populate OTP_Input field (do NOT auto-submit)
    - Replace existing user-entered digits if auto-read fires
    - Gracefully handle failure (manual entry remains functional)
    - No READ_SMS permission required
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x] 4.6 Implement error handling in AuthActivity.kt
    - Detect no-network and show "No internet connection" with guidance
    - Preserve user-entered data on errors
    - Show generic error with retry for non-specific failures
    - Keep errors visible until user initiates new action
    - _Requirements: 8.1, 8.2, 8.4_

  - [x] 4.7 Update WelcomeActivity.kt as auth router
    - Check `FirebaseAuth.getInstance().currentUser`
    - If authenticated: navigate to ChatListActivity, finish WelcomeActivity
    - If not authenticated: navigate to AuthActivity, finish WelcomeActivity
    - Handle 10-second timeout on session validation → treat as no session
    - _Requirements: 1.2, 1.4, 6.1_

  - [x] 4.8 Register AuthActivity in AndroidManifest.xml
    - Add `<activity android:name=".AuthActivity" android:exported="false" />`
    - _Requirements: 1.1_

- [x] 5. Checkpoint - Android Auth flow complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement Profile Management (Both Platforms)
  - [x] 6.1 Create Profile Screen HTML and styles for Web
    - Add Profile tab/section in Chat List screen HTML (web/index.html)
    - Fields: display name input (max 50 chars), gender dropdown (Male/Female/Other/Prefer not to say), age input (13-120), city input (max 100 chars), Save button
    - Add inline validation error display elements
    - Style Profile Screen in web/style.css
    - _Requirements: 11.1, 11.2, 11.9_

  - [x] 6.2 Implement Profile CRUD logic for Web in web/app.js
    - On auth success or app resume: read profile from `/users/{uid}/profile`
    - If no profile exists: initialize with defaults (displayName: "AnnoUser", others null)
    - Validate fields client-side before save
    - Write to RTDB on "Save" button click
    - Cache display name locally for chat messages
    - Handle offline save (Firebase RTDB offline persistence)
    - _Requirements: 11.3, 11.6, 11.7, 11.8, 11.9, 11.10, 11.13_

  - [x] 6.3 Create ProfileActivity layout (activity_profile.xml) for Android
    - TextInputLayout for display name (max 50 chars)
    - Gender spinner/dropdown with options
    - Age number input (13-120 range)
    - City text input (max 100 chars)
    - Save button, loading indicator, validation error TextViews
    - _Requirements: 11.1, 11.2, 11.9_

  - [x] 6.4 Implement ProfileActivity.kt for Android
    - Read profile from `/users/{uid}/profile` on activity creation
    - If no profile: show defaults (displayName: "AnnoUser", others null)
    - Validate all fields before saving
    - Write to RTDB on "Save" button tap
    - Cache display name locally for chat messages
    - Enable Firebase RTDB disk persistence for offline support
    - Register ProfileActivity in AndroidManifest.xml
    - _Requirements: 11.3, 11.6, 11.7, 11.8, 11.9, 11.10, 11.13_

  - [x] 6.5 Add Profile navigation to ChatListActivity.kt
    - Add bottom navigation or tab for Profile
    - Navigate to ProfileActivity from Profile tab
    - _Requirements: 11.1_

  - [x] 6.6 Add Logout option to ChatListActivity.kt
    - Add overflow menu with "Logout" option in top app bar
    - Show confirmation dialog before logout
    - On confirm: call `FirebaseAuth.getInstance().signOut()`, navigate to AuthActivity
    - Retain saved chats locally
    - _Requirements: 6.2, 6.3, 6.4, 6.5_

- [x] 7. Checkpoint - Profile management complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Integrate Authentication with Chat System
  - [x] 8.1 Update Web chat message sending to use UID and profile display name
    - Replace `generateAnonName()` as the user identity source
    - Use Firebase UID as `senderId` in messages
    - Use profile display name as `senderName` in messages
    - Ensure gender, age, city, phone number are NEVER in message payload
    - _Requirements: 11.5, 11.11, 11.12_

  - [x] 8.2 Update Web chat message display to show short UID
    - Display `senderName` (profile display name) as primary identifier
    - Display first 8 characters of `senderId` (UID) in smaller font below name
    - _Requirements: 11.12_

  - [x] 8.3 Update Web matchmaking to require authentication
    - Gate access to queue/sessions behind authenticated state
    - Use UID for queue entries and session references
    - _Requirements: 10.2_

  - [x] 8.4 Update Android ChatMessage model and MessageAdapter
    - Ensure `senderId` stores Firebase UID
    - Ensure `senderName` stores profile display name
    - Update MessageAdapter to display short UID (first 8 chars) below sender name
    - Ensure no private profile fields in message data
    - _Requirements: 11.5, 11.11, 11.12_

  - [x] 8.5 Update Android ChatListActivity to load display name from profile
    - Replace intent-extra-based name with profile-based display name
    - Use UID for queue and session operations
    - _Requirements: 10.2, 11.11_

  - [x] 8.6 Update Web session persistence and identity logic
    - Remove `generateAnonName()` as primary identity (keep for fallback only if needed)
    - Associate Account_ID with all locally cached data
    - On logout + re-login with same phone: restore profile data, generate no new anon name (use display name)
    - _Requirements: 7.1, 7.2, 7.3, 10.1, 10.3, 10.4, 10.5_

  - [x] 8.7 Update Android session persistence and identity logic
    - Use UID as key for all locally cached data
    - On logout + re-login with same phone: restore profile and saved chats via Account_ID
    - _Requirements: 7.1, 7.2, 7.3, 10.1, 10.3, 10.4, 10.5_

- [x] 9. Final checkpoint - Full integration complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Align Test Mode behavior across Web and Android
  - [x] 10.1 Make the web auth flow reachable via the documented start command
    - Change the static root in `web/server.js` from `web/public/` to the web root so `index.html`, `app.js`, and `style.css` at `web/` are served
    - Verify `npm start` in `web/` serves the Auth_Screen
    - _Requirements: 12.10_

  - [x] 10.2 Remove residual validation from the web test-mode path
    - Delete the empty-code rejection in `testModeVerify()` so any code, including empty, is accepted
    - Stop gating the "Send Code" button on phone input content; keep it enabled at all times
    - Remove the `+910000000000` fallback in `testModeSendCode()` in favor of the shared derivation
    - _Requirements: 12.1, 12.2, 12.3_

  - [x] 10.3 Implement the shared Test_Account_ID derivation on web
    - Derive the id as `test_<all digits of the full E.164 number>`, or `test_anonymous` when no digits were entered
    - Remove the 12-digit truncation
    - Keep the random per-tab suffix on the matchmaking identity only, never on the Test_Account_ID
    - _Requirements: 12.5_

  - [x] 10.4 Adopt the shared Test_Session storage keys on web
    - Use `anonchat_test_active`, `anonchat_test_uid`, `anonchat_test_phone`
    - Key cached Profile_Data as `anonchat_test_profile_<uid>` instead of the global `anonchat_display_name`
    - On logout, clear only `anonchat_test_active`
    - _Requirements: 12.6, 12.7_

  - [x] 10.5 Remove the extra web name-entry step in test mode
    - Route an active Test_Session straight to the Chat List, matching Android
    - Leave display name editing to the Profile_Screen only
    - _Requirements: 12.8_

  - [x] 10.6 Add the test-mode notice to the web Auth_Screen
    - Add the notice element to `web/index.html` and style it in `web/style.css`
    - Show it only while `TEST_MODE` is true, with the same wording as Android
    - _Requirements: 12.4_

  - [x] 10.7 Align Android TestSession with the shared contract
    - Replace the random `test-<uuid>` id with the phone-derived scheme
    - Rename the SharedPreferences keys to `anonchat_test_active`, `anonchat_test_uid`, `anonchat_test_phone`, `anonchat_test_profile_<uid>`
    - Pass the entered phone number from `AuthActivity` into the derivation
    - _Requirements: 12.5, 12.6, 12.7_

  - [x] 10.8 Verify the production flow is unaffected on both platforms
    - Set both flags to false and confirm the Requirement 1-11 flows run with no residual test-mode behavior and any stored Test_Session is ignored
    - Confirm test-mode code stays confined to removable, marked blocks
    - _Requirements: 12.9, 12.11_

  - [x] 10.9 Delete the stale web/public duplicate (needs confirmation)
    - Remove `web/public/index.html` and `web/public/style.css` once 10.1 is verified
    - Ask before deleting; these are the pre-auth copies of the app
    - _Requirements: 12.10_

## Notes

- Tasks are split by platform (Web and Android) for parallel development when possible
- Firebase Auth Phone Provider handles OTP generation, delivery, and verification server-side
- reCAPTCHA is Web-only; SMS Retriever API is Android-only
- Profile data (gender, age, city) is strictly private — never included in messages or shared paths
- Display name from profile replaces the random Anonymous_Name in chat messages
- The short UID (first 8 chars) provides visual user disambiguation
- Firebase security rules enforce all access controls server-side
- Checkpoints allow incremental validation between major milestones
- Task group 10 covers Test Mode parity (Requirement 12). Both clients already carry a `TEST_MODE` flag; the work is to converge the two implementations, not to add the bypass
- The web/Android chat transport divergence (localStorage + BroadcastChannel vs Firebase RTDB) is explicitly out of scope here and needs its own spec

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["2.1", "2.2", "4.1", "4.8"] },
    { "id": 2, "tasks": ["2.3", "2.4", "4.2"] },
    { "id": 3, "tasks": ["2.5", "2.6", "2.7", "4.3", "4.4", "4.5"] },
    { "id": 4, "tasks": ["2.8", "2.9", "4.6", "4.7"] },
    { "id": 5, "tasks": ["6.1", "6.3"] },
    { "id": 6, "tasks": ["6.2", "6.4", "6.5", "6.6"] },
    { "id": 7, "tasks": ["8.1", "8.2", "8.3", "8.4", "8.5"] },
    { "id": 8, "tasks": ["8.6", "8.7"] },
    { "id": 9, "tasks": ["10.1", "10.2", "10.3", "10.6", "10.7"] },
    { "id": 10, "tasks": ["10.4", "10.5"] },
    { "id": 11, "tasks": ["10.8", "10.9"] }
  ]
}
```
