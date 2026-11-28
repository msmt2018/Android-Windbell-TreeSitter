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

# grammar.js is the editable grammar source. The first generation emits the
# canonical src/grammar.json; the second pass deliberately consumes that JSON
# so parser.c is reproducible from the checked-in/generated grammar artifact.
npm exec --yes --package="tree-sitter-cli@$CLI_VERSION" -- tree-sitter generate grammar.js --abi="$ABI"
[[ -s "$GRAMMAR_JSON" ]] || { echo "::error::Tree-sitter did not emit src/grammar.json"; exit 1; }

npm exec --yes --package="tree-sitter-cli@$CLI_VERSION" -- tree-sitter generate src/grammar.json --abi="$ABI"
[[ -s "$PARSER" ]] || { echo "::error::Tree-sitter did not emit src/parser.c"; exit 1; }
[[ -s "$NODE_TYPES" ]] || { echo "::error::Tree-sitter did not emit src/node-types.json"; exit 1; }

generated_abi="$(sed -n 's/^#define LANGUAGE_VERSION[[:space:]]\+\([0-9][0-9]*\).*/\1/p' "$PARSER" | head -n 1)"
[[ "$generated_abi" == "$ABI" ]] || {
  echo "::error::TSH parser ABI mismatch: expected $ABI, found ${generated_abi:-unknown}"
  exit 1
}

echo "TSH Tree-sitter grammar generated with ABI $generated_abi"
