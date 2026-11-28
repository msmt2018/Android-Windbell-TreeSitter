/**
 * Tree-sitter grammar for the TSH (TreeSitter Highlight) configuration DSL.
 *
 * TSH is a structured configuration language, not Java/TOML/JSON. This parser
 * intentionally models its native blocks, properties, arrays, regex literals,
 * color literals and style annotations. It is permissive around unknown keys so
 * new TSH directives remain incrementally parseable without grammar churn.
 */
module.exports = grammar({
  name: 'tsh',

  extras: $ => [
    /[\s\uFEFF]+/,
    $.line_comment,
    $.block_comment,
  ],

  word: $ => $.identifier,

  conflicts: $ => [
    [$._value, $.block],
    [$.identifier, $.dotted_identifier],
  ],

  rules: {
    source_file: $ => repeat($._statement),

    _statement: $ => choice(
      $.property,
      $.block,
      $.array_block,
      $.expression_statement,
    ),

    property: $ => seq(
      field('key', $._key),
      choice(':', '='),
      field('value', $._value),
      optional(','),
    ),

    block: $ => seq(
      field('name', $._key),
      optional(seq('(', commaSep($._value), ')')),
      '{',
      repeat($._statement),
      '}',
      optional(','),
    ),

    expression_statement: $ => seq($._value, optional(',')),

    // TSH scheme sections use the concise "light [ ... ]" form.
    array_block: $ => seq(
      field('name', $._key),
      $.array,
      optional(','),
    ),

    _key: $ => choice($.identifier, $.dotted_identifier),

    dotted_identifier: $ => seq(
      $.identifier,
      repeat1(seq('.', $.identifier)),
    ),

    _value: $ => choice(
      $.object,
      $.array,
      $.regex,
      $.color,
      $.string,
      $.template_string,
      $.number,
      $.boolean,
      $.null,
      $.annotated_value,
      $.identifier,
      $.dotted_identifier,
    ),

    object: $ => seq(
      '{',
      commaSep($._object_entry),
      '}',
    ),

    _object_entry: $ => choice($.property, $.block, $.expression_statement),

    array: $ => seq(
      '[',
      commaSep($._value),
      ']',
    ),

    annotated_value: $ => seq(
      choice($.string, $.number, $.color, $.identifier, $.dotted_identifier),
      repeat1($.annotation),
    ),

    annotation: $ => token(seq('@', /[A-Za-z_][A-Za-z0-9_-]*/)),

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

    template_string: $ => seq(
      '`',
      repeat(choice(
        token.immediate(/[^\`\\]+/),
        $.escape_sequence,
      )),
      '`',
    ),

    escape_sequence: _ => token.immediate(seq(
      '\\',
      choice(
        /[\\'"\/bfnrt]/,
        /u[0-9a-fA-F]{4}/,
        /x[0-9a-fA-F]{2}/,
        /./,
      ),
    )),

    regex: $ => token(seq(
      '/',
      repeat(choice(
        /[^\/\\\n\r]/,
        /\\./,
      )),
      '/',
    )),

    color: _ => token(/#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b/),

    number: _ => token(choice(
      /[+-]?(?:0[xX][0-9a-fA-F]+|0[bB][01]+|(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(?:dp|sp|px|ms|s|%|f)?/,
    )),

    boolean: _ => choice('true', 'false'),
    null: _ => choice('null', 'none'),

    identifier: _ => /[A-Za-z_$][A-Za-z0-9_$-]*/,

    line_comment: _ => token(seq('//', /[^\n\r]*/)),
    block_comment: _ => token(seq('/*', /[^*]*\*+([^/*][^*]*\*+)*/, '/')),
  },
});

function commaSep(rule) {
  return optional(seq(rule, repeat(seq(',', rule)), optional(',')));
}
