/*
 * Copyright © 2026 com.zerostudio.idea.treesitter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
 * 纯 C++20 词法分析与递归下降 DSL 编译器（完全解耦、零硬编码、多主题方案驱动）
 */

#include "WindbellTshCompiler.h"
#include <cctype>
#include <sstream>
#include <algorithm>
#include <vector>
#include "ts__log.h"

namespace windbell::tsh {

// 内部高性能 DSL 词法分析器（支持 JSON5、嵌套块、注释过滤、正则解析）
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

        // 解析字符串 (支持单双引号)
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

        // 解析标识符、宏定义、颜色（如 keywordsToRegex, #FF0000, @B）
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

        cursor_++;
        return nextToken();
    }

    // 提取括号内的原始内容（用于 keywordsToRegex("...") 提取）
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
        val |= 0xFF000000; // 默认不透明 Alpha (0xFF)
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

    uint16_t global_style_counter = 0;
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
            // 2. 解析多主题方案: schemes { name { light [...], dark [...] } }
            // =================================================================
            else if (tok.text == "schemes") {
                lexer.nextToken(); // :
                lexer.nextToken(); // {
                while (true) {
                    auto scheme_name_tok = lexer.nextToken();
                    if (scheme_name_tok.type == TshLexer::TokenType::TOK_RBRACE || scheme_name_tok.type == TshLexer::TokenType::TOK_EOF) break;
                    
                    std::string scheme_name = scheme_name_tok.text;
                    if (grammar->default_scheme_name.empty()) {
                        grammar->default_scheme_name = scheme_name;
                    }

                    ColorScheme scheme;
                    scheme.name = scheme_name;

                    lexer.nextToken(); // { (进入 scheme 体)
                    while (true) {
                        auto mode_tok = lexer.nextToken(); // "light" 或 "dark"
                        if (mode_tok.type == TshLexer::TokenType::TOK_RBRACE || mode_tok.type == TshLexer::TokenType::TOK_EOF) break;
                        
                        bool is_light = (mode_tok.text == "light");
                        lexer.nextToken(); // [
                        while (true) {
                            auto item_tok = lexer.nextToken(); // 符号名，如 keyword, string
                            if (item_tok.type == TshLexer::TokenType::TOK_RBRACKET || item_tok.type == TshLexer::TokenType::TOK_EOF) break;
                            
                            std::string symbol_name = item_tok.text;
                            uint16_t sid = 0;
                            if (grammar->style_name_to_id.count(symbol_name)) {
                                sid = grammar->style_name_to_id[symbol_name];
                            } else {
                                sid = global_style_counter++;
                                grammar->style_name_to_id[symbol_name] = sid;
                            }

                            auto color_tok = lexer.nextToken(); // 十六进制颜色 #FF007F
                            uint32_t color_val = parseColorHex(color_tok.text);

                            uint16_t flags = 0;
                            // 扫描后续是否有修饰符（如 @B 粗体, @I 斜体）
                            while (true) {
                                auto next_t = lexer.nextToken();
                                if (next_t.type == TshLexer::TokenType::TOK_COMMA || next_t.type == TshLexer::TokenType::TOK_RBRACKET) {
                                    break;
                                }
                                if (next_t.type == TshLexer::TokenType::TOK_AT) {
                                    auto f_tok = lexer.nextToken();
                                    if (f_tok.text == "B") flags |= 0x01; // 粗体
                                    else if (f_tok.text == "I") flags |= 0x02; // 斜体
                                }
                            }

                            auto& color_entry = scheme.colors[sid];
                            if (is_light) {
                                color_entry.light_color = color_val;
                                color_entry.has_light_color = true;
                            } else {
                                color_entry.dark_color = color_val;
                                color_entry.has_dark_color = true;
                            }
                            color_entry.font_flags = flags;
                        }
                        lexer.nextToken(); // } (闭合 light/dark)
                    }
                    grammar->schemes[scheme_name] = scheme;
                }
            }

            // =================================================================
            // 3. 解析宏复用: defines: [ "ident": /[a-zA-Z_$].../ ]
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
            // 4. 解析宏观 Tree-sitter AST 选择器: ast: [ ... ]
            // =================================================================
            else if (tok.text == "ast") {
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto at = lexer.nextToken();
                    if (at.type == TshLexer::TokenType::TOK_RBRACKET || at.type == TshLexer::TokenType::TOK_EOF) break;
                    if (at.type == TshLexer::TokenType::TOK_LBRACE) {
                        AstSelectorRule ast_rule;
                        std::string node_name, field_name, parent_name, style_bind_name;

                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE || field.type == TshLexer::TokenType::TOK_EOF) break;
                            
                            if (field.text == "node") {
                                lexer.nextToken();
                                node_name = lexer.nextToken().text;
                            } else if (field.text == "field") {
                                lexer.nextToken();
                                field_name = lexer.nextToken().text;
                            } else if (field.text == "parent") {
                                lexer.nextToken();
                                parent_name = lexer.nextToken().text;
                            } else if (field.text == "style") {
                                lexer.nextToken();
                                style_bind_name = lexer.nextToken().text;
                            } else if (field.text == "query") {
                                lexer.nextToken();
                                dynamic_query_accumulator += lexer.nextToken().text + "\n";
                            }
                        }

                        if (!style_bind_name.empty()) {
                            auto style_it = grammar->style_name_to_id.find(style_bind_name);
                            if (style_it == grammar->style_name_to_id.end()) {
                                const uint16_t sid = global_style_counter++;
                                style_it = grammar->style_name_to_id.emplace(style_bind_name, sid).first;
                            }
                            ast_rule.style_id = style_it->second;
                            ast_rule.has_style = true;
                        }

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
                                lexer.nextToken();
                                auto b_id = lexer.nextToken().text;
                                raw_match_pat = expandBuiltin(b_id);
                            } else if (item.text == "match") {
                                lexer.nextToken();
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
                                lexer.nextToken();
                                auto s_name = lexer.nextToken().text;
                                if (grammar->style_name_to_id.count(s_name)) {
                                    reg_rule.style_id = grammar->style_name_to_id[s_name];
                                } else {
                                    uint16_t sid = global_style_counter++;
                                    grammar->style_name_to_id[s_name] = sid;
                                    reg_rule.style_id = sid;
                                }
                            }
                        }

                        if (reg_rule.type == RegexRuleType::MATCH && !raw_match_pat.empty()) {
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

    // Materialize the symbol catalog in stable ID order. A missing scheme color
    // remains zero/absent; the renderer must not synthesize black or white.
    grammar->styles.resize(grammar->style_name_to_id.size());
    for (const auto& entry : grammar->style_name_to_id) {
        TshStyle style;
        style.name = entry.first;
        style.style_id = entry.second;
        auto scheme_it = grammar->schemes.find(grammar->default_scheme_name);
        if (scheme_it != grammar->schemes.end()) {
            auto color_it = scheme_it->second.colors.find(style.style_id);
            if (color_it != scheme_it->second.colors.end()) {
                const SchemeColor& color = color_it->second;
                if (color.has_light_color) style.light_color = color.light_color;
                if (color.has_dark_color) style.dark_color = color.dark_color;
                style.font_flags = color.font_flags;
            }
        }
        if (style.style_id >= grammar->styles.size()) {
            grammar->styles.resize(static_cast<size_t>(style.style_id) + 1);
        }
        grammar->styles[style.style_id] = std::move(style);
    }

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
    }

    return grammar;
}

} // namespace windbell::tsh