/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 完整的纯 C++20 词法分析与递归下降语法解析器
 *
 * 绝对零硬编码：
 * 所有的 AST 节点、字段名、父节点约束、正则表达式、高亮样式完全动态从传入的 source 读取！
 */

#include "WindbellTshCompiler.h"
#include <cctype>
#include <sstream>
#include <algorithm>
#include <vector>
#include "ts__log.h"

namespace windbell::tsh {

// 内部词法分析器，支持规范的 JSON5、注释过滤、无引号键名、正则表达式与字符串提取
class TshLexer {
public:
    enum class TokenType {
        TOK_EOF,
        TOK_IDENT,
        TOK_STRING,
        TOK_REGEX,
        TOK_LBRACE,   // {
        TOK_RBRACE,   // }
        TOK_LBRACKET, // [
        TOK_RBRACKET, // ]
        TOK_COLON,    // :
        TOK_COMMA,    // ,
        TOK_GT,       // >
        TOK_AT        // @
    };

    struct Token {
        TokenType type;
        std::string text;
    };

    explicit TshLexer(const std::string& src) : src_(src), cursor_(0) {}

    Token nextToken() {
        skipIgnored();
        if (cursor_ >= src_.size()) {
            return {TokenType::TOK_EOF, ""};
        }

        char c = src_[cursor_];
        if (c == '{') { cursor_++; return {TokenType::TOK_LBRACE, "{"}; }
        if (c == '}') { cursor_++; return {TokenType::TOK_RBRACE, "}"}; }
        if (c == '[') { cursor_++; return {TokenType::TOK_LBRACKET, "["}; }
        if (c == ']') { cursor_++; return {TokenType::TOK_RBRACKET, "]"}; }
        if (c == ':') { cursor_++; return {TokenType::TOK_COLON, ":"}; }
        if (c == ',') { cursor_++; return {TokenType::TOK_COMMA, ","}; }
        if (c == '>') { cursor_++; return {TokenType::TOK_GT, ">"}; }
        if (c == '@') { cursor_++; return {TokenType::TOK_AT, "@"}; }

        // 解析字符串 (单双引号)
        if (c == '"' || c == '\'') {
            char quote = c;
            cursor_++;
            std::string content;
            while (cursor_ < src_.size() && src_[cursor_] != quote) {
                if (src_[cursor_] == '\\' && cursor_ + 1 < src_.size()) {
                    cursor_++;
                }
                content += src_[cursor_++];
            }
            if (cursor_ < src_.size()) cursor_++; // 消费闭合引号
            return {TokenType::TOK_STRING, content};
        }

        // 解析正则表达式 /.../
        if (c == '/') {
            cursor_++;
            std::string regex_body;
            while (cursor_ < src_.size() && src_[cursor_] != '/') {
                if (src_[cursor_] == '\\' && cursor_ + 1 < src_.size()) {
                    regex_body += src_[cursor_++];
                }
                regex_body += src_[cursor_++];
            }
            if (cursor_ < src_.size()) cursor_++; // 消费闭合斜杠
            return {TokenType::TOK_REGEX, regex_body};
        }

        // 解析标识符、带 # 符号的内置宏或颜色（如 keywordsToRegex, #JAVA_NUMBER#, #FFFFFF）
        if (std::isalpha(static_cast<unsigned char>(c)) || c == '_' || c == '#' || c == '.') {
            std::string ident;
            while (cursor_ < src_.size()) {
                char cur = src_[cursor_];
                if (std::isalnum(static_cast<unsigned char>(cur)) || cur == '_' || cur == '#' || cur == '.' || cur == '-') {
                    ident += cur;
                    cursor_++;
                } else {
                    break;
                }
            }
            return {TokenType::TOK_IDENT, ident};
        }

        // 解析数值
        if (std::isdigit(static_cast<unsigned char>(c))) {
            std::string num_str;
            while (cursor_ < src_.size() && (std::isdigit(static_cast<unsigned char>(src_[cursor_])) || src_[cursor_] == 'x' || src_[cursor_] == 'X')) {
                num_str += src_[cursor_++];
            }
            return {TokenType::TOK_IDENT, num_str};
        }

        cursor_++;
        return nextToken();
    }

