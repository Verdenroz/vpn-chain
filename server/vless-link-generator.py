##run this in your vps

#WARNING: THIS SCRIPT WAS MADE BY AN AI AND NOT BY ME

#!/usr/bin/env python3
# Usage: vless-link-generator.py NAME [--stdout]
# Writes NAME's import link to /root/vpn-chain-NAME.link (0600) and prints only
# the path; --stdout prints the link instead.
import json
import os
import subprocess
import sys
import urllib.request
from urllib.parse import urlencode, quote

CONFIG = os.environ.get("CONFIG", "/usr/local/etc/xray/config.json")
SIDECAR = os.environ.get("SIDECAR", "/usr/local/etc/xray/devices.json")
XRAY = os.environ.get("XRAY", "/usr/local/bin/xray")

args = [a for a in sys.argv[1:] if a != "--stdout"]
to_stdout = "--stdout" in sys.argv[1:]
if len(args) != 1:
    raise SystemExit("usage: vless-link-generator.py NAME [--stdout]")
name = args[0]

with open(CONFIG) as f:
    cfg = json.load(f)

inbound = next(i for i in cfg["inbounds"] if i.get("tag") == "vless-reality-443")

client = next((c for c in inbound["settings"]["clients"] if c.get("email") == name), None)
if client is None:
    raise SystemExit(f"no such device: {name}")
uuid = client["id"]
flow = client.get("flow", "xtls-rprx-vision")
port = inbound["port"]

reality = inbound["streamSettings"]["realitySettings"]
sni = reality["serverNames"][0]
private_key = reality["privateKey"]

devices = json.load(open(SIDECAR)) if os.path.exists(SIDECAR) else {}
if name in devices:
    short_id = devices[name]["shortId"]
elif len(reality["shortIds"]) == 1:
    short_id = reality["shortIds"][0]
else:
    raise SystemExit(f"no short ID recorded for {name}; see devices.sh list")

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
    raise SystemExit("Could not automatically find the public key.")

vps_ip = os.environ.get("VPS_IP") or urllib.request.urlopen("https://api.ipify.org").read().decode().strip()

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

link = f"vless://{uuid}@{vps_ip}:{port}?{urlencode(params)}#{quote(name)}"

if to_stdout:
    print(link)
else:
    path = os.environ.get("OUT", f"/root/vpn-chain-{name}.link")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(link + "\n")
    print(path)
