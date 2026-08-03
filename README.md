# AnonChat - Anonymous Chat Android App

A simple Android app that lets users chat anonymously in real-time. No sign-up, no accounts — just open the app and start chatting.

## Features

- **Anonymous identity**: Random fun username generated per session (e.g., "SwiftFox472")
- **Real-time messaging**: Messages appear instantly via Firebase Realtime Database
- **No registration**: Zero friction to start chatting
- **Chat bubbles**: WhatsApp-style message bubbles (green for yours, white for others)
- **Last 100 messages**: Shows recent conversation history on join

## Setup Instructions

### 1. Create a Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **Add project** and follow the wizard
3. Once created, click the **Android icon** to add an Android app
4. Enter package name: `com.anonchat.app`
5. Download the `google-services.json` file
6. Place `google-services.json` in the `app/` directory

### 2. Set Up Realtime Database

1. In Firebase Console, go to **Build > Realtime Database**
2. Click **Create Database**
3. Choose your region
4. Start in **Test mode** (or apply the rules from `firebase-database-rules.json`)

### 3. Build and Run

1. Open the project in Android Studio
2. Make sure you have `google-services.json` in the `app/` folder
3. Sync Gradle
4. Run on a device or emulator (API 24+)

## Project Structure

```
app/src/main/java/com/anonchat/app/
├── MainActivity.kt          # Main chat screen with Firebase listener
├── adapter/
│   └── MessageAdapter.kt    # RecyclerView adapter for chat messages
└── model/
    └── ChatMessage.kt       # Data class for messages
```

## Tech Stack

- **Kotlin** - Primary language
- **Firebase Realtime Database** - Real-time message sync
- **Material Design 3** - UI components
- **ViewBinding** - Type-safe view access
- **RecyclerView + ListAdapter** - Efficient message list with DiffUtil

## How It Works

1. On app launch, a random anonymous name is generated (e.g., "MysticOwl287")
2. Messages are written to Firebase Realtime Database under `/messages`
3. A `ChildEventListener` picks up new messages in real-time
4. Each user sees their own messages on the right (green) and others' on the left (white)

## Security Note

This is a demo/prototype app. For production, you should:
- Add Firebase Authentication (anonymous auth at minimum)
- Implement proper security rules
- Add content moderation
- Rate limit message sending
- Add message length limits
