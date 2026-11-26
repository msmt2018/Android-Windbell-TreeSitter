#!/usr/bin/env bash
set -Eeuo pipefail

# Install each independent grammar package once. Workspace children are
# installed by their nearest workspace root to avoid duplicate npm symlinks.
mapfile -d '' manifests < <(
  while IFS= read -r -d '' manifest; do
    dir_path="$(dirname "$manifest")"
    ancestor="$(dirname "$dir_path")"
    workspace_managed=false
    while [[ "$ancestor" == grammars/* ]]; do
      parent_manifest="$ancestor/package.json"
      if [[ -f "$parent_manifest" ]] && node -e 'const p=require(process.argv[1]); process.exit(p.workspaces ? 0 : 1)' "$PWD/$parent_manifest"; then
        workspace_managed=true
        break
      fi
      next_ancestor="$(dirname "$ancestor")"
      [[ "$next_ancestor" == "$ancestor" ]] && break
      ancestor="$next_ancestor"
    done
    if [[ "$workspace_managed" == false ]]; then
      printf '%s\0' "$dir_path"
    fi
  done < <(find grammars -mindepth 2 -maxdepth 3 -type f -name package.json ! -path 'grammars/asm/*' ! -path 'grammars/*/test-npm-package/*' -print0 | sort -z)
)

if (( ${#manifests[@]} == 0 )); then
  echo "No grammar npm package manifests found." >&2
  exit 1
fi

for dir_path in "${manifests[@]}"; do
  echo "::group::Installing grammar dependencies: $dir_path"
  (cd "$dir_path" && npm install --legacy-peer-deps --no-audit --no-fund)
  echo "::endgroup::"
done
