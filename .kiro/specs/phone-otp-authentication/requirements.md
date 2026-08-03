# Requirements Document

## Introduction

This document specifies the requirements for adding phone number OTP (One-Time Password) authentication to the AnonChat application. The feature replaces the current immediate-access flow (where users get a random anonymous name on launch) with a mandatory phone verification step. After successful verification, users still receive a random anonymous identity for chatting. Each verified phone number maps to a persistent account (via Firebase UID), ensuring the same user always accesses the same data. Additionally, users can maintain a private profile with personal information that is never disclosed to other users. This feature applies to both the Web and Android versions of the app, using Firebase Authentication as the backend provider.

## Glossary

- **OTP_Service**: The Firebase Authentication Phone provider that generates and verifies one-time passwords sent via SMS
- **Auth_Screen**: The screen displayed on app launch where users enter their phone number and OTP code
- **Phone_Input**: The form field where users enter their phone number including country code
- **OTP_Input**: The form field where users enter the 6-digit verification code received via SMS
- **Auth_Session**: A persistent login session created after successful OTP verification, stored locally and in Firebase
- **Anonymous_Name**: A randomly generated display name (e.g., "SwiftFox472") assigned after authentication
- **Rate_Limiter**: Firebase's built-in throttling mechanism that limits OTP requests per phone number
- **reCAPTCHA_Verifier**: The invisible reCAPTCHA widget used on Web to prevent automated SMS abuse
- **Country_Code_Selector**: A UI component that allows users to select their phone number country prefix
- **Account_ID**: The Firebase UID that uniquely identifies a user's account, derived from their verified phone number and persistent across all sessions
- **Profile_Screen**: The screen/tab where users can view and edit their personal information (name, gender, age, city)
- **Profile_Data**: The collection of personal information fields (name, gender, age, city) stored privately for a user's own reference
- **User_Profile_Store**: The private Firestore document or Realtime Database node where Profile_Data is persisted, accessible only by the owning user
- **Test_Mode**: A build-time development flag (`TEST_MODE` in `web/app.js`, `AuthActivity.TEST_MODE` on Android) that bypasses OTP_Service verification so the rest of the app can be built and exercised before Firebase is fully provisioned
- **Test_Session**: The locally stored stand-in for an Auth_Session created while Test_Mode is active, holding the test Account_ID and the entered phone number
- **Test_Account_ID**: The stand-in Account_ID used while Test_Mode is active, in place of a Firebase UID

## Requirements

### Requirement 1: Display Authentication Screen on Launch

**User Story:** As a user, I want to see a login screen when I open the app, so that I can verify my phone number before entering the chat.

#### Acceptance Criteria

1. WHEN the app launches and no Auth_Session exists, THE Auth_Screen SHALL display the Phone_Input field with a Country_Code_Selector defaulting to the device locale country code, or "+1" (US) if the device locale cannot be determined
2. WHEN the app launches and a valid Auth_Session exists (Firebase Authentication token is not expired and not revoked), THE Auth_Screen SHALL be skipped and the user SHALL be navigated directly to the Chat List screen with their existing Anonymous_Name
3. THE Auth_Screen SHALL display the AnonChat logo, a heading "Verify your phone", and a subtitle stating that the phone number is used only for verification and will not be visible to other users
4. IF the Auth_Session validation check fails due to network error or timeout (no response within 10 seconds), THEN THE app SHALL treat the session as absent and display the Auth_Screen

### Requirement 2: Phone Number Submission

**User Story:** As a user, I want to submit my phone number to receive a verification code, so that I can prove I own the number.

#### Acceptance Criteria

