# Veltrio for Android

Port of the macOS app (same features, same editorial look: true black,
tungsten amber, serif titles), in progress. Kotlin + Jetpack Compose,
minSdk 26; the player is libmpv (`dev.jdtech.mpv:libmpv`), like macOS
and Windows.

Two Gradle modules:

- `shared` — the core's bindings, storage, models, services (addons,
  catalogs, streams, Collection…), the player and the design tokens. The
  Android TV app ([veltrio-androidtv](https://github.com/VeltrioApp/veltrio-androidtv))
  includes this repository as a git submodule and builds on this module.
- `app` — the phone app.

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

- `ci.yml` — pushes to main, pull requests, manual runs: fetches the core, then builds and tests once
  `settings.gradle.kts` exists. Until then it only checks the core download.
- `release.yml` — tag `vX.Y.Z`: signed `.aab` + APK attached to the GitHub release (Play Console upload to add). Skipped until the project exists.

Organisation secrets used: `CORE_RELEASES_TOKEN` (optional: the repositories are public, so the workflow token can read core releases), `ANDROID_KEYSTORE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
