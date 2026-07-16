#!/bin/bash
set -xe

# Install Java 17 (Amazon Corretto)
dnf install -y java-17-amazon-corretto-headless

# Fetch the application jar from S3 (instance profile grants GetObject)
mkdir -p /opt/app
aws s3 cp s3://fi-sparkathon-d6866/package/app.jar /opt/app/app.jar --region us-east-1

# Run as a managed service on port 80.
# No SPRING_PROFILES_ACTIVE -> app uses DefaultCredentialsProvider -> EC2 instance role.
cat >/etc/systemd/system/transcript.service <<'UNIT'
[Unit]
Description=Sparkathon Transcript Generator
After=network-online.target
Wants=network-online.target

[Service]
Environment=AWS_REGION=us-east-1
Environment=SERVER_PORT=80
ExecStart=/usr/bin/java -jar /opt/app/app.jar
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now transcript.service

# Health check: block until the service answers /health (up to ~5 min),
# so the bootstrap surfaces a failed start in the EC2 system log.
for i in $(seq 1 30); do
  if curl -sf http://localhost:80/health >/dev/null; then
    echo "health check OK"
    break
  fi
  echo "waiting for /health ($i)..."
  sleep 10
done