1. WHEN the user enters a phone number and taps "Send Code", THE OTP_Service SHALL send a 6-digit numeric OTP to the provided phone number via SMS, and the OTP SHALL remain valid for 120 seconds from the time of sending
2. THE Phone_Input SHALL accept only valid phone number formats in E.164 format (a leading "+" followed by 7 to 15 digits inclusive)
3. IF the user submits an empty or invalid phone number, THEN THE Auth_Screen SHALL display an inline error message "Enter a valid phone number with country code" and keep the user on the current screen without sending a request
4. WHILE the OTP request is in progress, THE Auth_Screen SHALL display a loading indicator and disable the "Send Code" button to prevent duplicate requests, and IF the request does not complete within 15 seconds, THEN THE Auth_Screen SHALL cancel the request, re-enable the "Send Code" button, and display an error message indicating a timeout occurred
5. WHEN the OTP is sent successfully, THE Auth_Screen SHALL transition to show the OTP_Input field with a message indicating the code was sent to the provided number
6. IF the OTP_Service fails to deliver the SMS, THEN THE Auth_Screen SHALL display an error message indicating that the code could not be sent, re-enable the "Send Code" button, and allow the user to retry
7. WHEN the user has successfully sent an OTP, THE OTP_Service SHALL reject further OTP requests for the same phone number for 60 seconds and THE Auth_Screen SHALL display a countdown timer showing the remaining wait time before a new code can be requested

### Requirement 3: OTP Verification

**User Story:** As a user, I want to enter the OTP code I received, so that I can complete phone verification and access the chat.

#### Acceptance Criteria

1. WHEN the user enters a 6-digit code and taps "Verify", THE OTP_Service SHALL validate the code against the sent OTP
2. WHEN the OTP is valid, THE OTP_Service SHALL create an Auth_Session and THE Auth_Screen SHALL navigate the user to the Welcome screen where an Anonymous_Name is generated
3. WHEN the OTP is invalid, THE Auth_Screen SHALL display an error message "Invalid code. Please try again." and allow the user to re-enter the code
4. THE OTP_Input SHALL accept only numeric characters with a maximum length of 6 digits
5. WHILE OTP verification is in progress, THE Auth_Screen SHALL display a loading indicator and disable the "Verify" button

### Requirement 4: OTP Resend Functionality

**User Story:** As a user, I want to request a new code if I didn't receive one, so that I can still verify my phone.

#### Acceptance Criteria

1. WHEN the OTP_Input is displayed, THE Auth_Screen SHALL show a "Resend Code" button that is initially disabled for 60 seconds with a visible countdown timer displaying remaining seconds
2. WHEN the countdown reaches zero, THE Auth_Screen SHALL enable the "Resend Code" button
3. WHEN the user taps "Resend Code", THE OTP_Service SHALL send a new OTP to the same phone number, THE Auth_Screen SHALL clear the OTP input field, restart the 60-second countdown, and disable the "Resend Code" button, and any previously issued OTP for that phone number SHALL be invalidated
4. IF the Rate_Limiter blocks the resend request (more than 3 resend requests within a 15-minute window), THEN THE Auth_Screen SHALL display an error message indicating that too many attempts have been made and the user must wait before retrying, and SHALL disable the "Resend Code" button until the rate limit window resets
5. IF the resend request fails due to a network or server error, THEN THE Auth_Screen SHALL display an error message indicating the request failed, SHALL NOT restart the countdown timer, and SHALL keep the "Resend Code" button enabled so the user can retry

### Requirement 5: Web Platform reCAPTCHA Integration

**User Story:** As a developer, I want reCAPTCHA verification on the web platform, so that automated bots cannot abuse the SMS sending functionality.

#### Acceptance Criteria

1. WHEN the web application initializes the Auth_Screen, THE reCAPTCHA_Verifier SHALL be configured as an invisible reCAPTCHA attached to the "Send Code" button, and THE "Send Code" button SHALL remain disabled until the reCAPTCHA_Verifier signals it is ready
2. IF reCAPTCHA verification fails or the reCAPTCHA challenge is dismissed by the user, THEN THE Auth_Screen SHALL display an error message indicating that verification failed and the user should refresh the page, and THE "Send Code" button SHALL remain disabled
3. THE reCAPTCHA_Verifier SHALL operate invisibly without requiring manual user interaction when the user's risk score does not trigger a challenge; WHEN a visible challenge is triggered, THE Auth_Screen SHALL allow the user to complete the challenge before proceeding with the OTP request
4. IF the reCAPTCHA script fails to load within 10 seconds of Auth_Screen initialization, THEN THE Auth_Screen SHALL display an error message indicating that verification could not be loaded and suggesting the user check for browser extensions or network issues blocking the script

