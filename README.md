# Chord-A-Licious for Android

Native Kotlin / Jetpack Compose app. Open an audio file and see its chords as a bar-by-bar grid.
The chord analysis runs on the Chord-A-Licious server (https://chordalicious.co.nz); the app is a client.

- Licence: **GPL-3.0-or-later** (see `LICENSE`). The server is separate, proprietary software and is not part of this repository.
- Free and Pro tiers are enforced **by the server**. Subscribe through Stripe in your browser (no Google Play Billing, no Google services).
- No streaming-link analysis in this app (by design). It can open a YouTube link whose chords were already analysed on the website, and "Play on YouTube" hands the video to the YouTube app.
- Included: chord grid, key changes, busker mode, loop and metronome, instruments and tunings, chord templates, favourites, print to PDF, recording yourself (Pro).

## Build
Android Studio (Ladybug or newer), or `./gradlew assembleDebug` with JDK 17 and the Android SDK (platform 35).
Point the app at another server by changing `SERVER` in `app/build.gradle.kts`.

## F-Droid
See `docs/FDROID.md` for the submission steps, `fastlane/` for the store listing and `PRIVACY.md` for what is sent where.

Only FOSS dependencies (AndroidX, Compose, OkHttp). No proprietary SDKs, trackers or ads. Expect the "Non-Free Network Services" anti-feature, because the app needs the Chord-A-Licious server.
