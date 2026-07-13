#!/bin/bash
#
# Interactive model downloader for edge LLM inference.
#
# Features:
#   - Shows available models from scripts/models.json
#   - Marks already-downloaded models
#   - Downloads selected model to .model-cache/
#   - Sets the active model for the next build
#
# Usage: ./scripts/download-gemma-model.sh
#

set -e

SCRIPT_DIR="$(dirname "$0")"
MODEL_REGISTRY="${SCRIPT_DIR}/models.json"
MODEL_CACHE_DIR=".model-cache"
ACTIVE_MODEL_FILE="${MODEL_CACHE_DIR}/active-model.txt"
HF_TOKEN_URL="https://huggingface.co/settings/tokens/new?tokenType=fineGrained&globalPermissions=repo.content.read"

# ── Helpers ──────────────────────────────────────────────────────────

die() { echo "✗ $1"; exit 1; }

check_jq() {
    if ! command -v jq &> /dev/null; then
        echo "Installing jq..."
        if command -v brew &> /dev/null; then
            brew install jq
        else
            die "jq is required. Install with: brew install jq"
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
            else
                die "Homebrew not found. Install from https://brew.sh"
            fi
        fi
        pipx install huggingface_hub >&2
        echo "$HOME/.local/bin/hf"
    fi
}

get_hf_token() {
    if [ -f "$HOME/.cache/huggingface/token" ]; then
        cat "$HOME/.cache/huggingface/token"
    fi
}

# ── Model Registry ───────────────────────────────────────────────────

load_models() {
    if [ ! -f "$MODEL_REGISTRY" ]; then
        die "Model registry not found: $MODEL_REGISTRY"
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
    echo "  Available Edge LLM Models"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    for i in $(seq 0 $((count - 1))); do
        local name filename desc size_mb
        name=$(model_field "$i" "name")
        filename=$(model_field "$i" "filename")
        desc=$(model_field "$i" "description")
        size_mb=$(model_field "$i" "size_mb")

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

        printf "  %d) %s%s\n" $((i + 1)) "$name" "$active_marker"
        printf "     %s\n" "$desc"
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
    open "$HF_TOKEN_URL"
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
    echo "Opening model page: $url"
    sleep 1
    open "$url"
    echo ""
    echo "Click 'Agree and access repository' on the page."
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

download_model() {
    local idx=$1
    local filename repo name
    filename=$(model_field "$idx" "filename")
    repo=$(model_field "$idx" "repo")
    name=$(model_field "$idx" "name")

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
                echo "This may take a few minutes depending on your connection."
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
                printf "Try: (t)oken or (l)icense? [t/l]: "
                read -r fix
                case "$fix" in
                    l|L) prompt_for_license "$repo" ;;
                    *) prompt_for_token || return 1 ;;
                esac
                ;;
            *)
                die "Unexpected response: HTTP $http_code"
                ;;
        esac
    done

    die "Failed after $MAX_ATTEMPTS attempts."
}

# ── Main ─────────────────────────────────────────────────────────────

main() {
    echo ""
    echo "=== Edge LLM Model Manager ==="

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
        echo "    q) Quit"
        echo ""
        printf "  Choice: "
        read -r choice

        case "$choice" in
            q|Q)
                echo ""
                echo "Active model: $(get_active_model)"
                echo "Run './gradlew assembleDebug' to bundle it into the APK."
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
            [0-9]*)
                if [ "$choice" -ge 1 ] && [ "$choice" -le "$count" ]; then
                    local idx=$((choice - 1))
                    download_model "$idx"
                    local fn
                    fn=$(model_field "$idx" "filename")
                    set_active_model "$fn"
                    echo "✓ Activated: $(model_field "$idx" "name")"
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
