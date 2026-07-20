# Pomodorough Android

Native Android client for [Pomodorough](https://pomodorough.egigoka.me), a
Pomodoro timer that keeps one account clock synchronized across devices.

- Web app: <https://pomodorough.egigoka.me>
- API base: <https://pomodorough.egigoka.me/api/v1>
- OpenAPI: <https://pomodorough.egigoka.me/openapi.yaml>

## Features

- Focus, short-break, and long-break timers with optional automatic breaks
- Offline-first actions backed by a durable Room command queue
- Account synchronization with optimistic local replay and canonical server state
- Google sign-in through Android Credential Manager
- Keystore-encrypted access and rotating refresh tokens
- Timer completion alarms and notifications
- Native Jetpack Compose interface based on Pomodorough's transit control board

## Requirements

- Android Studio or JDK 17
- Android SDK Platform 34
- Android SDK Build Tools 35.0.0
- Android 8.0 (API 26) or newer for installation

## Build

Production API and OAuth values are configured by default. Build and verify with:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

With an emulator or device connected, run Room migration, persistence, and
repository race tests with:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Generated APKs are written below `app/build/outputs/apk/`. Release APKs are
unsigned; configure normal Android signing before distribution.

Override either endpoint or Google server client ID with Gradle properties:

```sh
./gradlew :app:assembleDebug \
  -PPOMODOROUGH_API_BASE_URL=https://example.com/api/v1 \
  -PPOMODOROUGH_GOOGLE_SERVER_CLIENT_ID=example.apps.googleusercontent.com
```

## Google Sign-In

Credential Manager requests an ID token for
`POMODOROUGH_GOOGLE_SERVER_CLIENT_ID`. Google Cloud must also have an Android
OAuth client configured for package `me.egigoka.pomodorough` and each signing
certificate SHA-1 used by distributed builds. Run `./gradlew signingReport` to
inspect local signing fingerprints.

The API deployment must include every possible token `aud` and `azp` value in
`GOOGLE_NATIVE_CLIENT_IDS`. For the production defaults this includes the web
client ID:

```text
614768274539-5jrk37jie6415babe51ae4qiupif0m7v.apps.googleusercontent.com
```

It must also include any Android OAuth client ID Google emits as `azp`.

## Architecture

- `ui/` contains Compose screens, theme, and lifecycle-aware view model.
- `data/local/` stores canonical snapshots, settings, and immutable pending
  commands in Room.
- `data/api/` implements the JSON API and SSE revision stream with OkHttp.
- `data/auth/` performs nonce-bound Google exchange and serializes refresh-token
  rotation. Token pairs are encrypted with Android Keystore and replaced as one
  durable value.
- `domain/TimerReducer.kt` replays pending commands over the latest canonical
  timer to keep offline interaction deterministic.
- `data/TimerRepository.kt` coordinates durable actions, network recovery,
  serialized sync, retries, SSE hints, and timer alarms.

Local timer actions are persisted before appearing in UI. Sync acknowledgements
remove commands regardless of outcome, then remaining commands replay over the
server snapshot. Refresh calls are globally serialized because reusing a rotated
refresh token revokes its complete session family.

## License

Pomodorough Android is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version.

Pomodorough Android is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for details.

Complete corresponding source for every distributed release, including build
files, must remain available under these terms.
