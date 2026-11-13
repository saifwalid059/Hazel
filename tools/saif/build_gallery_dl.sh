#!/usr/bin/env bash
# Hazel (Saif): packs gallery-dl and the pure-Python libraries it needs into one zipapp,
# app/src/main/assets/gallery-dl.pyz, which the app runs with the Python that
# youtubedl-android already ships (3.11, which has no `requests` of its own).
#
# Everything is pinned and taken as pure-Python wheels only, so the result does not depend
# on the machine that builds it. Run from the repository root:  bash tools/saif/build_gallery_dl.sh
set -euo pipefail

OUT="${1:-app/src/main/assets/gallery-dl.pyz}"
PY="${PYTHON:-python3}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"$PY" -m pip install --quiet --disable-pip-version-check --no-compile \
  --target "$WORK/app" \
  --platform any --implementation py --python-version 3.11 --only-binary=:all: \
  gallery-dl==1.32.19 \
  requests==2.34.2 \
  urllib3==2.8.0 \
  idna==3.20 \
  certifi==2026.10.23 \
  charset-normalizer==3.5.2 \
  PySocks==1.7.1

# Console scripts and package metadata are not needed inside the archive.
rm -rf "$WORK/app/bin" "$WORK/app"/*.dist-info "$WORK/app"/__pycache__

# gallery_dl.main() returns the exit status; zipapp's generated entry point would drop it.
cat > "$WORK/app/__main__.py" <<'PY'
import sys
import gallery_dl
sys.exit(gallery_dl.main())
PY

mkdir -p "$(dirname "$OUT")"
"$PY" -m zipapp "$WORK/app" -o "$OUT" -c
ls -l "$OUT"
