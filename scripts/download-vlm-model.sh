#!/bin/bash
#
# Interactive VLM (Vision-Language Model) downloader for Strix on-device inference.
#
# These are multimodal (text + image) models packaged as MediaPipe .task bundles,
# ready for on-device inference on Android via the LiteRT / MediaPipe LLM Inference
# API. The camera feed from the app is sent to the model for scene understanding
# and object localization (bounding boxes).
#
# Features:
#   - Shows available VLM models from scripts/vlm-models.json
#   - Marks already-downloaded models
#   - Handles Hugging Face auth (fine-grained token) and gated-repo license
#     acceptance, opening the browser where a human decision is required
#   - Downloads the selected model to .model-cache/
#   - Sets the active VLM for the next build (separate from the text LLM)
#
# Usage: ./scripts/download-vlm-model.sh
#

set -e

SCRIPT_DIR="$(dirname "$0")"
MODEL_REGISTRY="${SCRIPT_DIR}/vlm-models.json"
MODEL_CACHE_DIR=".model-cache"
ACTIVE_MODEL_FILE="${MODEL_CACHE_DIR}/active-vlm-model.txt"
HF_TOKEN_URL="https://huggingface.co/settings/tokens/new?tokenType=fineGrained&globalPermissions=repo.content.read"

# ── Helpers ──────────────────────────────────────────────────────────

die() { echo "✗ $1"; exit 1; }

open_url() {
    local url=$1
    if command -v open &> /dev/null; then
        open "$url"
    elif command -v xdg-open &> /dev/null; then
        xdg-open "$url"
    elif command -v cmd &> /dev/null; then
        cmd //c start "" "$url" 2>/dev/null
    else
        echo "Open manually: $url"
    fi
}

check_jq() {
    if ! command -v jq &> /dev/null; then
        echo "Installing jq..."
        if command -v brew &> /dev/null; then
            brew install jq
        elif command -v winget &> /dev/null; then
            winget install --id jqlang.jq -e
        elif command -v scoop &> /dev/null; then
            scoop install jq
        else
            die "jq is required. Install with: brew install jq (macOS/Linux) or winget install jqlang.jq (Windows)"
        fi
    fi
}

find_hf_cli() {
    if [ -f "$HOME/.local/bin/hf" ]; then
        echo "$HOME/.local/bin/hf"
    elif command -v hf &> /dev/null; then
        echo "hf"
    else
        echo "Installing Hugging Face CLI..." >&2
        if ! command -v pipx &> /dev/null; then
            if command -v brew &> /dev/null; then
                brew install pipx
            elif command -v pip &> /dev/null; then
                pip install --user pipx >&2
            elif command -v pip3 &> /dev/null; then
                pip3 install --user pipx >&2
            else
                die "pipx is required. Install from: https://pypa.github.io/pipx/installation/"
            fi
        fi
        pipx install huggingface_hub >&2
        # After install, check if hf is now on PATH (Windows puts it in Scripts/)
        if command -v hf &> /dev/null; then
            echo "hf"
        else
            echo "$HOME/.local/bin/hf"
        fi
    fi
}

get_hf_token() {
    if [ -f "$HOME/.cache/huggingface/token" ]; then
        cat "$HOME/.cache/huggingface/token"
    elif [ -f "$USERPROFILE/.cache/huggingface/token" ] 2>/dev/null; then
        cat "$USERPROFILE/.cache/huggingface/token"
    fi
}

# ── Model Registry ───────────────────────────────────────────────────

load_models() {
    if [ ! -f "$MODEL_REGISTRY" ]; then
        die "VLM model registry not found: $MODEL_REGISTRY"
    fi
}

model_count() {
    jq length "$MODEL_REGISTRY"
}

model_field() {
    local idx=$1 field=$2
    jq -r ".[$idx].$field" "$MODEL_REGISTRY"
}

get_active_model() {
    if [ -f "$ACTIVE_MODEL_FILE" ]; then
        cat "$ACTIVE_MODEL_FILE"
    else
        jq -r '.[] | select(.default == true) | .filename' "$MODEL_REGISTRY"
    fi
}

set_active_model() {
    local filename=$1
    mkdir -p "$MODEL_CACHE_DIR"
    echo "$filename" > "$ACTIVE_MODEL_FILE"
}

# ── Display ──────────────────────────────────────────────────────────

show_model_list() {
    local count
    count=$(model_count)
    local active
    active=$(get_active_model)

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Available On-Device VLM Models"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    for i in $(seq 0 $((count - 1))); do
        local name filename desc size_mb gated
        name=$(model_field "$i" "name")
        filename=$(model_field "$i" "filename")
        desc=$(model_field "$i" "description")
        size_mb=$(model_field "$i" "size_mb")
        gated=$(model_field "$i" "gated")

        local status=""
        if [ -f "${MODEL_CACHE_DIR}/${filename}" ]; then
            local actual_size
            actual_size=$(du -h "${MODEL_CACHE_DIR}/${filename}" | cut -f1)
            status="[DOWNLOADED ${actual_size}]"
        fi

        local active_marker=""
        if [ "$filename" = "$active" ]; then
            active_marker=" ◀ ACTIVE"
        fi

        local gated_marker=""
        if [ "$gated" = "true" ]; then
            gated_marker="  (gated: license acceptance required)"
        fi

        printf "  %d) %s%s\n" $((i + 1)) "$name" "$active_marker"
        printf "     %s\n" "$desc"
        printf "     download size ≈ %s MB%s\n" "$size_mb" "$gated_marker"
        if [ -n "$status" ]; then
            printf "     ✓ %s\n" "$status"
        fi
        echo ""
    done
}

