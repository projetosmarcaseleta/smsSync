# SMS Sync Android App

Android app built with Kotlin that captures incoming SMS and forwards them to the backend server.

## Setup

1. Open the project in Android Studio
2. Sync Gradle files
3. Build the project
4. Install on your Android device

## Permissions

The app requires the following permissions:
- `RECEIVE_SMS`: To capture incoming SMS messages
- `READ_SMS`: To read SMS content
- `INTERNET`: To send SMS data to backend

These permissions are requested at runtime when you first open the app.

## Configuration

1. Open the app on your device
2. Enter your backend server URL (e.g., `https://your-backend-url.com`)
3. Click "Save Configuration"
4. Grant SMS permissions when prompted

The app will now automatically forward all incoming SMS to your backend server.

## How It Works

- `SmsReceiver`: BroadcastReceiver that listens for incoming SMS
- When SMS is received, it extracts sender and message
- Sends HTTP POST request to backend `/api/sms` endpoint
- Works in background - no need to keep app open

## Building

```bash
./gradlew assembleDebug
```

Or use Android Studio's Build > Build Bundle(s) / APK(s) > Build APK(s)

## Testing

1. Install the app on a physical Android device (SMS doesn't work on emulator)
2. Configure backend URL
3. Send a test SMS to the device
4. Check backend logs to verify SMS was received

## Troubleshooting

- **SMS not being sent**: Check backend URL is correct and accessible
- **Permissions denied**: Go to Settings > Apps > SMS Sync > Permissions and enable SMS
- **Network errors**: Ensure device has internet connection and backend is running


