#!/usr/bin/env node
/**
 * SMS/MMS WebSocket Bridge (role=extension)
 * ------------------------------------------
 * Conecta-se ao gateway SMS via WebSocket como `role=extension` e encaminha
 * cada evento recebido para o webhook público do Vello:
 *
 *   POST https://<vello-host>/api/public/webhooks/sms?connection_id=<uuid>
 *   Header: X-API-Secret: <secret-da-conexao>
 *
 * Uso:
 *   VELLO_WEBHOOK_URL="https://vellotalk.lovable.app/api/public/webhooks/sms?connection_id=XXX" \
 *   VELLO_WEBHOOK_SECRET="<api_secret da conexão>" \
 *   GATEWAY_WS_URL="ws://localhost:4000" \
 *   GATEWAY_API_SECRET="<bearer do gateway>" \
 *   node scripts/sms-ws-bridge.mjs
 *
 * Opcionais:
 *   GATEWAY_AUTH_MODE=auto|header|query|message   (default: auto → tenta os 3 em sequência)
 *   GATEWAY_WS_PATH=/                              (default: /)
 *   RECONNECT_MS=5000
 *
 * Dependência recomendada: `npm i ws` (necessário p/ enviar Authorization header).
 *
 * NÃO conecte um segundo cliente com role=device — apenas role=extension.
 */

import process from "node:process";

const {
  VELLO_WEBHOOK_URL,
  VELLO_WEBHOOK_SECRET,
  GATEWAY_WS_URL,
  GATEWAY_API_SECRET,
  GATEWAY_AUTH_MODE = "auto",
  GATEWAY_WS_PATH = "",
  RECONNECT_MS = "5000",
} = process.env;

if (!VELLO_WEBHOOK_URL || !GATEWAY_WS_URL) {
  console.error("Missing env: VELLO_WEBHOOK_URL and GATEWAY_WS_URL are required.");
  process.exit(1);
}

// Preferir o pacote `ws` (suporta headers). Fallback: WebSocket global.
let WS;
let WS_SUPPORTS_HEADERS = false;
try {
  const mod = await import("ws");
  WS = mod.WebSocket ?? mod.default;
  WS_SUPPORTS_HEADERS = true;
} catch {
  WS = globalThis.WebSocket;
  if (!WS) {
    console.error("WebSocket indisponível. Instale: npm i ws");
    process.exit(1);
  }
}

const RECONNECT_DELAY = Number(RECONNECT_MS) || 5000;
const AUTH_MODES = GATEWAY_AUTH_MODE === "auto"
  ? ["header", "query", "message"]
  : [GATEWAY_AUTH_MODE];
let authModeIndex = 0;

function log(...args) {
  console.log(new Date().toISOString(), "[sms-bridge]", ...args);
}

async function forwardToVello(payload) {
  log("→ vello payload:", JSON.stringify(payload));
  try {
    const res = await fetch(VELLO_WEBHOOK_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(VELLO_WEBHOOK_SECRET ? { "X-API-Secret": VELLO_WEBHOOK_SECRET } : {}),
      },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      log("webhook error", res.status, text.slice(0, 200));
    } else {
      log("→ vello ok", payload.type ?? "?", payload.sender ?? "");
    }
  } catch (e) {
    log("webhook fetch failed:", e?.message ?? e);
  }
}

function buildUrl(mode) {
  const url = new URL(GATEWAY_WS_URL);
  if (GATEWAY_WS_PATH) url.pathname = GATEWAY_WS_PATH;
  url.searchParams.set("role", "extension");
  if (mode === "query" && GATEWAY_API_SECRET) {
    url.searchParams.set("token", GATEWAY_API_SECRET);
  }
  return url.toString();
}

