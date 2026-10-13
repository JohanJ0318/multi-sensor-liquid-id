#!/usr/bin/env bash
# Retrain from the banked CSVs and confirm the generated header matches the one
# the firmware is built with. Fails if the committed model is out of sync with
# the data and training code in this repo.
set -euo pipefail

cd "$(dirname "$0")"

python train_model.py \
  coke=data/coke_room.csv \
  distilled=data/distilledwater_room.csv \
  glycerin=data/glycerin_room.csv \
  isopropyl=data/isopropyl_room.csv \
  salt_water=data/saltwater_room.csv \
  tap_water=data/tapwater_room.csv

# Compare everything except comment and timestamp lines.
strip() { grep -v -e '^ \*' -e '^//' -e 'generated' "$1"; }

if diff <(strip model_params.h) <(strip ../firmware/Core/Inc/model_params.h); then
  echo "OK: firmware/Core/Inc/model_params.h matches a fresh retrain."
else
  echo "MISMATCH: retrain and copy ml/model_params.h into firmware/Core/Inc/ before flashing." >&2
  exit 1
fi
