#!/bin/bash
# =============================================================
# Script de configuração inicial da VPS para o projeto smsSync
# Execute este script UMA VEZ na VPS como root ou sudo
# =============================================================

set -e

echo "=== Iniciando setup da VPS ==="

# 1. Atualiza pacotes
apt-get update -y && apt-get upgrade -y

# 2. Instala Node.js 20 LTS (via NodeSource)
echo "Instalando Node.js 20..."
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs

# 3. Instala PM2 globalmente
echo "Instalando PM2..."
npm install -g pm2

# 4. Configura PM2 para iniciar com o sistema
pm2 startup systemd -u root --hp /root
systemctl enable pm2-root

# 5. Cria diretório da aplicação
APP_DIR="/var/www/smsSync"
mkdir -p $APP_DIR

# 6. Instala git (normalmente já vem no Ubuntu, mas garantindo)
apt-get install -y git

echo ""
echo "=== Setup concluído! ==="
echo ""
echo "Próximos passos MANUAIS:"
echo "  1. Clone o repositório:"
echo "     git clone https://<SEU_GH_TOKEN>@github.com/projetosmarcaseleta/smsSync.git $APP_DIR"
echo ""
echo "  2. Crie o arquivo .env:"
echo "     nano $APP_DIR/smsSync/.env"
echo "     (copie o conteúdo do .env.example e preencha os valores reais)"
echo ""
echo "  3. Inicie o app pela primeira vez:"
echo "     cd $APP_DIR/smsSync"
echo "     npm ci --omit=dev"
echo "     pm2 start server.js --name smsSync"
echo "     pm2 save"
echo ""
echo "  4. Configure os Secrets no GitHub (Settings > Secrets > Actions):"
echo "     VPS_HOST     = IP da sua VPS"
echo "     VPS_USER     = usuário SSH (ex: root)"
echo "     VPS_PASSWORD = senha SSH"
echo "     GH_TOKEN     = Personal Access Token do GitHub"
