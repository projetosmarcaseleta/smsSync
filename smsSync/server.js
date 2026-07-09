const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');
const bodyParser = require('body-parser');

const app = express();
const server = http.createServer(app);

// WebSocket server
const wss = new WebSocket.Server({ server });

// Store connected clients (Chrome extensions) and devices (Android app)
const clients = new Set();
const deviceClients = new Set();

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
  const urlStr = req.url || '';

  if (API_SECRET) {
    // Basic parsing to extract the token from query string
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

  // Role identifies whether this socket is the Android device (command channel)
  // or a Chrome extension (message viewer). Defaults to extension for backward compatibility.
  const roleMatch = urlStr.match(/[?&]role=([^&]+)/);
  const role = roleMatch ? decodeURIComponent(roleMatch[1]) : 'extension';
  const set = role === 'device' ? deviceClients : clients;

  console.log(role === 'device' ? 'Android device connected' : 'Chrome extension connected');
  set.add(ws);

  ws.on('close', () => {
    console.log(role === 'device' ? 'Android device disconnected' : 'Chrome extension disconnected');
    set.delete(ws);
  });

  ws.on('error', (error) => {
    console.error('WebSocket error:', error);
    set.delete(ws);
  });

  if (role === 'device') {
    // Devices report back the outcome of a send_sms/send_mms command here.
    ws.on('message', (data) => {
      try {
        const parsed = JSON.parse(data);
        if (parsed && parsed.type === 'send_result') {
          console.log(`Device ack for request ${parsed.requestId}: ${parsed.success ? 'OK' : 'FAILED'}${parsed.error ? ' - ' + parsed.error : ''}`);
        }
      } catch (e) {
        console.error('Failed to parse device message:', e);
      }
    });
  }

  // Send welcome message
  ws.send(JSON.stringify({
    type: 'connected',
    message: 'Connected to SMS Sync server'
  }));
});

// Helper function to broadcast SMS to all connected Chrome extensions
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

// Helper function to send a send_sms/send_mms command to all connected Android devices
function broadcastToDevices(command) {
  const messageStr = JSON.stringify(command);
  let sentCount = 0;

  deviceClients.forEach((client) => {
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

// REST API endpoint to send an SMS/MMS through a connected Android device
app.post('/api/send', authenticateAPI, (req, res) => {
  const { to, message, type, subject, attachments } = req.body;
  const messageType = (type === 'MMS') ? 'mms' : 'sms';

  if (!to) {
    return res.status(400).json({ error: 'Missing required field: to' });
  }
  if (messageType === 'sms' && !message) {
    return res.status(400).json({ error: 'Missing required field: message' });
  }
  if (messageType === 'mms' && !message && (!attachments || attachments.length === 0)) {
    return res.status(400).json({ error: 'MMS requires a message and/or at least one attachment' });
  }

  const requestId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const command = {
    command: messageType === 'mms' ? 'send_mms' : 'send_sms',
    requestId,
    to,
    message: message || ''
  };

  if (messageType === 'mms') {
    if (subject) command.subject = subject;
    if (attachments && attachments.length > 0) command.attachments = attachments;
  }

  const sentCount = broadcastToDevices(command);
  console.log(`Queued ${command.command} (request ${requestId}) to ${sentCount} device(s)`);

  if (sentCount === 0) {
    return res.status(503).json({ error: 'No Android device connected to relay the message', requestId });
  }

  res.json({
    success: true,
    requestId,
    devicesNotified: sentCount
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
    connectedDevices: deviceClients.size,
    timestamp: new Date().toISOString()
  });
});

// Get server info
app.get('/api/info', (req, res) => {
  res.json({
    name: 'SMS Sync Backend',
    version: '1.0.0',
    connectedClients: clients.size,
    connectedDevices: deviceClients.size,
    uptime: process.uptime()
  });
});

const PORT = process.env.PORT || 4000;

server.listen(PORT, () => {
  console.log(`SMS Sync Backend Server running on port ${PORT}`);
  console.log(`WebSocket server ready for Chrome extension connections`);
  console.log(`API endpoint: http://localhost:${PORT}/api/sms`);
  console.log(`Send endpoint: http://localhost:${PORT}/api/send`);
  console.log(`Test endpoint: http://localhost:${PORT}/api/test-sms`);
  console.log(`Test page: http://localhost:${PORT}/test.html`);
  console.log(`\nExample test request:`);
  console.log(`curl -X POST http://localhost:${PORT}/api/test-sms \\`);
  console.log(`  -H "Content-Type: application/json" \\`);
  console.log(`  -d '{"sender": "+1234567890", "message": "Test message"}'`);
});

