# Releasing the kiosk app

The kiosk updates itself from GitHub Releases of this repository (plan `kiosk-selfupdate`, source `GithubUpdateSource`).
A TV set to channel `stable` follows `releases/latest`; a TV set to `canary` follows the assets of the pre-release tagged
`canary`. Channel `off` (the default) never checks.

## Rules that must hold
- Release APKs are signed with the dedicated release key only (`keystore.properties`, never in git; invariant 8). A
  debug-signed APK must NEVER be published: a TV cannot update from one key to another, it would need an uninstall.
- `versionCode` must rise with every release; the TV installs only a higher code.
- Every `gh release ...` or `git push` is an outward action: do it only when the owner says so.
- Upload the APK BEFORE `version.properties`, so a TV never sees a manifest whose APK is missing.
- Never delete and recreate the `canary` release: replace its assets (`gh release upload canary <files> --clobber`).

## Build
```
./gradlew assembleRelease -PversionCode=<N> -PversionName=<X.Y.Z>     # app/build/outputs/apk/release/
```
Without `keystore.properties` the release APK is left UNSIGNED and cannot be installed: stop there.
`-PversionName` is 1-32 chars of `A-Za-z0-9._-`; omitted properties fall back to code 1 / name 0.1.0.

## Manifest
```
scripts/make-version-file.sh app/build/outputs/apk/release/<name>.apk [output-dir]
```
reads versionCode/versionName from the APK (aapt), adds `sha256` and `size`, and writes `version.properties` next to it.
The script uses GNU `stat` and `sha256sum` (Linux). A failed run leaves no `version.properties` behind (it is written to a
temp file and moved at the end). `MIN_VERSION_CODE=<n>` adds `minVersionCode` (the lowest installed code allowed to update directly). The APK file name
is at most 100 characters in total and has no `..`.

## Publish (each command asked for separately)
- stable: `gh release create v<X.Y.Z> <apk> version.properties --title ... --notes ...` (the APK first in the list), not
  marked as pre-release, so `releases/latest` picks it up.
- canary: the pre-release with the fixed tag `canary`: `gh release upload canary <apk> --clobber`, then
  `gh release upload canary version.properties --clobber`.

## Verify
`apksigner verify --print-certs <apk>` shows the release certificate (the same on every release); `sha256sum <apk>` equals
`sha256=` in `version.properties`.
