#!/usr/bin/env bash
# ==============================================================================
# sync-upstream.sh
# Checks for and incorporates upstream changes from https://github.com/storytold/deckcraft
# ==============================================================================
set -euo pipefail

UPSTREAM_REPO="https://github.com/storytold/deckcraft.git"
INFO_FILE=".upstream-info.json"
TARGET_DIR="deckcraft"
MODE="sync" # "check" or "sync"
TARGET_REF="main"

print_usage() {
    cat <<EOF
Usage: $0 [options]

Options:
  --check              Only check for upstream changes without modifying files
  --sync               Fetch and incorporate upstream changes (default)
  --ref <name>         Branch or tag to sync from (default: main)
  --help               Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)
            MODE="check"
            shift
            ;;
        --sync)
            MODE="sync"
            shift
            ;;
        --ref)
            TARGET_REF="$2"
            shift 2
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1"
            print_usage
            exit 1
            ;;
    esac
done

echo "=================================================="
echo "  DeckCraft Upstream Sync Tool"
echo "  Upstream: $UPSTREAM_REPO ($TARGET_REF)"
echo "  Mode:     $MODE"
echo "=================================================="

# 1. Read current metadata
CURRENT_SHA="none"
CURRENT_TAG="none"
if [ -f "$INFO_FILE" ]; then
    CURRENT_SHA=$(grep -o '"current_commit": *"[^"]*"' "$INFO_FILE" | cut -d'"' -f4 || echo "none")
    CURRENT_TAG=$(grep -o '"current_tag": *"[^"]*"' "$INFO_FILE" | cut -d'"' -f4 || echo "none")
fi
echo "Current tracked commit: $CURRENT_SHA"
echo "Current tracked tag:    $CURRENT_TAG"

# 2. Query remote upstream HEAD / ref
echo "Querying upstream remote for $TARGET_REF..."
LATEST_SHA=$(git ls-remote "$UPSTREAM_REPO" "refs/heads/$TARGET_REF" "refs/tags/$TARGET_REF" | awk '{print $1}' | head -n 1)
if [ -z "$LATEST_SHA" ]; then
    LATEST_SHA=$(git ls-remote "$UPSTREAM_REPO" HEAD | awk '{print $1}')
fi
LATEST_SHA_SHORT="${LATEST_SHA:0:7}"

# Query latest semantic release tag from upstream
LATEST_TAG=$(git ls-remote --tags "$UPSTREAM_REPO" | grep -v '\^{}' | awk '{print $2}' | sed 's#refs/tags/##' | sort -V | tail -n 1 || echo "unknown")

echo "Latest upstream commit: $LATEST_SHA ($LATEST_SHA_SHORT)"
echo "Latest upstream tag:    $LATEST_TAG"

HAS_CHANGES="false"
if [ "$CURRENT_SHA" != "$LATEST_SHA" ]; then
    HAS_CHANGES="true"
fi

# Write GitHub Actions step outputs if in CI
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "has_changes=$HAS_CHANGES" >> "$GITHUB_OUTPUT"
    echo "current_sha=$CURRENT_SHA" >> "$GITHUB_OUTPUT"
    echo "latest_sha=$LATEST_SHA" >> "$GITHUB_OUTPUT"
    echo "latest_sha_short=$LATEST_SHA_SHORT" >> "$GITHUB_OUTPUT"
    echo "current_tag=$CURRENT_TAG" >> "$GITHUB_OUTPUT"
    echo "latest_tag=$LATEST_TAG" >> "$GITHUB_OUTPUT"
    echo "target_ref=$TARGET_REF" >> "$GITHUB_OUTPUT"
fi

if [ "$HAS_CHANGES" = "false" ]; then
    echo "SUCCESS: Upstream is already up to date with commit $CURRENT_SHA."
    exit 0
fi

echo ">> Upstream has new changes available!"

if [ "$MODE" = "check" ]; then
    echo "Check mode finished. Changes detected."
    exit 0
fi

# 3. Incorporate upstream changes
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Cloning upstream $UPSTREAM_REPO ($TARGET_REF)..."
git clone --depth 50 --branch "$TARGET_REF" "$UPSTREAM_REPO" "$TMP_DIR/upstream"

COMMIT_SUMMARY=$(git -C "$TMP_DIR/upstream" log -n 5 --oneline || echo "Latest changes from upstream")
echo "Recent upstream commits:"
echo "$COMMIT_SUMMARY"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
    # Multiline output for GitHub Actions
    {
        echo "commit_summary<<EOF"
        echo "$COMMIT_SUMMARY"
        echo "EOF"
    } >> "$GITHUB_OUTPUT"
fi

# Sync upstream tree into $TARGET_DIR, preserving .git in our repo
echo "Synchronizing files into $TARGET_DIR/..."
mkdir -p "$TARGET_DIR"
rsync -av --delete \
    --exclude='.git' \
    --exclude='.github' \
    "$TMP_DIR/upstream/" "$TARGET_DIR/"

# 4. Update metadata file
NOW_UTC=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
cat <<EOF > "$INFO_FILE"
{
  "repository": "https://github.com/storytold/deckcraft",
  "branch": "$TARGET_REF",
  "current_commit": "$LATEST_SHA",
  "current_tag": "$LATEST_TAG",
  "last_synced_at": "$NOW_UTC"
}
EOF

echo "=================================================="
echo "  Upstream sync completed successfully!"
echo "  Updated $INFO_FILE to $LATEST_SHA_SHORT ($LATEST_TAG) at $NOW_UTC"
echo "=================================================="
