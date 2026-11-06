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
#include <functional>
#include "ts__log.h"

namespace windbell::tsh {

// 内部高性能 DSL 词法分析器（支持 JSON5、嵌套块、注释过滤、正则解析）
class TshLexer {
public:
    enum class TokenType {
        TOK_EOF,
        TOK_IDENT,
        TOK_NUMBER,
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

    void putBack(Token token) {
        pending_ = std::move(token);
        has_pending_ = true;
    }

    Token nextToken() {
        if (has_pending_) {
            has_pending_ = false;
            return std::move(pending_);
        }
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

        if (std::isdigit(static_cast<unsigned char>(c))) {
            std::string number;
            while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                number += src_[cursor_++];
            }
            return {TokenType::TOK_NUMBER, number};
        }

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
    Token pending_{TokenType::TOK_EOF, ""};
    bool has_pending_ = false;
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

uint32_t TshCompiler::parseColorHex(const std::string& hex_str) {
    std::string clean = hex_str;
    if (!clean.empty() && clean.front() == '#') clean.erase(clean.begin());

    // Normalize CSS-style RGB/RGBA and Android-style ARGB into AARRGGBB.
    if (clean.size() == 3 || clean.size() == 4) {
        std::string expanded;
        expanded.reserve(clean.size() * 2);
        for (char ch : clean) {
            expanded.push_back(ch);
            expanded.push_back(ch);
        }
        if (expanded.size() == 6) {
            clean = "FF" + expanded;
        } else {
            // CSS #RGBA becomes canonical AARRGGBB.
            clean = expanded.substr(6, 2) + expanded.substr(0, 6);
        }
    } else if (clean.size() == 6) {
        clean.insert(0, "FF");
    } else if (clean.size() != 8) {
        return 0;
    }

    uint32_t value = 0;
    std::stringstream stream;
    stream << std::hex << clean;
    stream >> value;
    if (stream.fail() || !stream.eof()) return 0;
    return value;
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
    auto ensureStyleId = [&](const std::string& name) -> uint16_t {
        auto it = grammar->style_name_to_id.find(name);
        if (it != grammar->style_name_to_id.end()) return it->second;
        const uint16_t id = global_style_counter++;
        grammar->style_name_to_id.emplace(name, id);
        return id;
    };
    TshLexer lexer(source);
    uint32_t regex_declaration_order = 0;
    uint32_t ast_declaration_order = 0;

    std::function<bool(CompiledRegexRule&)> parseRegexRule;
    parseRegexRule = [&](CompiledRegexRule& rule) -> bool {
        std::string raw_match_pattern;
        while (true) {
            auto item = lexer.nextToken();
            if (item.type == TshLexer::TokenType::TOK_RBRACE) break;
            if (item.type == TshLexer::TokenType::TOK_EOF) return false;
            if (item.type == TshLexer::TokenType::TOK_COMMA) continue;

            if (item.type == TshLexer::TokenType::TOK_NUMBER) {
                const int group_index = std::stoi(item.text);
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto style_name = lexer.nextToken();
                rule.group_style_map[group_index] = ensureStyleId(style_name.text);
                continue;
            }

            if (item.text == "match") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto pattern = lexer.nextToken();
                if (pattern.text == "keywordsToRegex") {
                    raw_match_pattern = expandKeywordsToRegex(lexer.extractParenthesizedBlock());
                } else if (pattern.text == "include") {
                    std::string include_name = lexer.extractParenthesizedBlock();
                    include_name.erase(std::remove_if(include_name.begin(), include_name.end(),
                        [](unsigned char ch) { return std::isspace(ch) || ch == '"' || ch == '\''; }),
                        include_name.end());
                    auto definition = defines_map.find(include_name);
                    if (definition == defines_map.end()) return false;
                    raw_match_pattern = definition->second;
                } else if (pattern.type == TshLexer::TokenType::TOK_REGEX ||
                           pattern.type == TshLexer::TokenType::TOK_STRING) {
                    raw_match_pattern = pattern.text;
                } else {
                    return false;
                }
            } else if (item.text == "builtin") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto name = lexer.nextToken();
                auto definition = defines_map.find(name.text);
                if (definition == defines_map.end()) return false;
                raw_match_pattern = definition->second;
            } else if (item.text == "start" || item.text == "begin" || item.text == "end") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return false;
                auto match_key = lexer.nextToken();
                if (match_key.text != "match") return false;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto pattern = lexer.nextToken();
                if (pattern.type != TshLexer::TokenType::TOK_REGEX &&
                    pattern.type != TshLexer::TokenType::TOK_STRING) return false;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_RBRACE) return false;

