# Getting Chord-A-Licious into F-Droid

Ready in this repository:
- GPL-3.0-or-later `LICENSE`, SPDX headers on the source files
- Only FOSS dependencies (AndroidX, Compose, OkHttp); no Google services, no trackers
- `dependenciesInfo` switched off in `app/build.gradle.kts`, no signing config (F-Droid signs the app itself)
- `fastlane/metadata/android/en-US/` title, short and full description, changelog (`changelogs/26.txt` = versionCode 26), icon and feature graphic
- `docs/fdroiddata/nz.co.chordalicious.app.yml`: the recipe to submit
- `PRIVACY.md`

Still to do (these need you):
1. Put this project in a **public Git repository** (GitLab, Codeberg or GitHub) and push it.
2. Add 2-8 screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/` (no real e-mail address visible).
3. Tag the release: `git tag v0.9.3 && git push --tags`.
4. Fork https://gitlab.com/fdroid/fdroiddata, copy the recipe to `metadata/nz.co.chordalicious.app.yml`, fill in the TODO lines, and open a merge request. Their bot checks the build; a volunteer reviews it.
5. For each later release: bump `versionCode` and `versionName`, add `changelogs/<versionCode>.txt`, commit, tag `vX.Y.Z`. F-Droid's tag check picks it up.

Things the reviewers will notice:
- The app needs the Chord-A-Licious server, which is closed source: the recipe declares the **NonFreeNet** anti-feature. That is normal and does not stop inclusion.
- No Gradle wrapper is committed; the recipe uses F-Droid's own Gradle (`gradle: yes`). If a reviewer asks for the wrapper, run `gradle wrapper` once and commit `gradlew`, `gradlew.bat` and `gradle/wrapper/*`.
- The server address is a build setting (`SERVER` in `app/build.gradle.kts`).
