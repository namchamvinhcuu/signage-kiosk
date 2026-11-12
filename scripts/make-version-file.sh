#!/usr/bin/env bash
# Writes version.properties next to an APK, in the format the kiosk's self-update reads (UpdateManifest.parse):
#   versionCode, versionName, apk (bare file name), sha256 (lowercase hex), size, [minVersionCode]
# versionCode / versionName are read from the APK itself (aapt), so the file cannot disagree with the archive.
#
# Usage: scripts/make-version-file.sh <path/to/app.apk> [output-dir]      (default output-dir: the APK's directory)
# Optional: MIN_VERSION_CODE=<n> to set minVersionCode; AAPT=<path to aapt> (default: newest in $ANDROID_HOME/build-tools).
set -euo pipefail

apk_path="${1:?usage: $0 <apk> [output-dir]}"
out_dir="${2:-$(dirname "$apk_path")}"
[ -f "$apk_path" ] || { echo "no such APK: $apk_path" >&2; exit 1; }

apk_name="$(basename "$apk_path")"
# Same rule as UpdateManifest: a bare .apk name of at most 100 characters in total, no "..".
if ! [[ "$apk_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,95}\.apk$ ]] || [[ "$apk_name" == *..* ]]; then
  echo "APK file name not allowed in a manifest: $apk_name" >&2
  exit 1
fi

aapt="${AAPT:-}"
if [ -z "$aapt" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  aapt="$(ls -d "$sdk"/build-tools/*/aapt 2>/dev/null | sort -V | tail -n 1 || true)"
fi
[ -x "$aapt" ] || { echo "aapt not found (set AAPT=...)" >&2; exit 1; }

badging="$("$aapt" dump badging "$apk_path")"
version_code="$(sed -n "s/.*package: name='[^']*' versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -n 1)"
version_name="$(sed -n "s/.*package: name='[^']*' versionCode='[0-9]*' versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
[ -n "$version_code" ] && [ -n "$version_name" ] || { echo "could not read versionCode/versionName from the APK" >&2; exit 1; }

size="$(stat -c %s "$apk_path")"
max=$((20 * 1024 * 1024))
[ "$size" -gt 0 ] && [ "$size" -le "$max" ] || { echo "APK size $size outside 1..$max bytes" >&2; exit 1; }
sha256="$(sha256sum "$apk_path" | cut -d' ' -f1)"

# Everything is validated BEFORE the file is touched: a failed run must not leave a half-written manifest behind.
if [ -n "${MIN_VERSION_CODE:-}" ] && ! [[ "$MIN_VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
  echo "MIN_VERSION_CODE must be a positive integer" >&2
  exit 1
fi
[[ "$version_name" =~ ^[A-Za-z0-9._-]{1,32}$ ]] || { echo "versionName not allowed in a manifest: $version_name" >&2; exit 1; }

out="$out_dir/version.properties"
tmp="$out.tmp"
mkdir -p "$out_dir"
{
  echo "versionCode=$version_code"
  echo "versionName=$version_name"
  echo "apk=$apk_name"
  echo "sha256=$sha256"
  echo "size=$size"
  if [ -n "${MIN_VERSION_CODE:-}" ]; then
    echo "minVersionCode=$MIN_VERSION_CODE"
  fi
} >"$tmp"
mv "$tmp" "$out"
echo "wrote $out (versionCode $version_code, versionName $version_name, $size bytes)"