### Requirement 6: Session Persistence and Logout

**User Story:** As a user, I want to stay logged in between app sessions, so that I don't have to verify my phone every time I open the app.

#### Acceptance Criteria

1. THE Auth_Session SHALL persist across app restarts using Firebase Authentication state persistence (local storage on Web, SharedPreferences on Android) and SHALL remain valid until the user explicitly logs out or the Firebase token is revoked server-side
2. WHEN the user taps the "Logout" option, THE app SHALL display a confirmation prompt before proceeding with logout
3. WHEN the user confirms logout, THE Auth_Session SHALL be cleared and THE app SHALL navigate back to the Auth_Screen
4. THE Chat List screen SHALL include a "Logout" option accessible from an overflow menu in the top app bar
5. WHEN the user confirms logout, THE app SHALL retain saved chats locally but clear the current Anonymous_Name so a new one is generated on next login
6. IF the persisted Auth_Session is found to be invalid or expired on app launch, THEN THE app SHALL clear the local session and navigate to the Auth_Screen

### Requirement 7: Anonymous Name Generation After Authentication

**User Story:** As a user, I want to receive a fresh anonymous identity after logging in, so that my phone number is never associated with my chat name publicly.

#### Acceptance Criteria

1. WHEN an Auth_Session is created for the first time after login, THE app SHALL generate a new Anonymous_Name and associate it with the Auth_Session locally
2. THE Anonymous_Name SHALL persist for the duration of the Auth_Session so the user keeps the same name across app restarts
3. THE app SHALL NOT transmit or store the verified phone number in any chat messages, session data, or user-visible database paths
4. WHEN the user logs out and logs back in, THE app SHALL generate a new Anonymous_Name

### Requirement 8: Error Handling for Network and Service Failures

**User Story:** As a user, I want clear feedback when something goes wrong during verification, so that I know what to do next.

#### Acceptance Criteria

1. IF the device has no network connectivity when sending or verifying OTP, THEN THE Auth_Screen SHALL display an error message indicating no internet connection with guidance to check the network, and SHALL preserve any user-entered data in Phone_Input or OTP_Input
2. IF the OTP_Service returns a non-specific error (any error other than invalid code, expired code, or rate limiting), THEN THE Auth_Screen SHALL display a generic error message indicating a problem occurred, and SHALL provide a retry button that re-attempts the last failed operation (send OTP or verify OTP) using the same previously entered data
3. IF the OTP code expires (after 5 minutes from when the code was sent), THEN THE Auth_Screen SHALL display a message indicating the code has expired with guidance to request a new one, SHALL clear the OTP_Input field, and SHALL enable the "Resend Code" button bypassing any active countdown
4. IF any error message is displayed on the Auth_Screen, THEN THE Auth_Screen SHALL keep the error visible until the user initiates a new action (taps retry, resend, or modifies input), and SHALL NOT automatically dismiss the error

### Requirement 9: Android Auto-Read OTP Support

**User Story:** As an Android user, I want the app to detect and auto-fill the OTP from SMS, so that verification is faster and easier.

#### Acceptance Criteria

1. WHEN an SMS containing the OTP is received on Android and the OTP_Input field is displayed, THE Auth_Screen SHALL auto-populate the OTP_Input field with the received 6-digit code using the SMS Retriever API without automatically submitting the verification
2. THE app SHALL NOT request broad SMS read permissions (READ_SMS); only the SMS Retriever API hash-based approach SHALL be used
3. IF auto-read fails or is unavailable, THEN THE Auth_Screen SHALL keep the OTP_Input field editable and all controls (Verify button, Resend Code button, and countdown timer) fully functional for manual entry
4. IF the OTP_Input field already contains user-entered digits when an auto-read OTP is received, THEN THE Auth_Screen SHALL replace the existing content with the auto-read code

