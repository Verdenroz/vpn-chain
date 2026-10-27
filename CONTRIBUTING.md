# Contributing

[CLAUDE.md](CLAUDE.md) has the module map and the invariants to check before
changing an area. Read [.impeccable.md](.impeccable.md) before touching the UI
or its copy.

## Build and test

Gradle 8.13 must run on JDK 17 to 21. `gradle.properties` points
`org.gradle.java.home` at `/usr/lib/jvm/java-21-openjdk`; change it there, or in
`~/.gradle/gradle.properties` to keep it machine-local. Versions live in
`gradle/libs.versions.toml`.

```
./gradlew :desktopApp:run                  # desktop app
./gradlew :androidApp:assembleDebug        # Android debug APK
./gradlew :desktopApp:installDesktopEntry  # launcher entry for a source build
./gradlew test                             # every module's commonTest and desktopTest
./gradlew :core:config:desktopTest --tests "*.SingBoxConfigFactoryTest"
```

Both targets are JVM, so the common tests run without a device. There is no
instrumented Android suite.

The kill-switch helper builds with `cli/killswitch/build.sh` (needs `gcc` and the
libcap headers). Shell scripts must pass `shellcheck`.

### Linux source build

`:desktopApp:run` finds `sing-box` and `vpn-chain-killswitch` on the `PATH`; an
installed `.deb` takes precedence with its own copies under `/opt/vpn-chain/libexec`.
`cli/setup-desktop.sh` is the source-build counterpart of the package: it builds the
helper and grants it `cap_net_admin`, installs the relay unit for the `sing-box` on
your `PATH`, and installs the polkit rules. Run it again after you rebuild the helper
or move `sing-box`. `cli/vpn-chain` is the bash command line for the same setup.

### Linux package

```
./gradlew :desktopApp:createReleaseDistributable
desktopApp/deb/build-deb.sh desktopApp/build/compose/binaries/main-release/app/vpn-chain dist
desktopApp/deb/test-install.sh dist/vpn-chain_*_amd64.deb debian:trixie-slim
```

`build-deb.sh` needs `dpkg-deb`, `gcc` with the libcap headers and `curl`, so run it
on Debian or Ubuntu or in a container. The package holds the app image, the pinned
`sing-box` and the helper under `/opt/vpn-chain`, the relay unit, the polkit rules
and the update action. The helper alone carries a file capability, set in `postinst`;
`sing-box` gets its capabilities from the unit. `test-install.sh` installs, removes
and purges the package in a container with no privileges and no systemd. The
`deb` workflow runs all of this on every change to the files it packages.

### Server scripts

These need Docker. The first run downloads the pinned Xray and checks its SHA-256.

```
server/tests/run.sh                     # every suite-*.sh in a container
server/tests/run.sh suite-devices.sh    # one suite
server/tests/e2e.sh                     # real xray and sing-box on a throwaway network
KNOWN_BAD=1 server/tests/matrix.sh      # every Xray x sing-box pair, as CI runs it
```

`matrix.sh` runs `e2e.sh` for each pinned Xray against each sing-box the apps
ship. `KNOWN_BAD=1` also requires Xray v26.10.21 with sing-box 1.14.3 to fail.

### Relay agent

`relay/` is a Cargo workspace: `relayd` and the xray API client it drives.

```
cd relay
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
crates/xray-api/tests/fetch-bins.sh && cargo test -p xray-api --locked --test live -- --ignored
```

The live tests run the client against a real Xray and the apps' sing-box.

## Bump Xray or sing-box

The apps and the relay must agree on REALITY, and a new pair can break it with
nothing in the unit tests noticing. Bump only with `matrix.sh` green.

- **Xray:** `XRAY_VERSION` and both SHA-256 values in `server/lib/pins.sh`, and the
  pin in `relay/crates/xray-api/tests/fetch-bins.sh`.
- **sing-box:** `libbox` in `gradle/libs.versions.toml` (Android),
  `SINGBOX_VERSIONS_DEFAULT` and its SHA-256 values in `server/lib/pins.sh` (the
  `.deb` bundles the `libbox` version, checked against that SHA-256), the
  pins in `relay/crates/xray-api/tests/fetch-bins.sh`, and `SING_BOX_VERSION` in
  `.github/workflows/blocklists.yml`. The blocklists must compile with the oldest
  sing-box the apps run, because an older runtime cannot read a newer `.srs`.

## Release

Pushing a `v*` tag runs `.github/workflows/release.yml`.

1. Set `vpnchain.version` in `gradle.properties` to `MAJOR.MINOR.PATCH`. The
   Android version code is `major * 10000 + minor * 100 + patch`.
2. Commit, then tag and push. The tag must match `vpnchain.version`, or the
   publish job fails.

   ```
   git tag v0.2.1 && git push origin v0.2.1
   ```

3. Approve the `publish` job in the `release` environment. It publishes a
   pre-release, which no installed copy is offered.
4. Install it from the release page and test it on a device, then promote it.
   The in-app updater only sees the latest full release.

   ```
   gh release edit v0.2.1 --prerelease=false --latest
   ```

The release carries the signed APKs (arm64-v8a, x86_64), the `.deb`, and
`manifest.json` with the size and SHA-256 of each. The installed app downloads the
`.deb`, checks those, and hands it to `desktopApp/deb/vpn-chain-install-update`
through `pkexec`, which hashes its own copy again before `apt` installs it.

### Signing

The release fails without the signing secrets, since an APK signed with another
key cannot update an installed one. Create the keystore once and keep it: lose
it and installed copies can never update again.

```
keytool -genkeypair -v -keystore release.jks -alias vpn-chain \
  -keyalg RSA -keysize 4096 -validity 10000
```

| Repository secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `vpn-chain` |
| `ANDROID_KEY_PASSWORD` | Key password |

Whoever can push a `v*` tag can ship an update to every installed copy. Protect
`v*` tags with a ruleset, and require reviewers on the `release` environment,
which GitHub otherwise creates unprotected on the first release.

## Commits and pull requests

Conventional commits (`feat:`, `fix:`, `docs:`, `ci:`, ...), subject at most 50
characters. Documentation lives in the README, this file, CLAUDE.md, or a comment
next to the code it explains. There is no docs directory, and CI fails on a link
to one.
