# SCM-to-TSH DSL v2 Migration Guide

**Core conclusion:** SCM is itself the Tree-sitter Query language. The safest migration is to embed the original SCM Query text in `treeSitter.queries[].source`, then bind capture names to TSH semantic styles. Do not translate all SCM into ATS and do not generate new `.scm` files.

## 1. Identify the Responsibility of Each SCM File

- `highlights.scm`: node/anonymous-token Queries and captures; usually migratable to TSH highlighting Queries.
- `locals.scm`: local-variable scope/definition/reference metadata; not equivalent to pure highlighting Query.
- `tags.scm`: definition/reference tag extraction; outside TSH highlighting responsibilities.
- `injections.scm`: embedded-language injection metadata; TSH currently has no injection runtime.
- Custom `.scm`: identify its actual consumer first; do not infer purpose from the extension alone.

TSH currently handles highlighting through ATS, native Tree-sitter Query, and Oniguruma Regex. Do not disguise locals/tags/folding/outline/injection functionality as highlighting rules.

## 2. Simplest Migration: Embed SCM Source

Original `highlights-java.scm`:

~~~scheme
(method_declaration
  name: (identifier) @function.method)

(class_declaration
  name: (identifier) @type)

(string_literal) @string
~~~

TSH:

~~~tsh
tsh 2 {
  language {
    id: "java"
    name: "Java"
    extensions: [".java"]
  }
  styles {
    function { scope: "entity.name.function", tags: ["function"] }
    type { scope: "entity.name.type", tags: ["type"] }
    string { scope: "string.quoted", tags: ["string"] }
  }
  schemes {
    default {
      light [ function: #795E26 type: #267F99 string: #A31515 ]
      dark [ function: #FFC66D type: #4EC9B0 string: #6A8759 ]
    }
  }
  treeSitter {
    queries: [
      {
        id: "java-highlights"
        source: `
          (method_declaration
            name: (identifier) @function_method)

          (class_declaration
            name: (identifier) @type)

          (string_literal) @string
        `
        captures: {
          function_method: function
          type: type
          string: string
        }
      }
    ]
  }
}
~~~

It is recommended to rename dotted capture names (for example, `@function.method`) to stable simple names (for example, `@function_method`) and map them through `captures`. This avoids lexical ambiguity in DSL capture-map keys.

## 3. SCM-to-TSH Concept Mapping

| SCM concept | TSH form | Notes |
|---|---|---|
| `(node)` | Preserve in `treeSitter.queries[].source` | ATS rewrite must be manual and limited to simple conditions |
| `field: (child)` | Preserve Query; or ATS node + field | Consider ATS only for simple single-node field conditions |
| `(node) @capture` | Preserve Query + captures binding | Capture is an output label, not a color |
| `"anonymous_token" @capture` | Preserve Query; or ATS anonymous-node selector | Requires the actual grammar symbol |
| `[...] alternation` | Preserve Query source | ATS has no general equivalent |
| `(#eq? ...)` | Preserve Query if on the TSH allowlist | Do not manually split into string rules |
| `(#match? ...)` | Preserve Query if on the TSH allowlist | Alternatively, ATS textRegex for a single node |
| `(#any-of? ...)` | Preserve Query if on the TSH allowlist | |
| `#set! metadata directive` | Not accepted by current Native TSH | Do not copy directly |
| Capture name | `captures: { name: semanticStyle }` | Explicit binding |
| `highlights.scm` | `treeSitter.queries[].source` | Embed Query source |
| TextMate Regex match | MATCH rule in `contains` | Re-express as Regex |
| TextMate begin/end | BLOCK start/end in `contains` | Only the currently supported BLOCK subset |
| Repository Regex fragment | `defines` or `repository` | Not a pattern object |
| `injections.scm` | No runnable TSH equivalent today | Cannot be migrated as if supported |
| `locals.scm` / `tags.scm` | No corresponding TSH highlighting semantics | Belong to other features |

## 4. When to Keep Query and When to Rewrite as ATS

### 4.1 Keep Query by Default

Preserve SCM source for:

- Deeply nested node patterns.
- Field patterns, anonymous tokens, and alternations.
- Captures and predicates.
- Combinations of multiple structural constraints.
- Cases where you want to track upstream `highlights.scm` updates.

Embed the source text and define capture-to-style mappings.

### 4.2 Cases Suitable for ATS

ATS may be considered when a rule is clearly expressible as a finite set of structural conditions on one candidate node:

- Node type and field.
- Direct parent, ancestor, or direct child.
- Previous/next sibling.
- childIndex/namedChildIndex.
- named/missing/extra/error/hasError.
- text/textPrefix/textSuffix/textRegex.

Example SCM:

~~~scheme
(class_declaration
  name: (identifier) @type)
~~~

ATS form:

~~~tsh
ast: [
  { node: "class_declaration", field: "name", style: type, priority: 100 }
]
~~~

The semantics of the ATS target-node/field combination must be verified against the current compiler/runtime and target grammar. If field/capture semantics are uncertain, retaining Query is safer. Do not force complex Queries into ATS merely to reduce the number of Query blocks.

### 4.3 Do Not Convert Query into Regex

`(method_declaration name: (identifier) @function)` expresses a declaration node and its name-field relationship in a syntax tree. Regex sees only a character stream and cannot reliably distinguish comments, strings, syntax errors, and real declarations. Simulating this with Regex causes false matches and loses grammar structure.

## 5. Predicate Migration

An SCM predicate is attached to a Query pattern and runs against the complete match.

Text matching:

~~~scheme
((identifier) @constant
 (#match? @constant "^[A-Z][A-Z0-9_]+$"))
~~~

Embed it directly:

~~~tsh
treeSitter {
  queries: [
    {
      id: "constants"
      source: `
        ((identifier) @constant
         (#match? @constant "^[A-Z][A-Z0-9_]+$"))
      `
      captures: { constant: constant }
    }
  ]
}
~~~

Capture-to-capture comparison:

~~~scheme
((assignment_expression
   left: (identifier) @left
   right: (identifier) @right)
 (#eq? @left @right))
~~~

Keep this form in `source`; ATS currently has no general operation for comparing two captured nodes.

Alternation predicates must remain in the same group as the structural pattern:

~~~scheme
(
  [(identifier) (type_identifier)] @name
  (#any-of? @name "Foo" "Bar")
)
~~~

Do not split the predicate and alternation into separate top-level patterns. The structural pattern could otherwise emit results without predicate filtering. TSH rejects forms that may weaken filter semantics.

Before migration, compare each predicate against the Native predicate allowlist in the [TSH DSL v2 Syntax Reference](TSH_DSL_V2_SYNTAX.en.md). Support in Tree-sitter CLI or other editor plugins does not imply TSH support. `#set!` is not a capture-to-style mapping; TSH uses the `captures` block.

## 6. Bind Capture Names to Style Names

An SCM capture is a Query result label, not a TSH color. TSH must map it to a semantic style.

~~~scheme
(method_declaration name: (identifier) @function)
~~~

~~~tsh
styles {
  function { scope: "entity.name.function", tags: ["function"] }
}
treeSitter {
  queries: [
    {
      source: `
        (method_declaration name: (identifier) @function)
      `
      captures: { function: function }
    }
  ]
}
~~~

Keeping capture and style names identical is recommended. If legacy SCM uses scope-like names such as `@function.method` or `@variable.parameter`, prefer `@method_name` or `@parameter` and map them to styles. This reduces lexical ambiguity in capture-map keys.

## 7. TextMate Regex Grammar to `contains`

SCM Query and TextMate Regex grammar are different languages. If the legacy language package also has JSON/TextMate grammar:

| TextMate concept | TSH Regex |
|---|---|
| match | match |
| begin | start |
| end | end |
| beginCaptures | startCaptures / beginCaptures |
| endCaptures | endCaptures |
| name | style |
| contentName | contentName |
| nested patterns | contains |
| reusable Regex fragment | defines / repository |

Example:

~~~tsh
styles {
  docComment { scope: "comment.block.documentation" }
  docTag { scope: "storage.type.annotation" }
}
contains: [
  {
    style: docComment
    start: /\/\*\*/
    end: /\*\//
    contains: [
      { match: /@\w+/, 0: docTag }
    ]
  }
]
~~~

This covers only the current TSH BLOCK subset. TextMate `while`, `applyEndPatternLast`, external includes, injection selectors, complete precedence, and pattern-object repositories cannot be copied directly.

## 8. Migration Recommendations for the Repository's Java Highlights

The current fixture `android-tree-sitter/src/test/resources/highlights-java.scm` contains:

| SCM content | Recommended migration |
|---|---|
| method_declaration/method_invocation name | Preserve Query; validate simple field cases before considering ATS |
| annotation/marker_annotation | Keep Query; annotation name may use ATS |
| anonymous `"@"` token | Keep Query; or ATS anonymous node + parent |
| type_identifier/scoped_type_identifier | ATS or Query |
| class/interface/enum declaration name | ATS field selector or Query |
| field_access/scoped_identifier/method_reference receiver | Prefer Query |
| `#match?` uppercase identifier | Query predicate; ATS textRegex may be evaluated for a single node |
| number/string/character/comment nodes | ATS or Query; prefer grammar nodes |
| true/false/null literal | Query/ATS |
| keyword anonymous-token set | Query alternation; maintaining individual ATS rules is more expensive |

Example:

~~~scheme
((field_access
  object: (identifier) @type)
 (#match? @type "^[A-Z]"))
~~~

~~~tsh
treeSitter {
  queries: [
    {
      id: "java-type-receiver"
      source: `
        ((field_access
          object: (identifier) @type)
         (#match? @type "^[A-Z]"))
      `
      captures: { type: type }
    }
  ]
}
~~~

## 9. Batch Migration Workflow

1. Classify every SCM file: highlights, locals, tags, injections, or custom.
2. Identify the matching grammar binary/TSLanguage version.
3. First embed Query source unchanged; do not begin by rewriting it as ATS.
4. Extract all captures and build a capture-to-semantic-style mapping.
5. Declare styles and light/dark palettes as needed.
6. Review each predicate against the Native TSH allowlist.
7. Migrate TextMate Regex rules separately into `contains`.
8. Split Query blocks by responsibility and assign stable IDs/priorities.
9. Invoke the Native compiler with the actual TSLanguage; Parser acceptance does not prove Query compilation succeeds.
10. Compare old SCM and new TSH capture ranges, capture names, styles, and predicate results.
11. Remove old resources only after confirming the application no longer reads external SCM at runtime.
12. Verify light/dark themes, alpha, overlaps, nested captures, and final Canvas output for large-document viewports.

## 10. Migration Errors to Avoid

- Do not put SCM Query into `ast`.
- Do not disguise ATS selectors as Query.
- Do not crudely convert Query structural conditions into global Regex.
- Do not treat capture names as colors or fixed style IDs.
- Do not assume every Tree-sitter predicate is supported by TSH.
- Do not use `#set!` as style binding.
- Do not treat locals/tags/injections as ordinary highlighting rules.
- Do not put unsupported Monarch or TextMate features into production .tsh files.

## 11. Definition of Migration Complete

Migration is complete only when highlighting responsibilities are covered by TSH ATS/Query/Regex; capture ranges and semantic mappings are checked; all predicates are within Native support; Query and target grammar ABI/node tables agree; runtime no longer implicitly depends on external SCM; and light/dark, alpha, overlap, and renderer output are verified. Completing documentation does not mean compilation, tests, or device verification have passed.
