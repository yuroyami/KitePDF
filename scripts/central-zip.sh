#!/usr/bin/env bash
# Builds the Maven Central zip of each module with the Kotlin toolchain and merges the zips into one.
# Uploaded as one deployment, the merged zip makes Central check the whole release first, then publish
# all of it or nothing. The toolchain alone uploads one deployment for each module.
#
# Usage: scripts/central-zip.sh <output zip> <module>...
# Signing reads KOTLIN_TOOLCHAIN_SIGNING_KEY and KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE.
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 <output zip> <module>..." >&2
  exit 2
fi
out=$1
shift
case $out in
  /*) ;;
  *) out=$PWD/$out ;;
esac
cd "$(dirname "$0")/.."

tasks=()
zips=()
for module in "$@"; do
  tasks+=(":$module:prepareMavenCentralBundle")
  zips+=("build/tasks/_${module}_prepareMavenCentralBundle/$module-central-bundle.zip")
done
./kotlin task "${tasks[@]}"

for zip in "${zips[@]}"; do
  if [ ! -f "$zip" ]; then
    echo "error: the toolchain wrote no $zip. Check the name and the output folder of its prepareMavenCentralBundle task." >&2
    exit 1
  fi
done

# Each artifact has its own folder, so two zips never hold the same file. Stop if they do.
dupes=$(for zip in "${zips[@]}"; do zipinfo -1 "$zip"; done | sed '/\/$/d' | sort | uniq -d)
if [ -n "$dupes" ]; then
  echo "error: these files are in more than one zip:" >&2
  echo "$dupes" >&2
  exit 1
fi

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
for zip in "${zips[@]}"; do
  unzip -q -n "$zip" -d "$stage"
done
mkdir -p "$(dirname "$out")"
rm -f "$out"
(cd "$stage" && zip -q -r -X -D "$out" .)

files=$(zipinfo -1 "$out" | wc -l | tr -d ' ')
echo "$out: $files files, $(du -h "$out" | cut -f1 | tr -d ' '), in these folders:"
zipinfo -1 "$out" | sed 's|/[^/]*$||' | sort -u
