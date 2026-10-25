# Per-device identities

Give each device its own VLESS UUID and REALITY short ID on the relay, so a lost
phone is revoked without touching the others. `server/devices.sh` runs on your
machine and manages the VPS over SSH. New credentials are generated on the
server and written to a `0600` file locally; they are never printed.

Needs `jq`, `xray`, `openssl` and `flock` on the VPS, and a local `secrets.env`
with `VPS_IP`, `REALITY_PUBKEY` and `SNI`. SSH defaults to `root@VPS_IP` with the
key `~/.ssh/id_1984_relay`; set `VPN_CHAIN_SSH_KEY` to change the key, or pass
`user@vps` as the last argument.

## Commands

```
server/devices.sh add NAME    [--self|--out PATH] [user@vps]
server/devices.sh rekey NAME  [--self|--out PATH] [user@vps]
server/devices.sh revoke NAME [--force] [user@vps]
server/devices.sh rename OLD NEW [user@vps]
server/devices.sh adopt NAME [user@vps]
server/devices.sh list [user@vps]
```

| Command | Does |
|---|---|
| `add` | New UUID and short ID for `NAME` |
| `rekey` | New UUID and short ID for an existing device; the old pair stops working |
| `revoke` | Removes the device. The last device needs `--force`. Revoking twice is harmless. |
| `rename` | Renames a device |
| `adopt` | Names the one client that exists on the server but not in `devices.json`; see [Migrating](#migrate-an-existing-vps) |
| `list` | Name, date added, bytes up and down, last seen |

Names are lowercase letters, digits, `.`, `_` and `-`, start with a letter or
digit, and run up to 32 characters.

Every change is validated with `xray -test`, swapped in, and applied with an
xray restart. If xray does not come back, the old config is restored. Live
connections of all devices drop during the restart.

### Where the credentials land

| Flag | Result |
|---|---|
| none | `~/.config/vpn-chain/devices/NAME.env` (directory `0700`, file `0600`) |
| `--out PATH` | The file at `PATH` |
| `--self` | Updates `VLESS_UUID`, `SHORT_ID` and `DEVICE_NAME` in your own `secrets.env` and keeps `secrets.env.bak` |

`--self` and `--out` are mutually exclusive. The device file carries `VPS_IP`,
`REALITY_PUBKEY`, `SNI` and `SERVER_PORT` (when set) from your `secrets.env`,
plus the new identity. It never carries the `ENTRY_*` WireGuard keys: each
device needs its own entry config from the provider.

Traffic and last-seen figures in `list` come from xray's loopback stats API and
are kept in `/usr/local/etc/xray/devices.json` on the VPS.

## Add a device

```
server/devices.sh add phone
```

Move `~/.config/vpn-chain/devices/phone.env` to the phone and paste it into
**Settings → Chain identity → Import**, or use `--out` to write it somewhere you
control. Then delete the local copy.

To switch the machine you are on to its own identity:

```
server/devices.sh rekey laptop --self
```

The desktop app's **Show QR** copies the identity that device uses. A phone that
scans it shares that identity, and revoking either revokes both. Use `add` for
a phone you want revocable on its own.

## Migrate an existing VPS

A relay deployed before per-device identities has one shared client. Move it
over without changing the REALITY keypair:

1. Copy the whole `server/` directory to the VPS (the scripts need `server/lib/`)
   and re-run the deploy as root:

   ```
   scp -r server root@203.0.113.10:
   ssh root@203.0.113.10 'bash server/deploy-server.sh'
   ```

   It keeps the REALITY keypair, the existing client and short ID, and adds the
   stats API. `FRESH=1` would wipe them instead.
2. Name the existing client. `adopt` needs exactly one client and one short ID
   that `devices.json` does not know:

   ```
   server/devices.sh adopt shared
   ```

3. Add one identity per device, and import each on its device:

   ```
   server/devices.sh add laptop --self
   server/devices.sh add phone
   ```

4. When every device is on its own identity and connected, revoke the shared one:

   ```
   server/devices.sh revoke shared
   ```

`devices.sh add` refuses to run while an unadopted client exists.

## Lost or stolen phone

1. Revoke its relay identity:

   ```
   server/devices.sh revoke phone
   ```

2. Delete the phone's WireGuard configuration at the entry provider. The entry hop
   is a separate credential, and revoking the relay identity does not touch it.
3. If the phone still held the old shared identity, rotate it:
   [rotate-vless-identity.md](rotate-vless-identity.md).

## Tests

Both need Docker. The first run downloads the pinned Xray release and checks its
SHA-256.

```
server/tests/run.sh                # every suite-*.sh in a container
server/tests/run.sh suite-devices.sh
server/tests/e2e.sh                # real xray and client containers on a throwaway network
```

`e2e.sh` adds two devices, revokes one, and checks that the revoked short ID is
refused while the other keeps working.
