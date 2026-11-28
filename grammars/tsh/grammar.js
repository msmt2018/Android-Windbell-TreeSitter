/**
 * Tree-sitter grammar for the native Windbell TSH v2 configuration DSL.
 *
 * Keep this grammar aligned with TshLexer/TshCompiler::compileInternal:
 * - optional "tsh 2 { ... }" document wrapper (legacy unwrapped profiles remain valid)
 * - colon-only properties, named blocks and bracket arrays
 * - JSON-style quoted strings, raw backtick strings and slash-delimited regexes
 * - colors are lexer-level identifiers beginning with '#'
 *
 * This grammar models the configuration language only. ATS is a separate
 * Tree-sitter Query/selector front-end and does not have a native ATS lexer
 * implementation in the current engine.
 */
module.exports = grammar({
  name: 'tsh',

  extras: $ => [
    /[\\s\\uFEFF]+/,
    $.line_comment,
    $.block_comment,
  ],

  rules: {
    source_file: $ => choice(
      $.versioned_document,
      $.legacy_document,
    ),

    versioned_document: $ => seq(
      'tsh',
      '2',
      '{',
      repeat($._root_entry),
      '}',
    ),

    legacy_document: $ => repeat($._root_entry),

    _root_entry: $ => choice(
      $.property,
      $.block,
    ),

    property: $ => seq(
      field('key', $.identifier),
      ':',
      field('value', $._value),
      optional(','),
    ),

    block: $ => seq(
      field('name', $.identifier),
      '{',
      repeat($._block_entry),
      '}',
      optional(','),
    ),

    _block_entry: $ => choice(
      $.property,
      $.block,
      $.array_block,
    ),

    // TSH theme sections use the native "light [ ... ]" / "dark [ ... ]" form.
    array_block: $ => seq(
      field('name', $.identifier),
      $.array,
      optional(','),
    ),

    _value: $ => choice(
      $.object,
      $.array,
      $.regex,
      $.color,
      $.string,
      $.raw_string,
      $.number,
      $.boolean,
      $.null,
      $.annotated_value,
      $.identifier,
    ),

    object: $ => seq(
      '{',
      commaSep(choice($.property, $.block)),
      '}',
    ),

    array: $ => seq(
      '[',
      commaSep($._value),
      ']',
    ),

    annotated_value: $ => seq(
      choice($.color, $.string, $.number, $.identifier),
      repeat1($.annotation),
    ),

    annotation: $ => seq('@', $.identifier),

    string: $ => choice(
      seq('"', repeat(choice(
        token.immediate(/[^"\\\n\r]+/),
        $.escape_sequence,
      )), '"'),
      seq("'", repeat(choice(
        token.immediate(/[^'\\\n\r]+/),
        $.escape_sequence,
      )), "'"),
    ),

    // Backtick strings are raw and may span multiple lines in the native lexer.
    raw_string: $ => seq(
      '`',
      repeat(token.immediate(/[^\`]+/)),
      '`',
    ),

    escape_sequence: _ => token.immediate(seq(
      '\\\\',
      choice(
        /["'\\/bfnrt]/,
        /u[0-9a-fA-F]{4}/,
      ),
    )),

    regex: _ => token(seq(
      '/',
      repeat(choice(
        /[^/\\]/,
        /\\./,
      )),
      '/',
    )),

    // The native lexer emits color literals as identifier tokens.
    color: _ => token(/#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})/),

    number: _ => token(/[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/),

    boolean: _ => choice('true', 'false'),
    null: _ => choice('null', 'none'),

    // Match the C++ lexer: '.', '#', and '-' are part of identifiers, not
    // standalone punctuation. '$' is intentionally excluded.
    identifier: _ => /[A-Za-z_#.][A-Za-z0-9_#.-]*/,

    line_comment: _ => token(seq('//', /[^\\n\\r]*/)),
    block_comment: _ => token(seq('/*', /[^*]*\\*+([^/*][^*]*\\*+)*/, '/')),
  },
});

function commaSep(rule) {
  return optional(seq(rule, repeat(seq(',', rule)), optional(',')));
}
