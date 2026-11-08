# OLEDTV-MUSIC

Minimal D-pad-native Navidrome player for Android TV / Fire TV (built for the 1st-gen Fire TV Stick 4K, API 25).

- Album Artists -> albums by release year -> tracklist -> split-screen player with synced lyrics
- OLED friendly: pure black UI, pixel-shifting artwork
- Built entirely in GitHub Actions; every push to `main` publishes an APK to Actions artifacts and Releases

## Updates that keep your login
Builds are signed with the persistent key in `signing/release.keystore` (committed on purpose; this is a private-use app), so every build installs as an update and keeps your login.
`versionCode` is the GitHub run number (+ optional `VERSION_CODE_OFFSET` repo variable).