# ── Auth ─────────────────────────────────────────────────────────────

prompt_for_token() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Create a New Token with Gated Repo Access"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "Your current token doesn't have permission to access gated repos."
    echo ""
    echo "Opening token creation page..."
    echo "IMPORTANT: Make sure this checkbox is selected:"
    echo "  ☑ Read access to contents of all public gated repos"
    echo ""
    sleep 1
    open_url "$HF_TOKEN_URL"
    echo ""
    printf "Paste your NEW token here: "
    read -r HF_TOKEN

    HF_TOKEN=$(echo "$HF_TOKEN" | tr -d '[:space:]')
    if [ -z "$HF_TOKEN" ]; then
        echo "No token provided."
        return 1
    fi

    echo "Logging in with new token..."
    if "$HF_CLI" auth login --token "$HF_TOKEN"; then
        echo "✓ Logged in"
        return 0
    else
        echo "✗ Login failed."
        return 1
    fi
}

prompt_for_license() {
    local repo=$1
    local url="https://huggingface.co/${repo}"
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Accept the Model License"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "This is a gated Gemma model. You must review and accept Google's"
    echo "usage license on the model page before you can download it."
    echo ""
    echo "Opening model page: $url"
    sleep 1
    open_url "$url"
    echo ""
    echo "Click 'Acknowledge license' / 'Agree and access repository' on the page."
    echo "Requests are normally approved immediately."
    printf "Press Enter after accepting the license..."
    read -r
}

# ── Download ─────────────────────────────────────────────────────────

check_access() {
    local repo=$1 filename=$2
    local url="https://huggingface.co/${repo}/resolve/main/${filename}"
    local token
    token=$(get_hf_token)
    local http_code

    if [ -n "$token" ]; then
        http_code=$(curl -s -o /dev/null -w "%{http_code}" -I -H "Authorization: Bearer $token" "$url" 2>/dev/null)
    else
        http_code=$(curl -s -o /dev/null -w "%{http_code}" -I "$url" 2>/dev/null)
    fi
    echo "$http_code"
}

confirm_large_download() {
    local size_mb=$1 name=$2
    # Warn on anything ~1GB+ so nobody is surprised by a multi-GB pull.
    if [ "$size_mb" -ge 1000 ] 2>/dev/null; then
        echo ""
        echo "⚠  ${name} is a large download (≈ ${size_mb} MB)."
        echo "   Make sure you're on a fast, unmetered connection and have disk space."
        printf "   Continue? [y/N]: "
        read -r ok
        if [[ ! "$ok" =~ ^[yY]$ ]]; then
            echo "Skipped."
            return 1
        fi
    fi
    return 0
}

download_model() {
    local idx=$1
    local filename repo name size_mb
    filename=$(model_field "$idx" "filename")
    repo=$(model_field "$idx" "repo")
    name=$(model_field "$idx" "name")
    size_mb=$(model_field "$idx" "size_mb")

    if [ -f "${MODEL_CACHE_DIR}/${filename}" ]; then
        local size
        size=$(du -h "${MODEL_CACHE_DIR}/${filename}" | cut -f1)
        echo ""
        echo "✓ ${name} already downloaded (${size})"
        printf "Re-download? [y/N]: "
        read -r choice
        if [[ ! "$choice" =~ ^[yY]$ ]]; then
            return 0
        fi
    fi

    confirm_large_download "$size_mb" "$name" || return 1

    mkdir -p "$MODEL_CACHE_DIR"

    local MAX_ATTEMPTS=3
    for attempt in $(seq 1 $MAX_ATTEMPTS); do
        echo ""
        echo "Checking access to ${name}..."
        local http_code
        http_code=$(check_access "$repo" "$filename")

        case "$http_code" in
            200|302|307)
                echo "✓ Access granted"
                echo ""
                echo "Downloading ${name}..."
                echo "This can take a while (multi-GB) depending on your connection."
                echo ""

                if "$HF_CLI" download "${repo}" "${filename}" --local-dir "${MODEL_CACHE_DIR}"; then
                    if [ -f "${MODEL_CACHE_DIR}/${filename}" ]; then
                        local size
                        size=$(du -h "${MODEL_CACHE_DIR}/${filename}" | cut -f1)
                        echo ""
                        echo "✓ Downloaded ${name} (${size})"
                        return 0
                    fi
                fi
                die "Download failed"
                ;;
            401)
                echo "✗ Not logged in"
                prompt_for_token || return 1
                ;;
            403)
                echo "✗ Access denied (403)"
                echo ""
                echo "For gated Gemma models this usually means the license hasn't been"
                echo "accepted yet, or your token lacks gated-repo read access."
                printf "Try: (l)icense acceptance or (t)oken? [l/t]: "
                read -r fix
                case "$fix" in
                    t|T) prompt_for_token || return 1 ;;
                    *)   prompt_for_license "$repo" ;;
                esac
                ;;
            *)
                die "Unexpected response: HTTP $http_code"
                ;;
        esac
    done

    die "Failed after $MAX_ATTEMPTS attempts."
}

