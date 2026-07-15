# SMS Sync

A real-time SMS synchronization system that forwards SMS messages from your Android phone to your Chrome extension on your laptop.

## Architecture

- **Backend Server**: Node.js/Express server with WebSocket support (needs to be hosted)
- **Android App**: Kotlin app that captures SMS and sends to backend
- **Chrome Extension**: Receives SMS in real-time via WebSocket

## Project Structure

```
smsSync/
├── backend/           # Node.js backend server
├── android-app/       # Android app (Kotlin)
└── chrome-extension/ # Chrome extension
```

## Setup Instructions

### 1. Backend Server

```bash
cd backend
npm install
npm start
```

The server runs on port 3000 by default. For production, set the `PORT` environment variable.

**Important**: The backend needs to be hosted on a public server (e.g., Heroku, Railway, Render) so both the mobile app and Chrome extension can access it.

### 2. Android App

1. Open the `android-app` folder in Android Studio
2. Build and run the app on your Android device
3. Grant SMS permissions when prompted
4. Enter your backend server URL (e.g., `https://your-backend-url.com`)
5. Click "Save Configuration"

The app will now automatically forward all incoming SMS to your backend.

### 3. Chrome Extension

1. Open Chrome and go to `chrome://extensions/`
2. Enable "Developer mode"
3. Click "Load unpacked" and select the `chrome-extension` folder
4. Click the extension icon
5. Enter your backend WebSocket URL (convert http to ws, e.g., `ws://your-backend-url.com` or `wss://your-backend-url.com`)
6. Click "Save & Connect"

## Backend Hosting

You need to host the backend on a public server. **Important**: This app requires WebSocket support for real-time messaging.

### ⚠️ WebSocket Support by Platform

| Platform | Free Tier | WebSocket Support | Notes |
|----------|-----------|-------------------|-------|
| **Railway.app** | ✅ Yes | ✅ Full Support | **Recommended** - 500 hours/month free |
| **Fly.io** | ✅ Yes | ✅ Full Support | Good free tier with WebSocket support |
| **Render.com** | ⚠️ Limited | ❌ Free tier issues | Free tier spins down, breaks WebSockets |
| **Heroku** | ❌ No | ✅ Paid plans | WebSocket support on paid dynos |
| **DigitalOcean** | ❌ No | ✅ Yes | App Platform supports WebSockets |

### Recommended: Railway.app (Free Tier Friendly)

1. Create account at [railway.app](https://railway.app)
2. Connect your GitHub repo
3. Create new project → Deploy from GitHub repo
4. Set root directory to `backend`
5. Deploy automatically
6. Get your public URL (e.g., `https://your-app.up.railway.app`)
7. Use `wss://your-app.up.railway.app` for Chrome extension

### Alternative: Fly.io (Free Tier)

1. Install Fly CLI: `curl -L https://fly.io/install.sh | sh`
2. `cd backend`
3. `fly launch` (follow prompts)
4. `fly deploy`
5. Get your URL and use `wss://your-app.fly.dev` for extension

### Render.com (Paid Plan Required)

⚠️ **Note**: Render's free tier spins down after 15 minutes of inactivity, which breaks WebSocket connections. You'll need a paid plan ($7+/month) for reliable WebSocket support.

1. Create account at render.com
2. Create new Web Service
3. Connect your repo and set root directory to `backend`
4. Upgrade to paid plan for WebSocket support
5. Deploy

### Heroku (Paid Plan)

1. Install Heroku CLI
2. `cd backend`
3. `heroku create your-app-name`
4. `git push heroku main`
5. Requires paid dyno for WebSocket support

## Configuration

- **Backend URL in Android App**: Use HTTPS URL (e.g., `https://your-backend.com`)
- **Backend URL in Chrome Extension**: Use WebSocket URL (e.g., `wss://your-backend.com`)

Note: If your backend is at `https://api.example.com`, the WebSocket URL should be `wss://api.example.com` (same domain, just change protocol).

## Features

- Real-time SMS forwarding
- No database required (stateless)
- Background SMS capture on Android
- Browser notifications for new SMS
- Message history in Chrome extension

## Permissions

### Android App
- `RECEIVE_SMS`: To capture incoming SMS
- `READ_SMS`: To read SMS content
- `INTERNET`: To send SMS to backend

### Chrome Extension
- `notifications`: To show SMS notifications
- `storage`: To save messages and configuration

## Troubleshooting

1. **SMS not appearing in extension**: Check that backend is running and accessible
2. **Connection issues**: Verify backend URL is correct in both app and extension
3. **Permissions**: Ensure Android app has SMS permissions granted
4. **WebSocket connection**: Check browser console for WebSocket errors

## Security Notes

- Consider adding authentication to the backend API
- Use HTTPS/WSS in production
- Add API key validation if needed

