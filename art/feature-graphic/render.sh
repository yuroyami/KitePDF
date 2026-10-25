#!/usr/bin/env bash
# Renders index.html to a PNG at 3840 x 2160 with headless Chrome.
#
# Usage:
#   ./render.sh                # dark variant  -> art/kitepdf-feature-graphic.png
#   ./render.sh light          # light variant -> art/kitepdf-feature-graphic-light.png
#   ./render.sh dark out.png   # any variant, to a path of your choice
#
# Set CHROME to another Chromium binary if Google Chrome is not installed.
# Headless Chrome does not always exit after it writes the screenshot, so the
# script waits for the file and then stops Chrome itself.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
theme="${1:-dark}"
case "$theme" in
  dark)  default_out="$here/../kitepdf-feature-graphic.png" ;;
  light) default_out="$here/../kitepdf-feature-graphic-light.png" ;;
  *) echo "unknown theme: $theme (use dark or light)" >&2; exit 2 ;;
esac
out="${2:-$default_out}"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

if [ ! -x "$chrome" ]; then
  echo "Chrome not found at: $chrome (set CHROME=/path/to/chrome)" >&2
  exit 1
fi

profile="$(mktemp -d)"
tmp_out="$profile/shot.png"
cleanup() {
  if [ -n "${pid:-}" ]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf "$profile" 2>/dev/null || true
}
trap cleanup EXIT

"$chrome" \
  --headless=new \
  --disable-gpu \
  --hide-scrollbars \
  --no-first-run \
  --no-default-browser-check \
  --disable-background-networking \
  --disable-component-update \
  --user-data-dir="$profile" \
  --window-size=1920,1080 \
  --force-device-scale-factor=2 \
  --virtual-time-budget=3000 \
  --screenshot="$tmp_out" \
  "file://$here/index.html?theme=$theme" >/dev/null 2>&1 &
pid=$!

# Wait up to 60 seconds for the screenshot, then one more second so the write finishes.
for _ in $(seq 1 120); do
  if [ -s "$tmp_out" ]; then
    sleep 1
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

if [ ! -s "$tmp_out" ]; then
  echo "Chrome wrote no screenshot" >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
mv "$tmp_out" "$out"
echo "wrote $out"
