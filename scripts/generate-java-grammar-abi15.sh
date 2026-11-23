#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
grammar_dir="$repo_root/grammars/java"
parser="$grammar_dir/src/parser.c"
grammar_json="$grammar_dir/src/grammar.json"
required_abi=15
cli_version="0.25.10"

if [[ ! -f "$grammar_json" || ! -f "$parser" ]]; then
  echo "::error::Java grammar source is incomplete; expected $grammar_json and $parser"
  exit 1
fi

current_abi="$(sed -n 's/^#define LANGUAGE_VERSION[[:space:]]\+\([0-9][0-9]*\).*/\1/p' "$parser" | head -n 1)"
if [[ "$current_abi" != "$required_abi" ]]; then
  echo "::notice::Regenerating tree-sitter-java parser: ABI ${current_abi:-unknown} -> $required_abi"
  (
    cd "$grammar_dir"
    npm exec --yes --package="tree-sitter-cli@$cli_version" -- \
      tree-sitter generate src/grammar.json --abi="$required_abi"
  )
fi

generated_abi="$(sed -n 's/^#define LANGUAGE_VERSION[[:space:]]\+\([0-9][0-9]*\).*/\1/p' "$parser" | head -n 1)"
if [[ "$generated_abi" != "$required_abi" ]]; then
  echo "::error::Java grammar regeneration did not produce ABI $required_abi (found ${generated_abi:-unknown})"
  exit 1
fi

echo "Java grammar parser ABI verified: $generated_abi"
