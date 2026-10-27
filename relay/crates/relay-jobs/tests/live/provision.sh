#!/usr/bin/env bash
# Runs as root inside the systemd container: installs the binaries and units the way a relay host
# has them, plus an xray with a REALITY inbound, its decoy and a sing-box client.
set -euo pipefail

GOOD=26.3.27
SNI=dest.test
UUID=4f1d7c0e-8d0e-4b8e-9d57-6f3f4b2f9c11
SHORT_ID=0123456789abcdef

install -Dm755 /opt/build/relay-jobs /usr/local/bin/relay-jobs
install -Dm755 /opt/build/relay-privileged /usr/local/libexec/vpn-chain/relay-privileged
install -m644 /opt/units/relay-privileged.path /opt/units/relay-privileged.service /etc/systemd/system/
install -Dm644 /opt/units/xray.service.d/10-vpn-chain.conf /etc/systemd/system/xray.service.d/10-vpn-chain.conf
install -Dm644 /opt/units/vpn-chain.sysusers /etc/sysusers.d/vpn-chain.conf
install -Dm644 /opt/units/vpn-chain.tmpfiles /etc/tmpfiles.d/vpn-chain.conf
systemd-sysusers
systemd-tmpfiles --create

for dir in /opt/bins/xray/*/; do
    version="$(basename "$dir")"
    install -Dm755 "$dir/xray" "/opt/vpn-chain/xray/$version/xray"
done
ln -s "$GOOD" /opt/vpn-chain/xray/current

keys="$(/opt/vpn-chain/xray/$GOOD/xray x25519)"
private="$(sed -n 's/^PrivateKey: *//p' <<<"$keys")"
public="$(sed -n 's/^Password (PublicKey): *//p' <<<"$keys")"

install -d /etc/dest
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$SNI" -addext "subjectAltName=DNS:$SNI" \
    -keyout /etc/dest/key.pem -out /etc/dest/cert.pem 2>/dev/null
cat >/etc/nginx/conf.d/dest.conf <<EOF
server {
    listen 127.0.0.1:8443 ssl;
    http2 on;
    server_name $SNI;
    ssl_certificate /etc/dest/cert.pem;
    ssl_certificate_key /etc/dest/key.pem;
    ssl_protocols TLSv1.3;
    return 200 "ok";
}
EOF

cat >/etc/systemd/system/xray.service <<EOF
[Unit]
After=nginx.service
[Service]
User=xray
ExecStart=/usr/local/bin/xray
EOF

install -d -o root -g xray -m 755 /usr/local/etc/xray
cat >/usr/local/etc/xray/config.json <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [{
    "tag": "vless-reality", "listen": "127.0.0.1", "port": 4443, "protocol": "vless",
    "settings": { "clients": [{ "id": "$UUID", "flow": "xtls-rprx-vision" }], "decryption": "none" },
    "streamSettings": {
      "network": "tcp", "security": "reality",
      "realitySettings": {
        "dest": "127.0.0.1:8443", "serverNames": ["$SNI"],
        "privateKey": "$private", "shortIds": ["$SHORT_ID"]
      }
    }
  }],
  "outbounds": [{ "tag": "direct", "protocol": "freedom" }]
}
EOF
chown root:xray /usr/local/etc/xray/config.json
chmod 640 /usr/local/etc/xray/config.json

cat >/root/client.json <<EOF
{
  "log": { "level": "warn" },
  "inbounds": [{ "type": "mixed", "listen": "127.0.0.1", "listen_port": 1080 }],
  "outbounds": [{
    "type": "vless", "tag": "vless-proxy", "server": "127.0.0.1", "server_port": 4443,
    "uuid": "$UUID", "flow": "xtls-rprx-vision", "network": "tcp",
    "tls": {
      "enabled": true, "server_name": "$SNI",
      "utls": { "enabled": true, "fingerprint": "chrome" },
      "reality": { "enabled": true, "public_key": "$public", "short_id": "$SHORT_ID" }
    },
    "packet_encoding": "xudp", "connect_timeout": "3s"
  }],
  "route": { "final": "vless-proxy" }
}
EOF
cat >/etc/systemd/system/client.service <<EOF
[Service]
ExecStart=/opt/bins/sing-box/sing-box run -c /root/client.json
EOF

cat >/usr/local/bin/through-tunnel <<'EOF'
#!/bin/sh
exec curl -sk -m 8 -o /dev/null --socks5-hostname 127.0.0.1:1080 https://127.0.0.1:8443/
EOF
chmod 755 /usr/local/bin/through-tunnel

install -d /etc/systemd/system/relay-privileged.service.d
printf '[Service]\nEnvironment=RELAY_JOBS_CONFIRM_SECS=10\n' >/etc/systemd/system/relay-privileged.service.d/live.conf

systemctl daemon-reload
systemctl restart nginx.service
systemctl start xray.service client.service
systemctl enable --now relay-privileged.path
