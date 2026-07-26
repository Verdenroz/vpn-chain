# Rotating the VLESS identity (relay client credentials)

Rotate whenever the **VLESS UUID** / **REALITY short_id** may be exposed (e.g.
shared in a conversation or captured in an AI memory store). Unlike the exit
key, this is **client-affecting**: every client must pick up the new
`secrets.env`, and the live connection breaks until you re-run `vpn-chain up`.

Blast radius of exposure is *unauthorized use* of your relay (someone routing
traffic out through your VPS), not decryption or identity compromise — so rotate
if you want to be consistent with an untrusted-context threat model, not because
your traffic was readable.

## Keeping the new secrets hidden

The new UUID/short_id are generated **on the server** and written **straight into
`~/.config/vpn-chain/secrets.env`** — they cross SSH captured into a shell
variable and are never echoed. The script prints only status, so it is safe to
run even where an assistant can see your terminal.

## Steps

```
# UUID + short_id only:
server/rotate-vless-identity.sh

# also regenerate the REALITY keypair (updates REALITY_PUBKEY):
server/rotate-vless-identity.sh --reality

# then re-render the client config and reconnect:
vpn-chain down && vpn-chain up
```

`vpn-chain status` should then show a healthy chain (entry via the entry app's
interface, a provider-owned
exit IP). If it breaks, restore the local backup `~/.config/vpn-chain/secrets.env.bak`
and the server backup `/usr/local/etc/xray/config.json.bak.*`.

## Notes

- The server's exit chain is untouched — only the inbound identity changes.
- Requires `jq` + `xray` + `openssl` on the VPS.
- Any other client (a future Android profile) must also be updated with the new
  UUID/short_id (+ REALITY public key if you used `--reality`).
