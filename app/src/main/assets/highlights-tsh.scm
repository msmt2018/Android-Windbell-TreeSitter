; TSH configuration language — Tree-sitter query highlights.
; Keep captures syntax-driven; avoid whole-document regex scans.

(line_comment) @comment
(block_comment) @comment

(string) @string
(template_string) @string
(regex) @string.special
(color) @constant
(number) @number
(boolean) @boolean
(null) @constant.builtin
(annotation) @attribute

(property
  key: (identifier) @property)

(property
  key: (dotted_identifier) @property)

(block
  name: (identifier) @type)

(array_block
  name: (identifier) @type)

(escape_sequence) @string.escape

(block
  name: (dotted_identifier) @type)

(dotted_identifier
  (identifier) @namespace)

[
  "{"
  "}"
  "["
  "]"
  "("
  ")"
  ","
  ":"
  "="
  "."
] @punctuation.delimiter

[
  "language"
  "editor"
  "comments"
  "indentation"
  "completion"
  "styles"
  "schemes"
  "defines"
  "ast"
  "treeSitter"
  "contains"
  "queries"
  "captures"
  "ui"
  "light"
  "dark"
] @keyword
