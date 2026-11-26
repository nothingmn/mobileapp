#!/bin/sh
# Rebuild the Notebook's capture chime from the operator's own recording.
#
# The shipped sound is the operator's, not a generated tone. He supplied a short MP3 (two
# identical notes on a pair of high partials); this script turns that file into the resource
# the app ships, with no code change: mono, 44.1 kHz, 16-bit PCM WAV, peak normalised to
# −12 dBFS so it sits under speech and music instead of over them. Nothing about the shape is
# altered — both notes and the gap between them are as he recorded them, and no silence is
# trimmed from either end.
#
# The source file itself is the operator's own and is not committed: it lives outside the
# repository (it arrived as a chat attachment), so a reader cannot regenerate the bytes from
# this tree alone. What is committed is the resource and this recipe, which records exactly
# what was done to the source. If the source is ever lost, the resource is still the truth.
#
#   scripts/remake-capture-chime.sh /path/to/chin.mp3
#   scripts/remake-capture-chime.sh /path/to/chin.mp3 /tmp/listen.wav
#
# Needs ffmpeg and ffprobe. Two passes: the first measures the peak of the decode, the second
# applies the difference to the target. Measured, not assumed — the level printed at the end
# is read back off the file that was just written.
set -eu

TARGET_DBFS=-12.0
SRC=${1:?usage: remake-capture-chime.sh <source.mp3> [out.wav]}
OUT=${2:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/notebook/src/androidMain/res/raw/capture_chime.wav}

[ -r "$SRC" ] || { echo "cannot read $SRC" >&2; exit 1; }
command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null || { echo "ffmpeg and ffprobe are required" >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

echo "source  $SRC"
ffprobe -v error -select_streams a:0 \
    -show_entries stream=codec_name,sample_rate,channels -show_entries format=duration,bit_rate \
    -of default=noprint_wrappers=1 "$SRC" | sed 's/^/        /'

# 1. Decode to the shipping format: first audio stream, mono, 44.1 kHz, 16-bit PCM.
ffmpeg -v error -y -i "$SRC" -map 0:a:0 -vn -ac 1 -ar 44100 -c:a pcm_s16le "$TMP/decoded.wav"

# 2. Measure that decode's peak, and the gain that puts it at the target.
PEAK_DB=$(ffmpeg -hide_banner -nostats -i "$TMP/decoded.wav" -af volumedetect -f null - 2>&1 |
    sed -n 's/.*max_volume: \(-\{0,1\}[0-9.]*\) dB.*/\1/p')
[ -n "$PEAK_DB" ] || { echo "could not measure the peak of $SRC" >&2; exit 1; }
GAIN_DB=$(awk -v t="$TARGET_DBFS" -v p="$PEAK_DB" 'BEGIN { printf "%.2f", t - p }')
echo "decoded mono 44.1 kHz: peak ${PEAK_DB} dBFS, applying ${GAIN_DB} dB to reach ${TARGET_DBFS} dBFS"

# 3. Apply the gain to the measured decode and write the resource. Byte-exact: no LAVF chunk,
#    no metadata, so two runs from the same source produce the same file.
ffmpeg -v error -y -i "$TMP/decoded.wav" -af "volume=${GAIN_DB}dB" \
    -c:a pcm_s16le -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$OUT"

# 4. Read the result back; the numbers below are the file, not the intention.
echo "wrote   $OUT"
ffprobe -v error -show_entries stream=codec_name,sample_rate,channels,bits_per_sample \
    -show_entries format=duration,size -of default=noprint_wrappers=1 "$OUT" | sed 's/^/        /'
ffmpeg -hide_banner -nostats -i "$OUT" -af volumedetect -f null - 2>&1 |
    sed -n 's/.*\(mean_volume\|max_volume\): \(.*\)/        \1: \2/p'
