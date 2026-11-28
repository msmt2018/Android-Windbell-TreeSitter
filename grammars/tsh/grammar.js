/**
 * Tree-sitter grammar for the native Windbell TSH v2 configuration DSL.
 *
 * Keep this grammar aligned with TshLexer/TshCompiler::compileInternal:
 * optional "tsh 2 { ... }" wrapper, colon properties, named blocks, arrays,
 * JSON-style quoted strings, raw backtick strings, regex and color literals.
 * ATS is a separate Tree-sitter Query/selector front-end; the current engine
 * does not implement a standalone ATS lexer/parser.
 */
module.exports = grammar({
  name: 'tsh',

  extras: $ => [
    /[\s\uFEFF]+/,
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

    legacy_document: $ => repeat1($._root_entry),

    _root_entry: $ => choice($.property, $.block),

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

    array_block: $ => seq(
      field('name', $.identifier),
      $.style_array,
      optional(','),
    ),

    style_array: $ => seq('[', repeat(seq($.style_entry, optional(','))), ']'),

    style_entry: $ => seq(
      field('style', $.identifier),
      ':',
      field('color', $.color),
      repeat($.annotation),
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
      repeat(seq(choice($.property, $.block), optional(','))),
      '}',
    ),

    array: $ => seq('[', repeat(seq($._value, optional(','))), ']'),

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

    raw_string: $ => seq(
      '\x60',
      repeat(token.immediate(/[^\x60]+/)),
      '\x60',
    ),

    escape_sequence: _ => token.immediate(seq(
      '\\',
      choice(/["'\\/bfnrt]/, /u[0-9a-fA-F]{4}/),
    )),

    regex: _ => token(seq(
      '/',
      repeat(choice(/[^/\\]/, /\\./)),
      '/',
    )),

    // The native lexer emits colors as identifier tokens; this narrower
    // lexical precedence gives valid hex colors their own named AST node.
    color: _ => token(prec(1, /#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})/)),

    number: _ => token(/[+-]?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/),

    boolean: _ => choice('true', 'false'),
    null: _ => choice('null', 'none'),

    // '.', '#', and '-' are part of native identifiers, not punctuation.
    identifier: _ => /[A-Za-z_#.][A-Za-z0-9_#.-]*/,

    line_comment: _ => token(seq('//', /[^\n\r]*/)),
    block_comment: _ => token(seq('/*', /[^*]*\*+([^/*][^*]*\*+)*/, '/')),
  },
});