                rule.type = RegexRuleType::BLOCK;
                regex_t* compiled = compileOnigurumaUtf16(pattern.text);
                if (!compiled) return false;
                if (item.text == "end") rule.onig_end = compiled;
                else rule.onig_start = compiled;
            } else if (item.text == "style") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto style_name = lexer.nextToken();
                rule.style_id = ensureStyleId(style_name.text);
                rule.has_style = true;
            } else if (item.text == "id") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                rule.id = lexer.nextToken().text;
            } else if (item.text == "priority") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_NUMBER) return false;
                rule.priority = std::stoi(value.text);
            } else if (item.text == "enabled") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                rule.enabled = lexer.nextToken().text != "false";
            } else if (item.text == "overlap") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                rule.allow_overlap = lexer.nextToken().text == "allow";
            } else if (item.text == "contains" || item.text == "patterns" || item.text == "captures") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto open = lexer.nextToken();
                const bool capture_block = item.text == "captures";
                const auto expected_open = capture_block ?
                    TshLexer::TokenType::TOK_LBRACE : TshLexer::TokenType::TOK_LBRACKET;
                const auto expected_close = capture_block ?
                    TshLexer::TokenType::TOK_RBRACE : TshLexer::TokenType::TOK_RBRACKET;
                if (open.type != expected_open) return false;

                while (true) {
                    auto child_token = lexer.nextToken();
                    if (child_token.type == expected_close) break;
                    if (child_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (child_token.type == TshLexer::TokenType::TOK_EOF) return false;
                    if (capture_block) {
                        if (child_token.type != TshLexer::TokenType::TOK_NUMBER) return false;
                        const int group_index = std::stoi(child_token.text);
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                        auto style_name = lexer.nextToken();
                        rule.group_style_map[group_index] = ensureStyleId(style_name.text);
                    } else {
                        if (child_token.type != TshLexer::TokenType::TOK_LBRACE) return false;
                        CompiledRegexRule nested;
                        nested.declaration_order = regex_declaration_order++;
                        if (!parseRegexRule(nested)) return false;
                        rule.nested_rules.push_back(std::move(nested));
                    }
                }
            }
        }

        if (!raw_match_pattern.empty()) {
            for (const auto& definition : defines_map) {
                const std::string include_call = "include(\"" + definition.first + "\")";
                size_t position = 0;
                while ((position = raw_match_pattern.find(include_call, position)) != std::string::npos) {
                    raw_match_pattern.replace(position, include_call.size(), definition.second);
                    position += definition.second.size();
                }
            }
            rule.onig_start = compileOnigurumaUtf16(raw_match_pattern);
            if (!rule.onig_start) return false;
        }

        if (rule.type == RegexRuleType::BLOCK) {
            return rule.has_style && rule.onig_start && rule.onig_end;
        }
        return rule.onig_start || !rule.group_style_map.empty();
    };

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
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                while (true) {
                    auto scheme_name_tok = lexer.nextToken();
                    if (scheme_name_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (scheme_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (scheme_name_tok.type != TshLexer::TokenType::TOK_IDENT &&
                        scheme_name_tok.type != TshLexer::TokenType::TOK_STRING) return nullptr;

                    const std::string scheme_name = scheme_name_tok.text;
                    // A real schemes block takes precedence over the legacy
                    // compatibility palette named "default".
                    if (grammar->default_scheme_name.empty() ||
                        (grammar->default_scheme_name == "default" &&
                         grammar->schemes.find("default") != grammar->schemes.end())) {
                        grammar->default_scheme_name = scheme_name;
                    }

                    auto scheme_open = lexer.nextToken();
                    if (scheme_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                    ColorScheme scheme;
                    scheme.name = scheme_name;
                    while (true) {
                        auto mode_tok = lexer.nextToken();
                        if (mode_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                        if (mode_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        if (mode_tok.text == "ui") {
                            auto ui_open = lexer.nextToken();
                            if (ui_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            while (true) {
                                auto token_name = lexer.nextToken();
                                if (token_name.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (token_name.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (token_name.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                auto separator = lexer.nextToken();
                                if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                auto value = lexer.nextToken();
                                if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                scheme.ui_colors[token_name.text] = parseColorHex(value.text);
                            }
                            continue;
                        }

                        const bool is_light = mode_tok.text == "light";
                        const bool is_dark = mode_tok.text == "dark";
                        if (!is_light && !is_dark) return nullptr;

                        auto list_open = lexer.nextToken();
                        if (list_open.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;

                        while (true) {
                            auto item_tok = lexer.nextToken();
                            if (item_tok.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (item_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (item_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                            const std::string symbol_name = item_tok.text;
                            auto separator = lexer.nextToken();
                            if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto color_tok = lexer.nextToken();
                            if (color_tok.type != TshLexer::TokenType::TOK_IDENT) return nullptr;

                            uint16_t sid = 0;
                            auto style_it = grammar->style_name_to_id.find(symbol_name);
                            if (style_it == grammar->style_name_to_id.end()) {
                                sid = global_style_counter++;
                                grammar->style_name_to_id.emplace(symbol_name, sid);
                            } else {
                                sid = style_it->second;
                            }

                            const uint32_t color_val = parseColorHex(color_tok.text);
                            uint16_t flags = 0;
                            while (true) {
                                auto modifier = lexer.nextToken();
                                if (modifier.type == TshLexer::TokenType::TOK_COMMA) break;
                                if (modifier.type == TshLexer::TokenType::TOK_RBRACKET) {
                                    lexer.putBack(std::move(modifier));
                                    break;
                                }
                                if (modifier.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                if (modifier.type == TshLexer::TokenType::TOK_AT) {
                                    auto flag_tok = lexer.nextToken();
                                    if (flag_tok.text == "B" || flag_tok.text == "bold") flags |= 0x01;
                                    else if (flag_tok.text == "I" || flag_tok.text == "italic") flags |= 0x02;
                                    else if (flag_tok.text == "U" || flag_tok.text == "underline") flags |= 0x04;
                                    else if (flag_tok.text == "S" || flag_tok.text == "strike" || flag_tok.text == "strikethrough") flags |= 0x08;
                                    continue;
                                }
                                // Commas are optional: the next identifier begins
                                // another style entry and must remain in the stream.
                                lexer.putBack(std::move(modifier));
                                break;
                            }

                            auto& color_entry = scheme.colors[sid];
                            if (is_light) {
                                color_entry.light_color = color_val;
                                color_entry.has_light_color = true;
                            } else {
                                color_entry.dark_color = color_val;
                                color_entry.has_dark_color = true;
                            }
                            if (is_light) color_entry.light_font_flags |= flags;
                            else color_entry.dark_font_flags |= flags;
                        }
                    }
                    grammar->schemes[scheme_name] = std::move(scheme);
                }
            }

            // DSL default rendering mode: false = light/day, true = dark/night.
            else if (tok.text == "isDark") {
                auto separator = lexer.nextToken();
                if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.text == "true") grammar->default_is_dark = true;
                else if (value.text == "false") grammar->default_is_dark = false;
                else return nullptr;
            }

            // =================================================================
            // 3. Semantic style declarations. Supports v2 object syntax and the
            // legacy [name, lightColor, darkColor, @modifier] tuple syntax.
            // =================================================================
            else if (tok.text == "styles") {
                auto styles_open = lexer.nextToken();
                if (styles_open.type == TshLexer::TokenType::TOK_COLON) styles_open = lexer.nextToken();

                if (styles_open.type == TshLexer::TokenType::TOK_LBRACE) {
                    while (true) {
                        auto style_name_tok = lexer.nextToken();
                        if (style_name_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        const std::string style_name = style_name_tok.text;
                        const uint16_t style_id = ensureStyleId(style_name);
                        auto style_open = lexer.nextToken();
                        if (style_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                        TshStyle definition;
                        definition.name = style_name;
                        definition.style_id = style_id;
                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (field.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                            auto separator = lexer.nextToken();
                            if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            if (field.text == "tags") {
                                auto array_open = lexer.nextToken();
                                if (array_open.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                while (true) {
                                    auto tag = lexer.nextToken();
                                    if (tag.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                    if (tag.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                    if (tag.type != TshLexer::TokenType::TOK_COMMA) {
                                        definition.tags.push_back(tag.text);
                                    }
                                }
                            } else {
                                auto value = lexer.nextToken();
                                if (field.text == "scope") definition.scope = value.text;
                                else if (field.text == "description") definition.description = value.text;
                                else if (field.text == "parent") definition.parent = value.text;
                            }
                        }
                        grammar->style_definitions[style_name] = std::move(definition);
                    }
                } else if (styles_open.type == TshLexer::TokenType::TOK_LBRACKET) {
                    ColorScheme legacy_scheme;
                    legacy_scheme.name = "default";
                    if (grammar->default_scheme_name.empty()) grammar->default_scheme_name = "default";

                    while (true) {
                        auto style_name_tok = lexer.nextToken();
                        if (style_name_tok.type == TshLexer::TokenType::TOK_RBRACKET) break;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        const std::string style_name = style_name_tok.text;
                        const uint16_t style_id = ensureStyleId(style_name);
                        auto light_sep = lexer.nextToken();
                        if (light_sep.type != TshLexer::TokenType::TOK_COMMA) return nullptr;
                        auto light_tok = lexer.nextToken();
                        if (light_tok.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                        auto dark_sep = lexer.nextToken();
                        if (dark_sep.type != TshLexer::TokenType::TOK_COMMA) return nullptr;
                        auto dark_tok = lexer.nextToken();
                        if (dark_tok.type != TshLexer::TokenType::TOK_IDENT) return nullptr;

                        SchemeColor color;
                        color.light_color = parseColorHex(light_tok.text);
                        color.dark_color = parseColorHex(dark_tok.text);
                        color.has_light_color = true;
                        color.has_dark_color = true;

                        auto modifier = lexer.nextToken();
                        if (modifier.type == TshLexer::TokenType::TOK_COMMA) {
                            modifier = lexer.nextToken();
                        }
                        while (modifier.type == TshLexer::TokenType::TOK_AT) {
                            auto flag = lexer.nextToken();
                            if (flag.text == "B" || flag.text == "bold") {
                                color.light_font_flags |= 0x01;
                                color.dark_font_flags |= 0x01;
                            }
                            else if (flag.text == "I" || flag.text == "italic") {
                                color.light_font_flags |= 0x02;
                                color.dark_font_flags |= 0x02;
                            } else if (flag.text == "U" || flag.text == "underline") {
                                color.light_font_flags |= 0x04;
                                color.dark_font_flags |= 0x04;
                            } else if (flag.text == "S" || flag.text == "strike" || flag.text == "strikethrough") {
                                color.light_font_flags |= 0x08;
                                color.dark_font_flags |= 0x08;
                            }
                            modifier = lexer.nextToken();
                            if (modifier.type == TshLexer::TokenType::TOK_COMMA) {
                                modifier = lexer.nextToken();
                            }
                        }
                        if (modifier.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                        // Leave the next tuple or closing bracket for the outer loop.
                        lexer.putBack(std::move(modifier));
                        legacy_scheme.colors[style_id] = color;
                    }
                    grammar->schemes[legacy_scheme.name] = std::move(legacy_scheme);
                } else {
                    return nullptr;
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
                        ast_rule.declaration_order = ast_declaration_order++;
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
                            } else if (field.text == "id") {
                                lexer.nextToken();
                                ast_rule.id = lexer.nextToken().text;
                            } else if (field.text == "priority") {
                                lexer.nextToken();
                                auto value = lexer.nextToken();
                                if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                ast_rule.priority = std::stoi(value.text);
                            } else if (field.text == "enabled") {
                                lexer.nextToken();
                                ast_rule.enabled = lexer.nextToken().text != "false";
                            } else if (field.text == "overlap") {
                                lexer.nextToken();
                                ast_rule.allow_overlap = lexer.nextToken().text == "allow";
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
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto rule_token = lexer.nextToken();
                    if (rule_token.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (rule_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (rule_token.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (rule_token.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                    CompiledRegexRule rule;
                    rule.declaration_order = regex_declaration_order++;
                    if (!parseRegexRule(rule)) return nullptr;
                    grammar->regex_rules.push_back(std::move(rule));
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
        auto definition_it = grammar->style_definitions.find(entry.first);
        if (definition_it != grammar->style_definitions.end()) {
            style.scope = definition_it->second.scope;
            style.tags = definition_it->second.tags;
            style.description = definition_it->second.description;
            style.parent = definition_it->second.parent;
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