### Requirement 10: Persistent Account Identity

**User Story:** As a user, I want to always access the same account when I log in with the same phone number, so that my data and history are preserved across sessions and devices.

#### Acceptance Criteria

1. WHEN a user successfully verifies their phone number via OTP, THE OTP_Service SHALL return the same Account_ID (Firebase UID) for that phone number regardless of when or how many times the user logs in
2. THE Account_ID SHALL serve as the unique identifier for all user-specific data stored in the application, including saved chats, Anonymous_Name, and Profile_Data
3. WHEN a user logs in with a phone number that has been verified previously, THE app SHALL restore the existing account associated with that Account_ID rather than creating a new account
4. THE app SHALL use the Account_ID as the key for all locally cached user data so that switching between login sessions on the same device retrieves the correct data for each account
5. IF a user logs out and logs back in with the same phone number, THEN THE app SHALL associate the session with the same Account_ID and restore previously persisted account data (saved chats and Profile_Data), while generating a new Anonymous_Name per Requirement 7

### Requirement 11: Private User Profile

**User Story:** As a user, I want to store my personal information (name, gender, age, city) in a private profile, so that I can keep track of my own details without exposing them to other users during chat.

#### Acceptance Criteria

1. THE app SHALL include a Profile_Screen accessible via a dedicated tab or navigation item in the main interface
2. THE Profile_Screen SHALL display editable fields for the following Profile_Data: display name (free-text, maximum 50 characters), gender (selectable options: Male, Female, Other, Prefer not to say), age (numeric input, valid range 13 to 120), and city (free-text, maximum 100 characters)
3. WHEN the user modifies any Profile_Data field and taps "Save", THE app SHALL persist the updated Profile_Data to the User_Profile_Store associated with the current Account_ID
4. THE User_Profile_Store SHALL be secured with Firebase security rules that restrict read and write access exclusively to the authenticated user matching the Account_ID; no other user or unauthenticated client SHALL be able to read or write the Profile_Data
5. THE app SHALL NOT include gender, age, or city fields in chat messages, chat metadata, user presence information, or any data structure accessible to other users
6. WHEN the user navigates to the Profile_Screen, THE app SHALL load and display the most recently saved Profile_Data from the User_Profile_Store; IF no Profile_Data exists, THEN THE Profile_Screen SHALL display the default display name "AnnoUser" and null values for gender, age, and city
7. THE Profile_Data SHALL persist across app restarts and re-logins with the same phone number, retrievable via the Account_ID
8. IF the user attempts to save Profile_Data while offline, THEN THE app SHALL save the data locally and sync to the User_Profile_Store when network connectivity is restored
9. IF the user enters an invalid value (age outside 13-120 range, name exceeding 50 characters, city exceeding 100 characters), THEN THE Profile_Screen SHALL display an inline validation error and SHALL NOT save until the input is corrected
10. IF the user has not provided a display name in their Profile_Data, THEN THE app SHALL use "AnnoUser" as the default display name visible to other users in chat
11. THE display name from Profile_Data SHALL be used as the sender name visible to other users in chat messages, replacing the randomly generated Anonymous_Name
12. THE app SHALL display the Account_ID (or a short unique identifier derived from it) below the display name in a smaller font size in chat messages, so that users can be uniquely identified across sessions
13. WHEN a new account is created, THE Profile_Data SHALL initialize with display name set to "AnnoUser" and gender, age, and city set to null

### Requirement 12: Test Mode Parity Between Web and Android

**User Story:** As a developer, I want the OTP bypass to behave identically on Web and Android, so that testing one platform tells me something true about the other and neither client drifts while the project is still being built.

#### Context

