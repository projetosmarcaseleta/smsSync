# SMS Sync Backend

Node.js backend server that receives SMS from Android app and forwards them to Chrome extension via WebSocket.

## Setup

```bash
npm install
npm start
```

For development with auto-reload:
```bash
npm run dev
```

## Environment Variables

- `PORT`: Server port (default: 3000)

## API Endpoints

### POST /api/sms
Receives SMS from Android app.

**Request Body:**
```json
{
  "sender": "+1234567890",
  "message": "Hello world",
  "timestamp": 1234567890
}
```

**Response:**
```json
{
  "success": true,
  "message": "SMS received and broadcasted",
  "clientsNotified": 1
}
```

### POST /api/test-sms
Test endpoint to send a test SMS payload. Useful for testing the WebSocket connection and Chrome extension without needing a real SMS.

**Request Body (all fields optional):**
```json
{
  "sender": "+1234567890",
  "message": "This is a test SMS message",
  "timestamp": "2024-01-01T12:00:00.000Z"
}
```

If fields are omitted, default test values will be used.

**Response:**
```json
{
  "success": true,
  "message": "Test SMS sent and broadcasted",
  "clientsNotified": 1,
  "data": {
    "type": "sms",
    "sender": "+1234567890",
    "message": "This is a test SMS message",
    "timestamp": "2024-01-01T12:00:00.000Z"
  }
}
```

**Example using curl:**
```bash
curl -X POST http://localhost:3000/api/test-sms \
  -H "Content-Type: application/json" \
  -d '{"sender": "+1234567890", "message": "Test message"}'
```

### GET /health
Health check endpoint.

### GET /api/info
Server information.

## WebSocket

The server also runs a WebSocket server on the same port. Chrome extensions connect to `ws://your-server.com` or `wss://your-server.com` (for HTTPS).

When an SMS is received via POST `/api/sms`, it's automatically broadcasted to all connected WebSocket clients.

## Deployment

⚠️ **Important**: This backend requires WebSocket support. Not all free hosting plans support WebSockets reliably.

### Railway.app (Recommended - Free Tier Friendly)
1. Create account at [railway.app](https://railway.app)
2. Connect your GitHub repository
3. Create new project → Deploy from GitHub
4. Set root directory to `backend`
5. Start command: `npm start`
6. Deploy automatically
7. Your app will be available at `https://your-app.up.railway.app`
8. Use `wss://your-app.up.railway.app` for Chrome extension WebSocket connection

### Fly.io (Free Tier Available)
1. Install Fly CLI: `curl -L https://fly.io/install.sh | sh`
2. `cd backend`
3. `fly launch` (follow prompts)
4. `fly deploy`
5. Use `wss://your-app.fly.dev` for WebSocket connection

### Render.com (Paid Plan Required)
⚠️ **Note**: Render's free tier spins down after 15 minutes, breaking WebSocket connections. You need a paid plan ($7+/month).

1. Create new Web Service
2. Connect repository
3. Set root directory to `backend`
4. Build command: `npm install`
5. Start command: `npm start`
6. Upgrade to paid plan for WebSocket support

### Heroku (Paid Plan Required)
1. `cd backend`
2. `heroku create your-app-name`
3. `git push heroku main`
4. Requires paid dyno for WebSocket support

## Important Notes

- The backend must be publicly accessible (hosted)
- Use HTTPS/WSS in production for security
- Consider adding authentication/API keys for production use

