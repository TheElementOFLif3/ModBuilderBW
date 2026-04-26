#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="${1:-$HOME/Desktop/Mod Builder/installer_outputs_windows_native}"
REF="${2:-$(git -C "$REPO_ROOT" branch --show-current)}"
WORKFLOW_FILE="release.yml"
ARTIFACT_NAME="ModBuilderBW-Windows"
INSTALLER_NAME="ModBuilderBW-Windows-Installer.msi"

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required tool: $1" >&2
    exit 1
  fi
}

require_tool gh
require_tool git
require_tool python3

if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit and push your changes before using the GitHub MSI build." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

before_id="$(gh run list --repo TheElementOFLif3/ModBuilderBW --workflow "$WORKFLOW_FILE" --branch "$REF" --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId // 0')"

echo "Dispatching GitHub Release workflow for ref: $REF"
gh workflow run "$WORKFLOW_FILE" --repo TheElementOFLif3/ModBuilderBW --ref "$REF"

run_id=""
for _ in {1..30}; do
  run_id="$(gh run list --repo TheElementOFLif3/ModBuilderBW --workflow "$WORKFLOW_FILE" --branch "$REF" --event workflow_dispatch --limit 20 --json databaseId,headBranch --jq ".[] | select(.headBranch == \"$REF\") | .databaseId" | head -n 1)"
  if [[ -n "$run_id" && "$run_id" != "$before_id" ]]; then
    break
  fi
  sleep 5
done

if [[ -z "$run_id" || "$run_id" == "$before_id" ]]; then
  echo "Unable to resolve the new workflow run id." >&2
  exit 1
fi

echo "Watching workflow run: $run_id"
gh run watch "$run_id" --repo TheElementOFLif3/ModBuilderBW --exit-status --interval 15

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

gh run download "$run_id" --repo TheElementOFLif3/ModBuilderBW --name "$ARTIFACT_NAME" --dir "$tmp_dir"

installer_path="$(find "$tmp_dir" -name "$INSTALLER_NAME" -print -quit)"
if [[ -z "$installer_path" ]]; then
  echo "Workflow completed but $INSTALLER_NAME was not found in downloaded artifacts." >&2
  exit 1
fi

cp -f "$installer_path" "$OUTPUT_DIR/$INSTALLER_NAME"
echo "MSI downloaded:"
echo "$OUTPUT_DIR/$INSTALLER_NAME"
