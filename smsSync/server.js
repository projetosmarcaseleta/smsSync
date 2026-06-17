const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const bodyParser = require('body-parser');

const app = express();
const server = http.createServer(app);

// WebSocket server
const wss = new WebSocket.Server({ server });

// Store connected clients (Chrome extensions)
const clients = new Set();

const API_SECRET = process.env.API_SECRET;

// Middleware to authenticate API requests
function authenticateAPI(req, res, next) {
  if (!API_SECRET) {
    console.warn('WARNING: API_SECRET is not set. API is unsecured.');
    return next();
  }
  
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Unauthorized: Missing or invalid Authorization header' });
  }

  const token = authHeader.split(' ')[1];
  if (token !== API_SECRET) {
    return res.status(403).json({ error: 'Forbidden: Invalid API Secret' });
  }

  next();
}

// Middleware
app.use(cors());
app.use(bodyParser.json({ limit: '10mb' }));
app.use(express.static('.')); // Serve static files (for test.html)

// WebSocket connection handling
wss.on('connection', (ws, req) => {
  if (API_SECRET) {
    // Basic parsing to extract the token from query string
    const urlStr = req.url || '';
    const tokenMatch = urlStr.match(/[?&]token=([^&]+)/);
    let token = tokenMatch ? tokenMatch[1] : null;
    
    if (token) {
      try {
        token = decodeURIComponent(token);
      } catch (e) {
        console.error('Failed to decode token:', e);
      }
    }
    
    if (token !== API_SECRET) {
      console.log(`WebSocket connection rejected: Invalid or missing token. Expected secret length: ${API_SECRET.length}, received token length: ${token ? token.length : 0}`);
      ws.close(4000, 'Unauthorized');
      return;
    }
  }

  console.log('Chrome extension connected');
  clients.add(ws);

  ws.on('close', () => {
    console.log('Chrome extension disconnected');
    clients.delete(ws);
  });

  ws.on('error', (error) => {
    console.error('WebSocket error:', error);
    clients.delete(ws);
  });

  // Send welcome message
  ws.send(JSON.stringify({
    type: 'connected',
    message: 'Connected to SMS Sync server'
  }));
});

// Helper function to broadcast SMS to all connected clients
function broadcastSMS(smsData) {
  const messageStr = JSON.stringify(smsData);
  let sentCount = 0;
  
  clients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(messageStr);
      sentCount++;
    }
  });

  return sentCount;
}

// REST API endpoint to receive SMS/MMS from mobile app
app.post('/api/sms', authenticateAPI, (req, res) => {
  const { sender, message, timestamp, type, subject, attachments } = req.body;
  const messageType = (type === 'MMS') ? 'mms' : 'sms';

  if (!sender) {
    return res.status(400).json({ error: 'Missing required field: sender' });
  }
  // MMS can have empty message body (image/media only)
  if (messageType === 'sms' && (message === undefined || message === null)) {
    return res.status(400).json({ error: 'Missing required field: message' });
  }

  const smsData = {
    type: messageType,
    sender: sender,
    message: message || '',
    timestamp: timestamp || new Date().toISOString()
  };

  if (subject) smsData.subject = subject;
  if (attachments && attachments.length > 0) smsData.attachments = attachments;

  console.log(`Received ${messageType.toUpperCase()}:`, smsData);

  // Broadcast to all connected Chrome extensions
  const sentCount = broadcastSMS(smsData);
  console.log(`Broadcasted ${messageType.toUpperCase()} to ${sentCount} client(s)`);

  res.json({
    success: true,
    message: `${messageType.toUpperCase()} received and broadcasted`,
    clientsNotified: sentCount
  });
});

// Test endpoint to send a test SMS
app.post('/api/test-sms', authenticateAPI, (req, res) => {
  const { sender, message, timestamp } = req.body;

  // Use default test values if not provided
  const testSmsData = {
    type: 'sms',
    sender: sender || '+1234567890',
    message: message || 'This is a test SMS message',
    timestamp: timestamp || new Date().toISOString()
  };

  console.log('Test SMS received:', testSmsData);

  // Broadcast to all connected Chrome extensions
  const sentCount = broadcastSMS(testSmsData);
  console.log(`Broadcasted test SMS to ${sentCount} client(s)`);

  res.json({ 
    success: true, 
    message: 'Test SMS sent and broadcasted',
    clientsNotified: sentCount,
    data: testSmsData
  });
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    connectedClients: clients.size,
    timestamp: new Date().toISOString()
  });
});

// Get server info
app.get('/api/info', (req, res) => {
  res.json({
    name: 'SMS Sync Backend',
    version: '1.0.0',
    connectedClients: clients.size,
    uptime: process.uptime()
  });
});

const PORT = process.env.PORT || 4000;

server.listen(PORT, () => {
  console.log(`SMS Sync Backend Server running on port ${PORT}`);
  console.log(`WebSocket server ready for Chrome extension connections`);
  console.log(`API endpoint: http://localhost:${PORT}/api/sms`);
  console.log(`Test endpoint: http://localhost:${PORT}/api/test-sms`);
  console.log(`Test page: http://localhost:${PORT}/test.html`);
  console.log(`\nExample test request:`);
  console.log(`curl -X POST http://localhost:${PORT}/api/test-sms \\`);
  console.log(`  -H "Content-Type: application/json" \\`);
  console.log(`  -d '{"sender": "+1234567890", "message": "Test message"}'`);
});

