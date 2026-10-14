#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$here/build
mkdir -p "$work/fonts" "$here/figures"

find_font() {
  local name=$1 found=""
  if command -v kpsewhich > /dev/null; then
    found=$(kpsewhich "$name" 2> /dev/null || true)
  fi
  if [ -z "$found" ]; then
    for root in "$HOME/Library/Caches/TectonicProject.Tectonic" "$HOME/.cache/Tectonic"; do
      [ -d "$root" ] && found=$(find "$root" -name "$name" -print -quit 2> /dev/null)
      [ -n "$found" ] && break
    done
  fi
  [ -n "$found" ] || { echo "font $name not found; run tectonic on the paper once so it downloads the fonts" >&2; exit 1; }
  cp "$found" "$work/fonts/"
}

if [ ! -s "$work/fonts/LinBiolinum_R.otf" ]; then
  tectonic -X compile --outdir "$work" "$here/hstore.tex" > /dev/null 2>&1 || true
fi
for font in LinBiolinum_R.otf LinBiolinum_RB.otf LinBiolinum_RI.otf LinLibertine_R.otf LinLibertine_RI.otf Inconsolatazi4-Regular.otf; do
  find_font "$font"
done

cat > "$work/fonts.conf" << EOF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>$work/fonts</dir>
  <cachedir>$work/fontcache</cachedir>
</fontconfig>
EOF

export FONTCONFIG_FILE=$work/fonts.conf
export PAPER_R=$here/R
export PAPER_FIGURES=$here/figures
export PAPER_RESULTS=$here/results
export PAPER_ABLATION=$here/ablation
export PAPER_GENERATED=$here/generated
export PAPER_README_IMAGES=$here/../docs/images

cd "$here"
mkdir -p generated "$PAPER_README_IMAGES"
for script in diagrams results; do
  [ -f "R/$script.R" ] && Rscript "R/$script.R"
done

if [ "${1:-}" != "figures" ]; then
  tectonic -X compile --keep-logs --outdir "$work" hstore.tex
  cp "$work/hstore.pdf" "$here/../docs/hstore-paper.pdf"
fi
