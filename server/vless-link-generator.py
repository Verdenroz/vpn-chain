##run this in your vps

#WARNING: THIS SCRIPT WAS MADE BY AN AI AND NOT BY ME

#!/usr/bin/env python3
import json
import subprocess
import urllib.request
from urllib.parse import urlencode, quote

CONFIG = "/usr/local/etc/xray/config.json"
XRAY = "/usr/local/bin/xray"

with open(CONFIG) as f:
    cfg = json.load(f)

inbound = next(i for i in cfg["inbounds"] if i.get("protocol") == "vless")

uuid = inbound["settings"]["clients"][0]["id"]
flow = inbound["settings"]["clients"][0].get("flow", "xtls-rprx-vision")
port = inbound["port"]

reality = inbound["streamSettings"]["realitySettings"]
sni = reality["serverNames"][0]
short_id = reality["shortIds"][0]
private_key = reality["privateKey"]

result = subprocess.run(
    [XRAY, "x25519", "-i", private_key],
    text=True,
    capture_output=True
)

output = (result.stdout + "\n" + result.stderr).strip()

public_key = None

for line in output.splitlines():
    lower = line.lower()
    if ("publickey" in lower or "public key" in lower or "password" in lower) and ":" in line:
        public_key = line.split(":", 1)[1].strip()
        break

if not public_key:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if len(lines) == 1:
        public_key = lines[0]

if not public_key:
    print("Could not automatically find the public key.")
    print("\nRaw xray output was:\n")
    print(output)
    raise SystemExit(1)

vps_ip = urllib.request.urlopen("https://api.ipify.org").read().decode().strip()

params = {
    "encryption": "none",
    "flow": flow,
    "security": "reality",
    "sni": sni,
    "fp": "chrome",
    "pbk": public_key,
    "sid": short_id,
    "type": "tcp",
    "packetEncoding": "xudp"
}

link = f"vless://{uuid}@{vps_ip}:{port}?{urlencode(params)}#{quote('vless-reality')}"

print("\nYour VLESS client link:\n")
print(link)