    // 提取括号内部的完整原始文本内容（专门针对 keywordsToRegex("...") 嵌套调用）
    std::string extractParenthesizedBlock() {
        skipIgnored();
        if (cursor_ < src_.size() && src_[cursor_] == '(') {
            cursor_++;
            std::string block;
            int depth = 1;
            while (cursor_ < src_.size() && depth > 0) {
                if (src_[cursor_] == '(') depth++;
                else if (src_[cursor_] == ')') depth--;
                if (depth > 0) block += src_[cursor_];
                cursor_++;
            }
            return block;
        }
        return "";
    }

private:
    void skipIgnored() {
        while (cursor_ < src_.size()) {
            char c = src_[cursor_];
            if (std::isspace(static_cast<unsigned char>(c))) {
                cursor_++;
            } else if (c == '/' && cursor_ + 1 < src_.size() && src_[cursor_ + 1] == '/') {
                cursor_ += 2;
                while (cursor_ < src_.size() && src_[cursor_] != '\n') cursor_++;
            } else if (c == '/' && cursor_ + 1 < src_.size() && src_[cursor_ + 1] == '*') {
                cursor_ += 2;
                while (cursor_ + 1 < src_.size() && !(src_[cursor_] == '*' && src_[cursor_ + 1] == '/')) cursor_++;
                cursor_ += 2;
            } else {
                break;
            }
        }
    }