function connect() {
  const mode = AUTH_MODES[authModeIndex % AUTH_MODES.length];
  const target = buildUrl(mode);
  const useHeader = mode === "header" && GATEWAY_API_SECRET && WS_SUPPORTS_HEADERS;

  log(`connecting (auth=${mode}${useHeader ? "+Authorization header" : ""})`, target);

  let ws;
  try {
    ws = useHeader
      ? new WS(target, { headers: { Authorization: `Bearer ${GATEWAY_API_SECRET}` } })
      : new WS(target);
  } catch (e) {
    log("construct failed:", e?.message ?? e);
    scheduleReconnect(true);
    return;
  }

  let opened = false;
  let alive = true;

  const heartbeat = setInterval(() => {
    if (!alive) {
      try { ws.close(); } catch { /* noop */ }
      return;
    }
    alive = false;
    try { ws.ping?.(); } catch { /* noop */ }
  }, 30000);

  const onOpen = () => {
    opened = true;
    log("connected as role=extension");

    // Modo "message": manda handshake JSON pós-open
    if (mode === "message" && GATEWAY_API_SECRET) {
      try {
        ws.send(JSON.stringify({
          type: "auth",
          role: "extension",
          token: GATEWAY_API_SECRET,
        }));
      } catch (e) { log("auth send failed:", e?.message ?? e); }
    }

    forwardToVello({ type: "connected", timestamp: Date.now() });

    // Ping p/ manter last_connected_at fresco
    const pingTimer = setInterval(
      () => forwardToVello({ type: "ping", timestamp: Date.now() }),
      60000,
    );
    ws.once?.("close", () => clearInterval(pingTimer));
  };
  ws.on?.("open", onOpen) ?? ws.addEventListener?.("open", onOpen);

  ws.on?.("pong", () => { alive = true; });

  const onMessage = async (raw) => {
    alive = true;
    const text = typeof raw === "string" ? raw : raw?.data ?? raw?.toString?.();
    if (!text) return;
    log("← ws body:", text);
    let msg;
    try { msg = JSON.parse(text); } catch { log("non-json ignored:", String(text).slice(0, 80)); return; }
    if (msg && typeof msg === "object" && msg.type) {
      await forwardToVello(msg);
    }
  };
  ws.on?.("message", (data) => onMessage(data)) ?? ws.addEventListener?.("message", (ev) => onMessage(ev.data ?? ev));

  const onUnexpected = (res) => {
    // pacote `ws`: 'unexpected-response' entrega o handshake HTTP (401/403 etc)
    log(`handshake failed: HTTP ${res?.statusCode ?? "?"} ${res?.statusMessage ?? ""}`);
  };
  ws.on?.("unexpected-response", (_req, res) => onUnexpected(res));

  const onClose = (code, reason) => {
    clearInterval(heartbeat);
    const codeNum = typeof code === "number" ? code : code?.code;
    const reasonStr = String(reason ?? code?.reason ?? "");
    log("closed", codeNum ?? "", reasonStr, opened ? "(after open)" : "(before open)");

    // Se falhou antes do open (403/1003 etc), tenta o próximo modo de auth
    if (!opened && AUTH_MODES.length > 1) {
      authModeIndex += 1;
      log(`retrying with next auth mode: ${AUTH_MODES[authModeIndex % AUTH_MODES.length]}`);
    }
    scheduleReconnect(!opened);
  };
  ws.on?.("close", (code, reason) => onClose(code, reason)) ?? ws.addEventListener?.("close", (ev) => onClose(ev.code, ev.reason));

  const onError = (err) => log("error:", err?.message ?? err);
  ws.on?.("error", (err) => onError(err)) ?? ws.addEventListener?.("error", (ev) => onError(ev.error ?? ev.message ?? ev));
}

let reconnectTimer = null;
function scheduleReconnect(fast) {
  if (reconnectTimer) return;
  const delay = fast ? Math.min(RECONNECT_DELAY, 3000) : RECONNECT_DELAY;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

connect();

process.on("SIGINT", () => { log("SIGINT, exiting"); process.exit(0); });
process.on("SIGTERM", () => { log("SIGTERM, exiting"); process.exit(0); });
