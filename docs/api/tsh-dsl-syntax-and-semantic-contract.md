# TSH DSL 语法与语义契约

> 状态：拟定的 TSH DSL v1 规范。正式实现必须以语法测试和版本化 Schema 为准；本文件中的语法是目标契约，不代表当前 Native TshCompiler 已完整接受。

## 1. 唯一执行模型

TSH 只有两条核心执行管线：Tree-sitter + ATS（Tree-sitter Query/SCM 简化混合语法）和独立 Regex 管线。TextMate/Monarch 的 selector、scope、主题级联仅作为 DSL 设计参考，不引入其运行时或第三执行管线。

## 2. 顶层声明

v1 顶层声明集合：meta、language、grammar、query、regex、scope、selector、attribute、theme、style、font、color、decoration、injection、variables、include。声明具有稳定 ID；同一命名空间重复 ID 为错误。

~~~tsh
tsh 1

language "source.example" {
  extensions: [".ex", ".example"]
  grammar: "grammar.example"
  queries: ["queries.example"]
  regex: "regex.example"
}

theme "midnight" {
  extends: "theme.base"
  color "editor.foreground": #D8DEE9
  color "editor.background": #20242C
  font "editor.default" {
    family: ["JetBrains Mono", "monospace"]
    size: 14sp
    weight: 400
    style: normal
  }
}

scope "comment" {
  selector: "comment"
  attributes: {
    foreground: color("syntax.comment")
    fontStyle: italic
  }
}

style "comment-style" {
  target: scope("comment")
  theme: "midnight"
  foreground: color("#7F8C98")
  fontStyle: italic
}
~~~

Lexer 必须明确区分字符串、标识符、数字、颜色、单位、正则字面量、数组、对象、注释和多行 Query 字面量。不得依赖正则替换或按行切割实现 Parser。

## 3. 类型系统与属性 Schema

基础类型：Boolean、Int、Float、String、Identifier、Enum、Color、FontFamily、FontSize、Length、Angle、Duration、Regex、Query、Array<T>、Map<K,V>、Object、Reference<T>、Expression<T>。

禁止将任意字符串隐式转换为 Color/Regex/Query/FontSize。px、dp、sp、em、rem、%、pt 必须保留单位，并在明确度量上下文解析。颜色支持 #RGB/#RGBA/#RRGGBB/#RRGGBBAA、rgb()/rgba()/hsl()/hsla()、命名颜色和变量引用；非法分量必须诊断。

每个属性 Schema 至少定义 name、type、required、default、nullable、allowedValues、min/max、unit、inheritable、mergePolicy、deprecatedSince、introducedIn、capabilityRequirement。未知属性在 strict 模式报错；宽松模式也必须 warning，不能静默忽略。命名空间区分 core、grammar、query、regex、scope、theme、font、render 和扩展命名空间。

## 4. 表达式与引用

支持字面量、数组/对象、变量引用、命名对象引用、有限内建函数和条件表达式。表达式具有静态结果类型。引用解析在编译期完成；未定义引用、类型不匹配和循环引用均报错。内建函数采用白名单，不允许任意脚本或反射。

## 5. 规则与匹配声明

Tree-sitter/ATS 规则必须绑定 grammar/query，Regex 规则必须绑定 regex pattern。两类规则最终映射统一 TshMatch，但保留 sourceKind、ruleId、capture、priority 和 sourceRange。不得把 Regex 声明注入 Tree-sitter 管线，反之亦然。

~~~tsh
query "syntax.example" {
  grammar: "grammar.example"
  source: <<QUERY
    (comment) @comment
    (string) @string
  QUERY
}

regex "numbers.example" {
  pattern: /\\b[0-9]+(\\.[0-9]+)?\\b/
  flags: [unicode]
  capture "0" -> scope("constant.numeric")
}
~~~

实际 Parser 必须定义多行 Query/Regex 字面量转义和终止规则，不能让正文被外层 DSL 误解析。

## 6. Scope、Selector 与样式

Scope 是匹配结果的语义分类，不是第三种匹配管线。Selector 选择 Scope/上下文；Style 将属性应用到选择结果。选择器必须定义组合、祖先/后代、精确匹配、通配和优先级语义，且有复杂度上限。

样式合成顺序必须明确：默认值 < Theme 基础规则 < 语言 Scope 规则 < 更具体 Selector < 显式 Style 覆盖 < 宿主显式覆盖（若启用）。同级规则按 specificity、priority、声明顺序确定性排序，并记录属性来源。

## 7. 诊断、恢复与版本

Lexer、Parser、Schema、TypeChecker、ReferenceResolver、SemanticAnalyzer、Compiler 各阶段产生结构化诊断。诊断含稳定 code、stage、severity、source URI、revision、range、related locations。Parser 尽可能错误恢复，但不完整 AST 不得编译发布。

每个 DSL 版本必须有规范语法、Schema、正反例 fixture、格式化规则、迁移器和兼容测试。当前为拟定语法契约，不代表当前编译器已支持全部语法。
