#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAMMAR_DIR="$ROOT/grammars/tsh"
SOURCE="$GRAMMAR_DIR/grammar.js"
GRAMMAR_JSON="$GRAMMAR_DIR/src/grammar.json"
PARSER="$GRAMMAR_DIR/src/parser.c"
NODE_TYPES="$GRAMMAR_DIR/src/node-types.json"
CLI_VERSION="0.25.10"
ABI=15

[[ -f "$SOURCE" ]] || { echo "::error::Missing TSH grammar.js"; exit 1; }
cd "$GRAMMAR_DIR"

# Tree-sitter's supported generation flow reads grammar.js and emits
# both the canonical src/grammar.json and generated src/parser.c in one pass.
# grammar.json is generated metadata, not a second grammar.js replacement input.
npm exec --yes --package="tree-sitter-cli@$CLI_VERSION" -- tree-sitter generate --abi="$ABI"
[[ -s "$GRAMMAR_JSON" ]] || { echo "::error::Tree-sitter did not emit src/grammar.json"; exit 1; }

[[ -s "$PARSER" ]] || { echo "::error::Tree-sitter did not emit src/parser.c"; exit 1; }
[[ -s "$NODE_TYPES" ]] || { echo "::error::Tree-sitter did not emit src/node-types.json"; exit 1; }

sample="$ROOT/app/src/main/assets/tsh/tsh.tsh"
if [[ -f "$sample" ]]; then
  parse_log="$(npm exec --yes --package="tree-sitter-cli@$CLI_VERSION" -- tree-sitter parse "$sample" 2>&1)" || {
    printf '%s\n' "$parse_log"
    echo "::error::The repository TSH profile could not be parsed by the generated grammar"
    exit 1
  }
  if grep -Eq '(^|[[:space:]])(ERROR|MISSING)([[:space:]]|$)' <<< "$parse_log"; then
    printf '%s\n' "$parse_log"
    echo "::error::The repository TSH profile contains Tree-sitter ERROR or MISSING nodes"
    exit 1
  fi
  echo "Repository TSH profile parsed without ERROR/MISSING nodes."
fi

generated_abi="$(sed -n 's/^#define LANGUAGE_VERSION[[:space:]]\+\([0-9][0-9]*\).*/\1/p' "$PARSER" | head -n 1)"
[[ "$generated_abi" == "$ABI" ]] || {
  echo "::error::TSH parser ABI mismatch: expected $ABI, found ${generated_abi:-unknown}"
  exit 1
}

echo "TSH Tree-sitter grammar generated with ABI $generated_abi"