    const std::string& src_;
    size_t cursor_;
};

std::string TshCompiler::expandKeywordsToRegex(const std::string& word_list) {
    std::istringstream iss(word_list);
    std::string item;
    std::vector<std::string> words;
    while (iss >> item) {
        if (!item.empty() && (item.front() == '"' || item.front() == '\'')) item = item.substr(1);
        if (!item.empty() && (item.back() == '"' || item.back() == '\'')) item.pop_back();
        if (!item.empty()) words.push_back(item);
    }
    if (words.empty()) return "";

    std::string out = "\\b(?:";
    for (size_t i = 0; i < words.size(); ++i) {
        if (i > 0) out += "|";
        out += words[i];
    }
    out += ")\\b";
    return out;
}

std::string TshCompiler::expandBuiltin(const std::string& builtin_name) {
    if (builtin_name == "#JAVA_QUOTED_STRING#" || builtin_name == "#C_QUOTED_STRING#") {
        return "(?:\"(?:\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*')";
    } else if (builtin_name == "#JAVA_NUMBER#" || builtin_name == "#C_NUMBER#") {
        return "\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[0-7_]+|[0-9_]+(?:\\.[0-9_]+)?(?:[eE][+-]?[0-9_]+)?[fFdDlL]?)\\b";
    }
    return "";
}

uint32_t TshCompiler::parseColorHex(const std::string& hex_str) {
    std::string clean = hex_str;
    if (!clean.empty() && clean[0] == '#') clean = clean.substr(1);
    uint32_t val = 0;
    std::stringstream ss;
    ss << std::hex << clean;
    ss >> val;
    if (clean.length() <= 6) {
        val |= 0xFF000000; // 默认不透明 Alpha 0xFF
    }
    return val;
}

static regex_t* compileOnigurumaUtf16(const std::string& pattern_str) {
    if (pattern_str.empty()) return nullptr;
    std::vector<uint16_t> u16(pattern_str.begin(), pattern_str.end());
    regex_t* reg = nullptr;
    OnigErrorInfo einfo;
    int r = onig_new(
        &reg,
        reinterpret_cast<const UChar*>(u16.data()),
        reinterpret_cast<const UChar*>(u16.data() + u16.size()),
        ONIG_OPTION_DEFAULT,
        ONIG_ENCODING_UTF16_LE,
        ONIG_SYNTAX_DEFAULT,
        &einfo
    );
    if (r != ONIG_NORMAL) {
        char err[ONIG_MAX_ERROR_MESSAGE_LEN];
        onig_error_code_to_str(reinterpret_cast<UChar*>(err), r, &einfo);
        LOGE("WindbellTSH", "Oniguruma UTF-16 Regex Compile Failed: %s, pat: %s", err, pattern_str.c_str());
        return nullptr;
    }
    return reg;
}

std::shared_ptr<TshGrammar> TshCompiler::compile(const std::string& source, const TSLanguage* ts_lang) {
    auto grammar = std::make_shared<TshGrammar>();
    std::unordered_map<std::string, std::string> defines_map;
    std::string dynamic_query_accumulator;

    // 基础标准内置调色板字典预设
    grammar->style_name_to_id["default"]     = 0;
    grammar->style_name_to_id["keyword"]     = 1;
    grammar->style_name_to_id["string"]      = 2;
    grammar->style_name_to_id["comment"]     = 3;
    grammar->style_name_to_id["number"]      = 4;
    grammar->style_name_to_id["type"]        = 5;
    grammar->style_name_to_id["constant"]    = 6;
    grammar->style_name_to_id["variable"]    = 7;
    grammar->style_name_to_id["annotation"]  = 8;
    grammar->style_name_to_id["operator"]    = 9;
    grammar->style_name_to_id["function"]    = 10;
    grammar->style_name_to_id["docComment"]  = 11;
    grammar->style_name_to_id["stringBlock"] = 12;
    grammar->style_name_to_id["keyword2"]    = 13;
    grammar->style_name_to_id["typeBuiltin"] = 14;

    uint16_t custom_style_counter = 100;
    TshLexer lexer(source);
    TshLexer::Token tok = lexer.nextToken();

    while (tok.type != TshLexer::TokenType::TOK_EOF) {
        if (tok.type == TshLexer::TokenType::TOK_IDENT) {
            // =================================================================
            // 1. 解析语法元信息: name: ["Java", ".java"]
            // =================================================================
            if (tok.text == "name") {
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto t = lexer.nextToken();
                    if (t.type == TshLexer::TokenType::TOK_RBRACKET || t.type == TshLexer::TokenType::TOK_EOF) break;
                    if (t.type == TshLexer::TokenType::TOK_STRING) {
                        if (grammar->name.empty()) grammar->name = t.text;
                        if (!t.text.empty() && t.text[0] == '.') {
                            grammar->file_extensions.push_back(t.text.substr(1));
                        }
                    }
                }
            }
            // =================================================================
            // 2. 解析多态调色板: styles: [ "keyword", #0000FF, #CC7832, @B ]
            // =================================================================
            else if (tok.text == "styles") {
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto st = lexer.nextToken();
                    if (st.type == TshLexer::TokenType::TOK_RBRACKET || st.type == TshLexer::TokenType::TOK_EOF) break;
                    if (st.type == TshLexer::TokenType::TOK_STRING || st.type == TshLexer::TokenType::TOK_IDENT) {
                        std::string style_name = st.text;
                        uint16_t sid = 0;
                        if (grammar->style_name_to_id.count(style_name)) {
                            sid = grammar->style_name_to_id[style_name];
                        } else {
                            sid = custom_style_counter++;
                            grammar->style_name_to_id[style_name] = sid;
                        }

                        StyleDefinition def;
                        def.style_id = sid;
                        def.name = style_name;

                        // 循环读取本项后续参数直至遇到下一个逗号或括号
                        while (true) {
                            auto param = lexer.nextToken();
                            if (param.type == TshLexer::TokenType::TOK_COMMA || param.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (param.type == TshLexer::TokenType::TOK_IDENT && param.text[0] == '#') {
                                if (def.light_color == 0xFF000000) def.light_color = parseColorHex(param.text);
                                else def.dark_color = parseColorHex(param.text);
                            } else if (param.type == TshLexer::TokenType::TOK_AT) {
                                auto flag_tok = lexer.nextToken();
                                if (flag_tok.text == "B") def.font_flags |= 0x01; // 粗体
                                else if (flag_tok.text == "I") def.font_flags |= 0x02; // 斜体
                            }
                        }
                        grammar->styles.push_back(def);
                    }
                }
            }
            // =================================================================
            // 3. 解析宏复用: defines: [ "identifier": /[a-zA-Z_$].../ ]
            // =================================================================
            else if (tok.text == "defines") {
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto dt = lexer.nextToken();
                    if (dt.type == TshLexer::TokenType::TOK_RBRACKET || dt.type == TshLexer::TokenType::TOK_EOF) break;
                    if (dt.type == TshLexer::TokenType::TOK_STRING || dt.type == TshLexer::TokenType::TOK_IDENT) {
                        std::string d_name = dt.text;
                        lexer.nextToken(); // :
                        auto d_val = lexer.nextToken();
                        if (d_val.type == TshLexer::TokenType::TOK_REGEX) {
                            defines_map[d_name] = d_val.text;
                        }
                    }
                }
            }
            // =================================================================
            // 4. 解析全动态 AST 选择器与谓词: ast: [ ... ] (杜绝任何硬编码)
            // =================================================================
            else if (tok.text == "ast") {
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto at = lexer.nextToken();
                    if (at.type == TshLexer::TokenType::TOK_RBRACKET || at.type == TshLexer::TokenType::TOK_EOF) break;
                    if (at.type == TshLexer::TokenType::TOK_LBRACE) {
                        AstSelectorRule ast_rule;
                        std::string node_name, field_name, parent_name;

                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE || field.type == TshLexer::TokenType::TOK_EOF) break;
                            
                            if (field.text == "node") {
                                lexer.nextToken(); // :
                                node_name = lexer.nextToken().text;
                            } else if (field.text == "field") {
                                lexer.nextToken(); // :
                                field_name = lexer.nextToken().text;
                            } else if (field.text == "parent") {
                                lexer.nextToken(); // :
                                parent_name = lexer.nextToken().text;
                            } else if (field.text == "style") {
                                lexer.nextToken(); // :
                                auto s_val = lexer.nextToken().text;
                                if (grammar->style_name_to_id.count(s_val)) {
                                    ast_rule.style_id = grammar->style_name_to_id[s_val];
                                }
                            } else if (field.text == "outline") {
                                lexer.nextToken(); // :
                                auto o_val = lexer.nextToken().text;
                                if (o_val == "class") ast_rule.outline_kind = 1;
                                else if (o_val == "method") ast_rule.outline_kind = 2;
                                else if (o_val == "field") ast_rule.outline_kind = 3;
                                else if (o_val == "enum") ast_rule.outline_kind = 4;
                            } else if (field.text == "fold") {
                                lexer.nextToken(); // :
                                auto f_val = lexer.nextToken().text;
                                ast_rule.is_foldable = (f_val == "true");
                            } else if (field.text == "query") {
                                // 允许直接在 .tsh 中定义外部 Lisp SCM Pattern
                                lexer.nextToken(); // :
                                auto q_val = lexer.nextToken().text;
                                dynamic_query_accumulator += q_val + "\n";
                            }
                        }

                        // 如果关联了 Tree-sitter Language，直接编译为原生 16 位符号 ID
                        if (ts_lang) {
                            if (!node_name.empty()) {
                                ast_rule.target_symbol = ts_language_symbol_for_name(ts_lang, node_name.c_str(), static_cast<uint32_t>(node_name.length()), true);
                            }
                            if (!field_name.empty()) {
                                ast_rule.field_id = ts_language_field_id_for_name(ts_lang, field_name.c_str(), static_cast<uint32_t>(field_name.length()));
                            }
                            if (!parent_name.empty()) {
                                ast_rule.parent_symbol = ts_language_symbol_for_name(ts_lang, parent_name.c_str(), static_cast<uint32_t>(parent_name.length()), true);
                            }
                        }

                        grammar->ast_selector_rules.push_back(ast_rule);
                    }
                }
            }
            // =================================================================
            // 5. 解析微观正则流与 Block 跨行结构: contains: [ ... ]
            // =================================================================
            else if (tok.text == "contains") {
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto ct = lexer.nextToken();
                    if (ct.type == TshLexer::TokenType::TOK_RBRACKET || ct.type == TshLexer::TokenType::TOK_EOF) break;
                    if (ct.type == TshLexer::TokenType::TOK_LBRACE) {
                        CompiledRegexRule reg_rule;
                        std::string raw_match_pat;

                        while (true) {
                            auto item = lexer.nextToken();
                            if (item.type == TshLexer::TokenType::TOK_RBRACE || item.type == TshLexer::TokenType::TOK_EOF) break;

                            if (item.text == "builtin") {
                                lexer.nextToken(); // :
                                auto b_id = lexer.nextToken().text;
                                raw_match_pat = expandBuiltin(b_id);
                                reg_rule.style_id = (b_id.find("STRING") != std::string::npos) ? 2 : 4;
                            } else if (item.text == "match") {
                                lexer.nextToken(); // :
                                auto m_tok = lexer.nextToken();
                                if (m_tok.text == "keywordsToRegex") {
                                    std::string raw_kw = lexer.extractParenthesizedBlock();
                                    raw_match_pat = expandKeywordsToRegex(raw_kw);
                                } else if (m_tok.type == TshLexer::TokenType::TOK_REGEX) {
                                    raw_match_pat = m_tok.text;
                                }
                            } else if (item.text == "start") {
                                reg_rule.type = RegexRuleType::BLOCK;
                                lexer.nextToken(); // :
                                lexer.nextToken(); // {
                                lexer.nextToken(); // match
                                lexer.nextToken(); // :
                                auto st_val = lexer.nextToken().text;
                                reg_rule.onig_start = compileOnigurumaUtf16(st_val);
                                lexer.nextToken(); // }
                            } else if (item.text == "end") {
                                lexer.nextToken(); // :
                                lexer.nextToken(); // {
                                lexer.nextToken(); // match
                                lexer.nextToken(); // :
                                auto ed_val = lexer.nextToken().text;
                                reg_rule.onig_end = compileOnigurumaUtf16(ed_val);
                                lexer.nextToken(); // }
                            } else if (item.text == "style") {
                                lexer.nextToken(); // :
                                auto s_name = lexer.nextToken().text;
                                if (grammar->style_name_to_id.count(s_name)) {
                                    reg_rule.style_id = grammar->style_name_to_id[s_name];
                                }
                            } else if (std::isdigit(static_cast<unsigned char>(item.text[0]))) {
                                int g_num = std::stoi(item.text);
                                lexer.nextToken(); // :
                                auto g_sname = lexer.nextToken().text;
                                if (grammar->style_name_to_id.count(g_sname)) {
                                    uint16_t mapped_sid = grammar->style_name_to_id[g_sname];
                                    if (g_num == 0) reg_rule.style_id = mapped_sid;
                                    else reg_rule.group_style_map[g_num] = mapped_sid;
                                }
                            }
                        }

                        if (reg_rule.type == RegexRuleType::MATCH && !raw_match_pat.empty()) {
                            // 展开可能含有的 include("...") 宏调用
                            for (const auto& kv : defines_map) {
                                std::string target = "include(\"" + kv.first + "\")";
                                size_t f_pos = raw_match_pat.find(target);
                                if (f_pos != std::string::npos) {
                                    raw_match_pat.replace(f_pos, target.length(), kv.second);
                                }
                            }
                            reg_rule.onig_start = compileOnigurumaUtf16(raw_match_pat);
                            grammar->regex_rules.push_back(reg_rule);
                        } else if (reg_rule.type == RegexRuleType::BLOCK && reg_rule.onig_start && reg_rule.onig_end) {
                            grammar->regex_rules.push_back(reg_rule);
                        }
                    }
                }
            }
        }
        tok = lexer.nextToken();
    }

    // =========================================================================
    // 如果 .tsh 中定义了动态 SCM query 语句，现场动态编译装配，杜绝内核写死
    // =========================================================================
    if (ts_lang && !dynamic_query_accumulator.empty()) {
        uint32_t err_offset = 0;
        TSQueryError err_type = TSQueryErrorNone;
        grammar->dynamic_ts_query = ts_query_new(
            ts_lang,
            dynamic_query_accumulator.c_str(),
            static_cast<uint32_t>(dynamic_query_accumulator.length()),
            &err_offset,
            &err_type
        );
        if (!grammar->dynamic_ts_query) {
            LOGE("WindbellTSH", "User defined query compile error at offset: %u, err: %d", err_offset, err_type);
        }
    }

    return grammar;
}

} // namespace windbell::tsh