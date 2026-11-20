#!/usr/bin/env bash
#
# Put the Rust core where the project expects it (core/, gitignored):
#
#   core/jniLibs/<abi>/libveltrio_core.so
#   core/kotlin/uniffi/veltrio/veltrio.kt
#
# Downloads `veltrio-core-android.zip` from the veltrio-core release named in
# core.version (needs `gh` signed in with access to VeltrioApp/veltrio-core,
# or GH_TOKEN in CI). VELTRIO_CORE_DIR=../veltrio-core builds it from a
# local checkout instead.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/core"
VERSION="$(tr -d '[:space:]' < "$ROOT/core.version")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -n "${VELTRIO_CORE_DIR:-}" ]; then
  CORE="$(cd "$VELTRIO_CORE_DIR" && pwd)"
  echo "==> Building the core from $CORE"
  "$CORE/scripts/package-android.sh"
  cp "$CORE/dist/veltrio-core-android.zip" "$TMP/veltrio-core-android.zip"
else
  echo "==> veltrio-core $VERSION"
  gh release download "$VERSION" --repo VeltrioApp/veltrio-core --pattern veltrio-core-android.zip --dir "$TMP"
fi

rm -rf "$OUT" && mkdir -p "$OUT"
unzip -q "$TMP/veltrio-core-android.zip" -d "$OUT"

# UniFFI 0.29 declares an error's `message` field next to an override of
# Exception.message: two properties of one name, which Kotlin 2.4 rejects.
# Keep one: the field, overriding Exception.message.
python3 - "$OUT/kotlin/uniffi/veltrio/veltrio.kt" <<'PY'
import re, sys
path = sys.argv[1]
src = open(path, encoding="utf-8").read()
src, n = re.subn(
    r"val `message`: kotlin\.String\n(\s*)\) : (\w+)\(\) \{\n\s*override val message\n\s*get\(\) = \"message=\$\{ `message` \}\"\n",
    r"override val `message`: kotlin.String\n\1) : \2() {\n", src)
open(path, "w", encoding="utf-8").write(src)
print(f"==> patched {n} error message properties")
PY
echo "==> core/ ready"
