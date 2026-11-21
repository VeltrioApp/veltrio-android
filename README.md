# Veltrio for Android

Native Android phone and tablet app (same features and editorial look: true black,
tungsten amber, serif titles). Kotlin + Jetpack Compose,
minSdk 26; the player is libmpv (`dev.jdtech.mpv:libmpv`), like macOS
and Windows.

Two Gradle modules:

- `shared` — the core's bindings, storage, models, services (addons,
  catalogs, streams, Collection, debrid, IPTV, accounts, media servers,
  downloads, Chromecast…), the player, the design tokens and the screens. The
  Android TV app ([veltrio-androidtv](https://github.com/VeltrioApp/veltrio-androidtv))
  includes this repository as a git submodule and builds on this module.
- `app` — the phone app: its activity (numbered tabs), the download
  service. The TV app has its own activity (a side rail) and shows the
  same screens, with a focus frame and remote keys in the player.

## Build

JDK 21 (Android Studio's bundled one works:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`),
the Android SDK, then:

```bash
./scripts/fetch-core.sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

## The core

`core.version` names the veltrio-core release to use;
`scripts/fetch-core.sh` downloads its `veltrio-core-android.zip` into `core/`
(gitignored). Needs [`gh`](https://cli.github.com) (signed in), or `VELTRIO_CORE_DIR=../veltrio-core` to
build from a local checkout.

The `shared` module compiles `core/jniLibs` and `core/kotlin` in, with
`net.java.dev.jna:jna@aar` (UniFFI's Kotlin bindings use JNA).
`fetch-core.sh` also patches the generated bindings: UniFFI 0.29 declares
an error's `message` field next to an override of `Exception.message`,
which Kotlin 2.4 rejects.

## CI/CD

- `ci.yml` — pushes to main, pull requests, manual runs: fetches the core and builds the app.
- `release.yml` — tag `vX.Y.Z`: signed `.aab` + APK attached to the GitHub release.

Organisation secrets used: `CORE_RELEASES_TOKEN` (optional), `ANDROID_KEYSTORE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; optional `VELTRIO_TRAKT_CLIENT_ID`, `VELTRIO_TRAKT_CLIENT_SECRET`, `VELTRIO_SIMKL_CLIENT_ID`.

## Sending settings to a TV

Settings → « Envoyer vers une TV » sends this device's addons, IPTV
playlists, debrid keys, accounts, media servers, Collection and playback
choices to a Veltrio TV on the same network (« Depuis un téléphone » there).
The TV advertises `_veltrio-pair._tcp` over DNS-SD, listens on port 8765
(else any free one) and shows a 6-digit code:

1. `GET /veltrio-pair` → `{"name", "publicKey", "version": 1}`: an ephemeral
   P-256 key, uncompressed X9.63, base64.
2. The sender makes its own P-256 key pair; AES-256-GCM key =
   HKDF-SHA256(ikm: ECDH secret, salt: the code as UTF-8, info:
   `veltrio-pair-v1`).
3. `POST /veltrio-pair {"publicKey", "nonce" (12 bytes), "data"}`: `data`
   is the `SettingsBundle` JSON (see `Pairing.kt`), encrypted, tag
   appended. 200 `{"result"}`, or 403 for a wrong code; after 5 wrong
   codes the TV draws a new code and key.

Importing is additive: nothing already on the TV is removed. Any platform
with P-256 ECDH can send (CryptoKit on iOS / macOS, CNG on Windows).

## License

Dual-licensed under either of [Apache-2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT). libmpv (`dev.jdtech.mpv:libmpv`) is LGPL.