Both clients already carry a Test_Mode flag, but the two implementations diverged. The observed divergences are:

| # | Behavior | Web (`web/app.js`) | Android (`AuthActivity.kt`) |
|---|----------|--------------------|------------------------------|
| D1 | Empty OTP_Input | Blocked with "Enter any code to continue" | Accepted |
| D2 | Empty Phone_Input | "Send Code" disabled until non-empty; falls back to `+910000000000` | Accepted (country code only) |
| D3 | Backend while in Test_Mode | Firebase never initialized (`auth = null`), profile in localStorage | Firebase RTDB used, anonymous sign-in attempted |
| D4 | Test_Account_ID scheme | `test_<first 12 phone digits>` plus a random per-tab suffix | Random `test-<uuid>` persisted in SharedPreferences |
| D5 | Test_Mode visible to user | No indicator | On-screen notice on Auth_Screen |
| D6 | Test_Session storage | `anonchat_test_uid`, `anonchat_test_phone`; presence of uid means logged in | `test_session_active` flag plus `test_session_uid`, `test_session_phone` |
| D7 | Display name cache key | `anonchat_display_name` (global, not per-account) | `test_profile_display_name_<uid>` (per-account) |
| D8 | Post-login Welcome screen | Name-entry screen shown on first login | Router only, no name entry |
| D9 | Reachability of the auth flow | `npm start` serves `web/public/`, an older copy with no Auth_Screen | N/A |

#### Acceptance Criteria

1. WHILE Test_Mode is active, THE Auth_Screen SHALL accept any value in Phone_Input, including an empty value, and SHALL NOT apply the E.164 validation of Requirement 2.2, on both Web and Android
2. WHILE Test_Mode is active, THE Auth_Screen SHALL accept any value in OTP_Input, including an empty value, and SHALL NOT apply the 6-digit constraint of Requirement 3.4, on both Web and Android
3. WHILE Test_Mode is active, THE Auth_Screen SHALL keep the "Send Code" and "Verify" controls enabled at all times on both Web and Android, and SHALL NOT display validation errors for Phone_Input or OTP_Input
4. WHILE Test_Mode is active, THE Auth_Screen SHALL display a visible notice on both Web and Android stating that verification is bypassed and any phone number and code will be accepted
5. WHILE Test_Mode is active, both platforms SHALL derive the Test_Account_ID from the entered phone number using one identical, documented scheme, so that the same phone number entered on Web and on Android yields the same Test_Account_ID
6. WHILE Test_Mode is active, both platforms SHALL persist the Test_Session under identical key names and with an explicit active flag, such that clearing the active flag ends the session while retaining the Test_Account_ID and any locally cached Profile_Data
7. WHILE Test_Mode is active, both platforms SHALL key locally cached Profile_Data by Test_Account_ID, so that two different test phone numbers on the same device or browser do not share Profile_Data
8. WHILE Test_Mode is active, THE app SHALL apply the same post-login navigation on both platforms: no Auth_Screen when a Test_Session is active, and no additional name-entry step beyond the Profile_Screen
9. WHEN Test_Mode is switched to inactive on either platform, THE app SHALL fall back to the real OTP_Service flow specified in Requirements 1 through 11 with no residual Test_Mode behavior, and any stored Test_Session SHALL be ignored
10. THE web application served by the documented start command (`npm start` in `web/`) SHALL serve the Auth_Screen implementation, so the authentication flow and its Test_Mode bypass are reachable without opening files directly
11. THE Test_Mode implementation on both platforms SHALL be confined to clearly marked code paths that can be removed without altering the production OTP_Service flow

#### Out of Scope

The following divergence is recorded but not addressed by this requirement, because it predates Test_Mode and affects production behavior as well:

- The web client performs matchmaking, session creation, and message exchange over `localStorage` plus `BroadcastChannel` (same-browser only), while Android uses Firebase Realtime Database. Cross-platform chat between a browser and the Android app is therefore not possible in any mode. This needs its own spec.
