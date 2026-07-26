# Rotating the upstream WireGuard key (server exit hop)

Rotate whenever the server's upstream WireGuard **private key** may be exposed
(shared in chat, committed, pasted into a tool, etc.). This replaces only the
exit-hop credentials — the VLESS/REALITY identity is untouched, so every client
keeps working without reconfiguration.

The new private key travels from your downloaded `.conf` straight to the
VPS over your own SSH session. It is never printed, committed, or shown to any
assistant.

## Steps

1. **Mint a new key + revoke the old one.**
   In your provider's WireGuard config generator, create a **new** configuration
   (this generates a fresh keypair). **Delete** the old configuration on that page
   so the leaked key stops working.

2. **Apply it to the server:**
   ```
   server/rotate-exit-key.sh ~/Downloads/new-exit.conf root@<vps>
   ```
   The script parses `PrivateKey` / `Address` / `PublicKey` / `Endpoint` from the
   `.conf`, patches the `exit-wg` outbound in `/usr/local/etc/xray/config.json`
   (backing it up first), validates with `xray -test`, and restarts xray. It aborts
   without applying if validation fails.

3. **Verify the exit IP changed** (with the relay up locally):
   ```
   curl --socks5-hostname 127.0.0.1:1080 https://api.ipify.org
   ```
   It should report the new exit, and `ip-api.com` should still show the
   provider's ASN.

## Notes

- The phone uses a **separate** WG config (see `docs/android.md`); rotate
  it independently in the same way if it may be exposed.
- Requires `jq` on the VPS. Override the SSH key with
  `VPN_CHAIN_SSH_KEY=/path/to/key`.