# ── Push to device (for offline on-device inference) ─────────────────

# The app reads models from its OWN external files dir, which is the only location an
# installed app can reliably read (SELinux blocks apps from /data/local/tmp). This must
# match the first candidate in LocalModelLocator on the app side.
APP_ID="com.resourcefork.rccontrol"
DEVICE_MODEL_DIR="/sdcard/Android/data/${APP_ID}/files/models"

find_adb() {
    if command -v adb &> /dev/null; then
        echo "adb"
    elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        echo "$HOME/Library/Android/sdk/platform-tools/adb"
    else
        echo ""
    fi
}

push_to_device() {
    local active
    active=$(get_active_model)
    if [ -z "$active" ] || [ ! -f "${MODEL_CACHE_DIR}/${active}" ]; then
        echo "✗ No downloaded active model to push. Download or activate one first."
        return 1
    fi
    local ADB
    ADB=$(find_adb)
    if [ -z "$ADB" ]; then
        echo "✗ adb not found on PATH or in ~/Library/Android/sdk/platform-tools."
        echo "  Install platform-tools: brew install --cask android-platform-tools"
        return 1
    fi

    local n
    n=$("$ADB" devices | grep -cw "device")
    if [ "$n" = "0" ]; then
        echo "✗ No device detected. Connect a phone with USB debugging enabled and authorize it."
        return 1
    fi

    echo ""
    echo "Pushing ${active} to the app's storage on the device..."
    echo "  ${DEVICE_MODEL_DIR}/"
    echo "(Multi-GB copy over USB — this can take several minutes.)"
    echo ""
    echo "Note: the Strix app must be installed first (its data dir has to exist)."
    "$ADB" shell "mkdir -p ${DEVICE_MODEL_DIR}/"
    if "$ADB" push "${MODEL_CACHE_DIR}/${active}" "${DEVICE_MODEL_DIR}/${active}"; then
        echo "✓ Pushed. Open the app (or tap 'Re-scan') and it will detect the model."
    else
        echo "✗ adb push failed."
        echo "  If the app isn't installed yet, run ./gradlew installDebug first, then retry."
        return 1
    fi
}

# ── Main ─────────────────────────────────────────────────────────────

main() {
    echo ""
    echo "=== Strix On-Device VLM Model Manager ==="

    check_jq
    load_models
    HF_CLI=$(find_hf_cli)

    local count
    count=$(model_count)

    while true; do
        show_model_list

        echo "  Actions:"
        echo "    #) Download & activate a model (enter its number)"
        echo "    a) Activate an already-downloaded model (without downloading)"
        echo "    p) Push the active model to a connected device (adb, for offline use)"
        echo "    q) Quit"
        echo ""
        printf "  Choice: "
        read -r choice

        case "$choice" in
            q|Q)
                echo ""
                echo "Active VLM: $(get_active_model)"
                echo "To run it offline on a device: choose 'p' to adb push it, then"
                echo "build/install the app (./gradlew installDebug) and toggle on-device mode."
                echo ""
                exit 0
                ;;
            a|A)
                echo ""
                echo "Enter the number of the model to activate:"
                printf "  #: "
                read -r num
                if [[ "$num" =~ ^[0-9]+$ ]] && [ "$num" -ge 1 ] && [ "$num" -le "$count" ]; then
                    local idx=$((num - 1))
                    local fn
                    fn=$(model_field "$idx" "filename")
                    if [ -f "${MODEL_CACHE_DIR}/${fn}" ]; then
                        set_active_model "$fn"
                        echo "✓ Activated: $(model_field "$idx" "name")"
                    else
                        echo "✗ Model not downloaded yet. Download it first."
                    fi
                else
                    echo "Invalid selection."
                fi
                ;;
            p|P)
                push_to_device || true
                ;;
            [0-9]*)
                if [ "$choice" -ge 1 ] && [ "$choice" -le "$count" ]; then
                    local idx=$((choice - 1))
                    if download_model "$idx"; then
                        local fn
                        fn=$(model_field "$idx" "filename")
                        set_active_model "$fn"
                        echo "✓ Activated: $(model_field "$idx" "name")"
                    fi
                else
                    echo "Invalid selection. Pick 1-${count}."
                fi
                ;;
            *)
                echo "Invalid choice."
                ;;
        esac
    done
}

main
