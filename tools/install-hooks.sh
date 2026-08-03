#!/usr/bin/env bash
# Installs a pre-commit hook that runs tools/scan-secrets.py --staged before every commit.
#
# It scans the git INDEX (what `git commit` would actually record), not the working tree, so a
# file that is only partially staged is checked against the part that is about to be committed -
# the same content a reviewer would see land, not whatever else happens to be sitting on disk.
#
# This script only INSTALLS the hook; it does not run it. Nothing in this repository invokes it
# automatically - each clone opts in by running:
#
#   ./tools/install-hooks.sh
#
# Deliberately out of scope for this pass: scanning git history. A hook here only ever sees
# future commits: it cannot do anything about a secret already at HEAD, and a false sense that it
# does is worse than being silent about it. See docs/RELEASE.md section 4c for what it takes to
# actually get a value that already landed off a public remote (rotate it - removing it from HEAD
# does not unpublish it).

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
hooks_dir="$repo_root/.git/hooks"
hook_path="$hooks_dir/pre-commit"

mkdir -p "$hooks_dir"

if [ -e "$hook_path" ] && [ ! -L "$hook_path" ]; then
  existing_marker="installed by tools/install-hooks.sh"
  if ! grep -q "$existing_marker" "$hook_path" 2>/dev/null; then
    echo "error: $hook_path already exists and was not installed by this script." >&2
    echo "       Move it aside first if you want to replace it." >&2
    exit 1
  fi
fi

cat > "$hook_path" <<'HOOK'
#!/bin/sh
# installed by tools/install-hooks.sh - scans STAGED content only, not the working tree.
set -e
repo_root="$(git rev-parse --show-toplevel)"
exec python3 "$repo_root/tools/scan-secrets.py" --staged
HOOK

chmod +x "$hook_path"

echo "Installed pre-commit secret scan at $hook_path (scans staged content only)."
