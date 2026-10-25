# Releasing

Pushing a `v*` tag runs `.github/workflows/release.yml`.

## Cut a release

1. Set `vpnchain.version` in `gradle.properties` to `MAJOR.MINOR.PATCH`. The
   Android version code derives from it (`major * 10000 + minor * 100 + patch`).
2. Commit, then tag and push:

   ```
   git tag v0.1.7 && git push origin v0.1.7
   ```

The tag must match `vpnchain.version`. The publish job fails when the built APKs
carry another version or a build is missing.

## What gets published

Each build runs on its own native runner.

| Asset | Notes |
|---|---|
| `vpn-chain-<version>-android-arm64-v8a.apk`, `...-android-x86_64.apk` | Signed release APKs (arm64 for phones, x86_64 for emulators) |
| `.deb`, `vpn-chain-<version>-linux-x64.tar.gz` | The tarball is what the managed install and updates use |
| `.msi`, `.dmg` | The `.dmg` is unsigned: right-click, Open |
| `manifest.json` | Size and SHA-256 of both APKs and the Linux tarball |

The apps read `manifest.json` from the latest release to update themselves.

## Signing

The release fails without signing secrets, because an APK signed with another key
cannot update an installed one. Create a keystore once:

```
keytool -genkeypair -v -keystore release.jks -alias vpn-chain \
  -keyalg RSA -keysize 4096 -validity 10000
```

Add these repository secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `vpn-chain` |
| `ANDROID_KEY_PASSWORD` | Key password |

Keep the keystore. Lose it and installed copies can no longer update.

## Recommended GitHub settings

Whoever can push a `v*` tag can ship an update to every installed copy.

- [ ] A tag ruleset for `v*` that restricts creation, update and deletion.
- [ ] A protected `release` environment with a required reviewer. The workflow
      must also name it (`environment: release` on the `publish` job); it does
      not yet, so the environment has no effect until it does.

The workflow already limits `contents` to read-only outside the publish job,
keeps no git credentials after checkout, and pins every action to a commit.

## How updates reach users

| Platform | Mechanism |
|---|---|
| Android | Downloads the APK for its ABI, checks size and SHA-256 against the manifest, hands it to the system installer. Restarts the chain afterwards if it was up. Needs "install unknown apps" for the app. |
| Linux desktop | Downloads the tarball, checks size and SHA-256, refuses entries or links outside its folder, unpacks to `versions/<version>`, swaps the `current` symlink and relaunches. |

Only the managed install (`cli/install-desktop.sh`) and release APKs update.
`core/update/.../DesktopInstall.kt` and `cli/install-desktop.sh` apply the same
checks; change them together.
