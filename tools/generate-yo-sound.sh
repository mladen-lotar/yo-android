#!/bin/bash
# Regenerate app/src/main/res/raw/yo.mp3 — the notification clip for a received Yo.
#
# The original Yo app played a short recorded voice saying "Yo". That clip is not ours to
# reuse, so this synthesizes an equivalent one with macOS speech synthesis. Keeping the
# generator in the repo means the asset is reproducible and auditable rather than an
# opaque binary someone has to trust.
#
# Requires: macOS (`say`, ships with the OS) and ffmpeg with libmp3lame.
# Usage: tools/generate-yo-sound.sh [voice] [rate] [text]
set -euo pipefail

VOICE="${1:-Fred}"
RATE="${2:-170}"
TEXT="${3:-Yo!}"

cd "$(dirname "$0")/.."
OUT="app/src/main/res/raw/yo.mp3"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v say >/dev/null || { echo "needs macOS 'say'" >&2; exit 1; }
ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libmp3lame || {
  echo "needs ffmpeg with libmp3lame" >&2; exit 1; }

say -v "$VOICE" -r "$RATE" -o "$TMP/raw.aiff" "$TEXT"
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$TMP/raw.aiff")
FADE=$(python3 -c "print(max(0, $DUR - 0.04))")

# Trim leading/trailing silence (synthesizers pad both ends), normalize to a
# consistent loudness so the clip is audible without being harsh, taper the tail
# to avoid a click, and encode mono mp3 — decodable on every Android version we support.
ffmpeg -y -loglevel error -i "$TMP/raw.aiff" \
  -af "silenceremove=start_periods=1:start_silence=0:start_threshold=-45dB,\
areverse,silenceremove=start_periods=1:start_silence=0:start_threshold=-45dB,areverse,\
loudnorm=I=-14:TP=-1.5:LRA=7,afade=t=out:st=${FADE}:d=0.04" \
  -ac 1 -ar 44100 -c:a libmp3lame -b:a 96k "$OUT"

echo "wrote $OUT ($(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT")s, $(wc -c <"$OUT" | tr -d ' ') bytes) voice=$VOICE rate=$RATE text='$TEXT'"
