# Veltrio for Android

Not started yet. This repository holds the CI/CD and the pinned
[veltrio-core](https://github.com/VeltrioApp/veltrio-core) release the
app will build on.

**Planned stack:** Kotlin + Jetpack Compose, minSdk 26. Player: libmpv for Android (mpv-android) or Media3/ExoPlayer.

## The core

`core.version` names the veltrio-core release to use;
`scripts/fetch-core.sh` downloads its `veltrio-core-android.zip` into `core/`
(gitignored). Needs [`gh`](https://cli.github.com) (signed in), or `VELTRIO_CORE_DIR=../veltrio-core` to
build from a local checkout.

In the app module: `sourceSets.main.jniLibs.srcDir("../core/jniLibs")`, `sourceSets.main.kotlin.srcDir("../core/kotlin")`, and depend on `net.java.dev.jna:jna:<version>@aar` (UniFFI's Kotlin bindings use JNA).

## CI/CD

- `ci.yml` — pushes to main, pull requests, manual runs: fetches the core, then builds and tests once
  `settings.gradle.kts` exists. Until then it only checks the core download.
- `release.yml` — tag `vX.Y.Z`: signed `.aab` + APK attached to the GitHub release (Play Console upload to add). Skipped until the project exists.

Organisation secrets used: `CORE_RELEASES_TOKEN` (optional: the repositories are public, so the workflow token can read core releases), `ANDROID_KEYSTORE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
