# OLEDTV-MUSIC

Minimal D-pad-native Navidrome player for Android TV / Fire TV (built for the 1st-gen Fire TV Stick 4K, API 25).

- Album Artists -> albums by release year -> tracklist -> split-screen player with synced lyrics
- OLED friendly: pure black UI, pixel-shifting artwork
- Built entirely in GitHub Actions; every push to `main` publishes an APK to Actions artifacts and Releases

## Updates that keep your login
Add a persistent signing key as repo secrets so each build installs as an update:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
`versionCode` is the GitHub run number (+ optional `VERSION_CODE_OFFSET` repo variable).
