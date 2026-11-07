This folder holds the ONE signing key of the app (trimmer.p12).

Why it is in the project: every build (GitHub Actions or local) is signed with this same key,
so a new APK can be installed OVER the old one without uninstalling.

Rules:
 * Do not delete or replace trimmer.p12. If the key changes, Android refuses to update the app.
 * Keep a backup copy of this file.
 * Always increase versionCode in app/build.gradle.kts for each update (the project does this for you).
 * Passwords are in app/build.gradle.kts (store and key password: trimmer-2026, alias: trimmer).
This key is meant for a personal / test app that is installed outside Google Play.
