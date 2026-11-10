# Android app plan
1. **0.1 (done: scaffold, then website look: dark theme, logo, app icon)**: log in (existing account), open an audio file, bar grid, Detailed chords, Pro via Stripe in the browser, recognition greyed out "coming soon".
2. (done in 0.3.0) transpose + capo, diagrams, options sheet, tasty-o-meter, chord edits
3. 0.3: microphone "Record yourself" (Pro; /predict-chords?recording=true).
4. 0.4: polish, icon, screenshots, fastlane metadata (F-Droid listing), privacy statement.
5. 1.0.0: publish source on a public Git host, submit the F-Droid merge request (fdroiddata), also IzzyOnDroid and GitHub Releases APKs. Releases signed with your own key; F-Droid can build and sign itself from tagged source.
Server side (private): /mobile/handoff + /mobile/go (added in server v0.9.1).
