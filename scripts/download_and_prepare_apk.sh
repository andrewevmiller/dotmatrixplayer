#!/usr/bin/env bash
set -euo pipefail

# Usage:
#  ./scripts/download_and_prepare_apk.sh            # download artifact, list APKs, print adb install commands
#  ./scripts/download_and_prepare_apk.sh --install # also run adb install (requires adb and a paired device)
#  ./scripts/download_and_prepare_apk.sh --install --device <device-id>

WORKFLOW="android-build.yml"
BRANCH="fenilmodh-build-apk"
ARTIFACT_NAME="android-apks"
OUT_DIR="./artifacts"

usage() {
  cat <<EOF
Usage: $0 [--install] [--device <id>]
  --install      Run adb install for each found APK
  --device <id>  Use adb -s <id> when installing
EOF
  exit 1
}

DO_INSTALL=0
DEVICE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --install) DO_INSTALL=1; shift;;
    --device) DEVICE="$2"; shift 2;;
    -h|--help) usage;;
    *) echo "Unknown arg: $1"; usage;;
  esac
done

command -v gh >/dev/null 2>&1 || { echo "gh CLI not found. Install GitHub CLI and authenticate (gh auth login)."; exit 2; }

mkdir -p "$OUT_DIR"

# Prefer a successful run; fall back to latest run for branch if none succeeded
RUN_ID=$(gh run list --workflow="$WORKFLOW" --branch="$BRANCH" --limit 10 --json id,conclusion --jq '.[] | select(.conclusion=="success") | .id' 2>/dev/null | head -n1 || true)
if [[ -z "$RUN_ID" ]]; then
  echo "No successful run found for workflow $WORKFLOW on branch $BRANCH; using latest run id (if any)"
  RUN_ID=$(gh run list --workflow="$WORKFLOW" --branch="$BRANCH" --limit 1 --json id --jq '.[0].id' 2>/dev/null || true)
fi

if [[ -z "$RUN_ID" || "$RUN_ID" == "null" ]]; then
  echo "No workflow run found for $WORKFLOW on branch $BRANCH. Ensure the branch has been pushed and the workflow ran." >&2
  exit 3
fi

echo "Downloading artifact '$ARTIFACT_NAME' from run id: $RUN_ID"
if ! gh run download "$RUN_ID" --name "$ARTIFACT_NAME" --dir "$OUT_DIR"; then
  echo "Failed to download artifact via gh. Check that artifact name and run id are correct." >&2
  exit 4
fi

# If a zip was downloaded, unzip it
ZIP_PATH="$OUT_DIR/${ARTIFACT_NAME}.zip"
if [[ -f "$ZIP_PATH" ]]; then
  echo "Unzipping $ZIP_PATH -> $OUT_DIR"
  unzip -o "$ZIP_PATH" -d "$OUT_DIR" >/dev/null
fi

# Find APKs
mapfile -t APKS < <(find "$OUT_DIR" -type f -name "*.apk" | sort)
if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "No APK files found in $OUT_DIR" >&2
  exit 5
fi

echo "Found ${#APKS[@]} APK(s):"
for i in "${!APKS[@]}"; do
  idx=$((i+1))
  echo " $idx) ${APKS[$i]}"
done

echo
if [[ $DO_INSTALL -eq 0 ]]; then
  echo "ADB install commands (copy-paste to run):"
  for p in "${APKS[@]}"; do
    if [[ -z "$DEVICE" ]]; then
      echo "adb install -r \"$p\""
    else
      echo "adb -s \"$DEVICE\" install -r \"$p\""
    fi
  done
  exit 0
fi

# If here, DO_INSTALL=1
command -v adb >/dev/null 2>&1 || { echo "adb not found. Install Android platform-tools and ensure adb is in PATH."; exit 6; }

if [[ -n "$DEVICE" ]]; then
  echo "Installing APK(s) to device $DEVICE"
  for p in "${APKS[@]}"; do
    echo "adb -s $DEVICE install -r \"$p\""
    adb -s "$DEVICE" install -r "$p" || { echo "Install failed for $p" >&2; exit 7; }
  done
else
  # If multiple devices connected, prompt
  DEVICES_RAW=$(adb devices | sed -n '2,$p' | awk '{print $1" " $2}')
  connected_count=$(adb devices | sed -n '2,$p' | awk 'NF==2 && $2=="device"' | wc -l)
  if [[ $connected_count -gt 1 ]]; then
    echo "Multiple devices connected. Use --device <id> to specify which one." >&2
    adb devices
    exit 8
  fi
  echo "Installing APK(s) to default device"
  for p in "${APKS[@]}"; do
    echo "adb install -r \"$p\""
    adb install -r "$p" || { echo "Install failed for $p" >&2; exit 9; }
  done
fi

echo "Done."
