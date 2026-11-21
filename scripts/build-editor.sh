#!/usr/bin/env bash
# Builds the offline ONLYOFFICE editor bundle (ranuts/document, AGPL-3.0)
# and places it in android/app/src/main/assets/www.
# Needs: git, Node.js >= 24, pnpm 11.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EDITOR_REPO="${EDITOR_REPO:-https://github.com/ranuts/document}"
EDITOR_REF="${EDITOR_REF:-$(cat "$ROOT/scripts/EDITOR_REF")}"
WORK="$ROOT/build/editor-src"
OUT="$ROOT/android/app/src/main/assets/www"

echo "Editor source: $EDITOR_REPO @ $EDITOR_REF"
rm -rf "$WORK"
mkdir -p "$WORK"
git -C "$WORK" init -q
git -C "$WORK" remote add origin "$EDITOR_REPO"
git -C "$WORK" fetch -q --depth 1 origin "$EDITOR_REF"
git -C "$WORK" checkout -q FETCH_HEAD

(
  cd "$WORK"
  pnpm install --frozen-lockfile
  pnpm run build
)

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
cp -a "$WORK/dist" "$OUT"

# The x2t converter ships brotli-compressed and relies on the web server's
# Content-Encoding header. The app serves files itself, so store it raw.
node -e '
const fs = require("fs"), zlib = require("zlib");
const p = process.argv[1];
const raw = zlib.brotliDecompressSync(fs.readFileSync(p));
if (raw.readUInt32BE(0) !== 0x0061736d) throw new Error("x2t is not a wasm module");
fs.writeFileSync(p, raw);
console.log("x2t wasm decompressed:", raw.length, "bytes");
' "$OUT/sdkjs/common/wasm/x2t/x2t.wasm.br"

# Not needed inside the app.
find "$OUT" -name '*.map' -delete
rm -f "$OUT/_headers" "$OUT/_redirects" "$OUT/sw.js"

echo "Bundle size:"
du -sh "$OUT"
du -sh "$OUT"/* | sort -h | tail -12
