# Rotate the server's upstream WireGuard key

Rotate when the private key of the server's exit hop (its WireGuard config to the
upstream provider) may be exposed: shared in chat, committed, pasted into a tool.
It replaces only the exit-hop credentials. The VLESS/REALITY identity is
untouched, so every client keeps working.

The new key goes from your downloaded `.conf` to the VPS over your own SSH
session. It is never printed or committed.

## Steps

1. **Mint a new key and revoke the old one.** In your provider's WireGuard config
   generator, create a new configuration (a fresh keypair) and delete the old
   configuration there, so the leaked key stops working.
2. **Apply it to the server:**

   ```
   server/rotate-exit-key.sh ~/Downloads/new-exit.conf [user@vps]
   ```

   The script reads `PrivateKey`, `Address`, `PublicKey` and `Endpoint` from the
   `.conf`, backs up `/usr/local/etc/xray/config.json` as `config.json.bak.*`,
   patches the `exit-wg` outbound, validates with `xray -test`, and restarts xray.
   It aborts without applying if validation fails.
3. **Verify the exit IP changed**, with the relay up locally:

   ```
   curl --socks5-hostname 127.0.0.1:1080 https://api.ipify.org
   ```

   It should show the new exit, and `ip-api.com` should still show the provider's
   ASN.

## Notes

- The target defaults to `root@VPS_IP` from your `secrets.env`. Set
  `VPN_CHAIN_SSH_KEY=/path/to/key` to change the SSH key.
- Needs `jq` on the VPS.
- The phone uses a separate WireGuard config ([android.md](android.md)). Rotate
  it the same way if it may be exposed.
