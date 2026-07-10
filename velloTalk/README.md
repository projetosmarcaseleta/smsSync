# SMS/MMS WebSocket Bridge

Bridge Node.js que conecta ao gateway SMS via WebSocket (`role=extension`)
e encaminha os eventos para o webhook público do Vello.

## Instalação

Requer Node 18+ (usa `--env-file`, nativo a partir do Node 20.6+; em
versões mais antigas exporte as variáveis manualmente).

```bash
npm install
```

## Configuração

Copie as variáveis abaixo para um arquivo `.env` na raiz do projeto:

```bash
VELLO_WEBHOOK_URL="https://vellotalk.lovable.app/api/public/webhooks/sms?connection_id=XXX"
VELLO_WEBHOOK_SECRET="<api_secret da conexão>"
GATEWAY_WS_URL="ws://localhost:4000"
GATEWAY_API_SECRET="<bearer do gateway>"

# opcionais
# GATEWAY_AUTH_MODE=auto           # auto (default) tenta header → query → message
```

## Start

```bash
npm start
```

Isso conecta ao gateway como `role=extension` e encaminha cada SMS/MMS
recebido para o webhook do Vello. A aplicação não gera arquivo de log —
o andamento (conexão, mensagens encaminhadas, erros) sai apenas no
console do processo.

⚠️ Rode **apenas uma instância** por vez — o gateway aceita só um
cliente `role=extension`; duas instâncias ativas causam entrega
duplicada ao Vello.

### Erros comuns

- **HTTP 403 + close code 1003 antes do open** → o gateway recusou a
  autenticação. O bridge já tenta 3 modos em sequência (`header`, `query`,
  `message`). Se os 3 falharem, confira `GATEWAY_API_SECRET`, se o gateway
  aceita `role=extension` naquele momento (ele costuma limitar a 1 cliente
  por vez) e o log de "handshake failed: HTTP …" impresso pelo bridge.
- **Handshake sem `Authorization`** → você está rodando sem o pacote `ws`
  (o WebSocket global do Node não suporta headers). Rode `npm i ws`.

Ou como serviço `systemd`:

```ini
# /etc/systemd/system/vello-sms-bridge.service
[Unit]
Description=Vello SMS/MMS WebSocket Bridge
After=network.target

[Service]
Type=simple
Environment=VELLO_WEBHOOK_URL=https://vellotalk.lovable.app/api/public/webhooks/sms?connection_id=XXX
Environment=VELLO_WEBHOOK_SECRET=xxx
Environment=GATEWAY_WS_URL=ws://localhost:4000
Environment=GATEWAY_API_SECRET=xxx
ExecStart=/usr/bin/node /opt/vello/sms-ws-bridge.mjs
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now vello-sms-bridge
sudo journalctl -u vello-sms-bridge -f
```

## Fluxo

```
Celular Gateway ──► WS localhost:4000  ──► sms-ws-bridge.mjs
                                              │  role=extension
                                              ▼
                          POST /api/public/webhooks/sms?connection_id=XXX
                                       X-API-Secret: <secret>
                                              ▼
                                      Vello (Cloud Worker)
                                              ▼
                          clientes → sellers → conversas → mensagens
```

## Envio (outbound)

O envio (analista → cliente) **não** usa este bridge — é HTTP direto do
Vello para `${backend_url}/api/send` com `Authorization: Bearer <secret>`.
Basta cadastrar `backend_url` e `api_secret` em Configurações → SMS.
