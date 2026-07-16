#!/bin/bash
#
# Downloads the monocular depth model that powers Strix's geometry reflex layer
# (see docs/scene-understanding-plan.md, Level 2) and optionally pushes it to a
# connected device.
#
# Model: MiDaS v2.1 small (official TFLite artifact from the isl-org/MiDaS
# GitHub release). ~63 MB, 256x256 input, inverse relative depth output.
# No auth or license acceptance required.
#
# The app picks up any *.tflite in its models dir automatically (freshest wins)
# and enables the reflex layer: corridor proximity in the UI, ground-truth
# geometry in the pilot prompt, and a forward-motion veto when the center
# corridor is blocked.
#
# Usage: ./scripts/download-depth-model.sh
#

set -e

MODEL_URL="https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite"
MODEL_FILENAME="midas-v2_1-small.tflite"
MODEL_CACHE_DIR=".model-cache"

# Must match LocalModelLocator / DepthModelLocator on the app side.
APP_ID="com.resourcefork.rccontrol"
DEVICE_MODEL_DIR="/sdcard/Android/data/${APP_ID}/files/models"

die() { echo "✗ $1"; exit 1; }

find_adb() {
    if command -v adb &> /dev/null; then
        echo "adb"
    elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        echo "$HOME/Library/Android/sdk/platform-tools/adb"
    else
        echo ""
    fi
}

download() {
    mkdir -p "$MODEL_CACHE_DIR"
    local dest="${MODEL_CACHE_DIR}/${MODEL_FILENAME}"

    if [ -f "$dest" ]; then
        echo "✓ Already downloaded: ${dest} ($(du -h "$dest" | cut -f1))"
        return 0
    fi

    echo "Downloading MiDaS v2.1 small (~63 MB)..."
    if curl -L --fail -o "$dest" "$MODEL_URL"; then
        echo "✓ Downloaded to ${dest} ($(du -h "$dest" | cut -f1))"
    else
        rm -f "$dest"
        die "Download failed: $MODEL_URL"
    fi
}

push_to_device() {
    local src="${MODEL_CACHE_DIR}/${MODEL_FILENAME}"
    local ADB
    ADB=$(find_adb)
    if [ -z "$ADB" ]; then
        echo "adb not found – skipping device push."
        echo "Install platform-tools (brew install --cask android-platform-tools), then push manually:"
        echo "  adb push ${src} ${DEVICE_MODEL_DIR}/${MODEL_FILENAME}"
        return 0
    fi

    local n
    n=$("$ADB" devices | grep -cw "device")
    if [ "$n" = "0" ]; then
        echo "No device detected – skipping push. When one is connected, run:"
        echo "  adb push ${src} ${DEVICE_MODEL_DIR}/${MODEL_FILENAME}"
        return 0
    fi

    printf "Push to connected device now? [Y/n]: "
    read -r ok
    if [[ "$ok" =~ ^[nN]$ ]]; then
        echo "Skipped. Push later with:"
        echo "  adb push ${src} ${DEVICE_MODEL_DIR}/${MODEL_FILENAME}"
        return 0
    fi

    echo "Pushing to ${DEVICE_MODEL_DIR}/ ..."
    echo "Note: the Strix app must be installed first (its data dir has to exist)."
    "$ADB" shell "mkdir -p ${DEVICE_MODEL_DIR}/"
    if "$ADB" push "$src" "${DEVICE_MODEL_DIR}/${MODEL_FILENAME}"; then
        echo "✓ Pushed. Open the app (or tap 'Re-scan') – the depth readout appears"
        echo "  under the camera preview once frames are flowing."
    else
        die "adb push failed. If the app isn't installed yet, run ./gradlew installDebug first."
    fi
}

echo ""
echo "=== Strix Depth Model (geometry reflex layer) ==="
echo ""
download
push_to_device
