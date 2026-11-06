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
#include <cmath>
#include "ts__log.h"

namespace windbell::tsh {

static thread_local uint32_t g_tsh_last_source_offset = 0;

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
        g_tsh_last_source_offset = static_cast<uint32_t>(cursor_);
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

        if (std::isdigit(static_cast<unsigned char>(c)) ||
            ((c == '-' || c == '+') && cursor_ + 1 < src_.size() &&
             std::isdigit(static_cast<unsigned char>(src_[cursor_ + 1])))) {
            std::string number;
            if (src_[cursor_] == '-' || src_[cursor_] == '+') number += src_[cursor_++];
            while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                number += src_[cursor_++];
            }
            if (cursor_ + 1 < src_.size() && src_[cursor_] == '.' &&
                std::isdigit(static_cast<unsigned char>(src_[cursor_ + 1]))) {
                number += src_[cursor_++];
                while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                    number += src_[cursor_++];
                }
            }
            if (cursor_ < src_.size() && (src_[cursor_] == 'e' || src_[cursor_] == 'E')) {
                const size_t exponent_start = cursor_;
                std::string exponent;
                exponent += src_[cursor_++];
                if (cursor_ < src_.size() && (src_[cursor_] == '-' || src_[cursor_] == '+')) {
                    exponent += src_[cursor_++];
                }
                const size_t digits_start = cursor_;
                while (cursor_ < src_.size() && std::isdigit(static_cast<unsigned char>(src_[cursor_]))) {
                    exponent += src_[cursor_++];
                }
                if (cursor_ == digits_start) cursor_ = exponent_start;
                else number += exponent;
            }
            return {TokenType::TOK_NUMBER, number};
        }

        // 解析字符串 (支持单双引号)
        if (c == '"' || c == '\'' || c == '`') {
            char quote = c;
            const bool raw_string = quote == '`';
            cursor_++;
            std::string content;
            while (cursor_ < src_.size() && src_[cursor_] != quote) {
                if (!raw_string && src_[cursor_] == '\\' && cursor_ + 1 < src_.size()) {
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

std::shared_ptr<TshGrammar> TshCompiler::compile(const std::string& source, const TSLanguage* ts_lang) {
    g_tsh_last_source_offset = 0;
    auto result = compileInternal(source, ts_lang);
    std::vector<TshCompileDiagnostic> diagnostics;
    if (!result) {
        const uint32_t offset = std::min<uint32_t>(
            g_tsh_last_source_offset, static_cast<uint32_t>(source.size()));
        uint32_t line = 1;
        uint32_t column = 1;
        for (uint32_t i = 0; i < offset; ++i) {
            if (source[i] == '\n') { ++line; column = 1; }
            else { ++column; }
        }
        diagnostics.push_back({
            "TSH_PARSE_OR_COMPILE_ERROR",
            "TSH compilation failed near this location. Check the current property value, delimiters, references, regex/query syntax and required fields.",
            offset, line, column
        });
    }
    {
        std::lock_guard<std::mutex> lock(diagnostic_mutex_);
        last_diagnostics_ = std::move(diagnostics);
    }
    return result;
}

std::vector<TshCompileDiagnostic> TshCompiler::getLastDiagnostics() const {
    std::lock_guard<std::mutex> lock(diagnostic_mutex_);
    return last_diagnostics_;
}

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

static bool isValidColorHex(const std::string& value) {
    std::string clean = value;
    if (!clean.empty() && clean.front() == '#') clean.erase(clean.begin());
    if (clean.size() != 3 && clean.size() != 4 &&
        clean.size() != 6 && clean.size() != 8) return false;
    return std::all_of(clean.begin(), clean.end(), [](unsigned char ch) {
        return std::isxdigit(ch) != 0;
    });
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

static bool decodePatternUtf8ToUtf16(const std::string& input, std::vector<uint16_t>& output) {
    output.clear();
    output.reserve(input.size());
    for (size_t i = 0; i < input.size();) {
        const uint8_t first = static_cast<uint8_t>(input[i++]);
        uint32_t codepoint = 0;
        size_t continuation_count = 0;
        if (first <= 0x7F) {
            codepoint = first;
        } else if ((first & 0xE0) == 0xC0) {
            codepoint = first & 0x1F;
            continuation_count = 1;
        } else if ((first & 0xF0) == 0xE0) {
            codepoint = first & 0x0F;
            continuation_count = 2;
        } else if ((first & 0xF8) == 0xF0) {
            codepoint = first & 0x07;
            continuation_count = 3;
        } else {
            return false;
        }
        if (i + continuation_count > input.size()) return false;
        for (size_t n = 0; n < continuation_count; ++n) {
            const uint8_t next = static_cast<uint8_t>(input[i++]);
            if ((next & 0xC0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        // JNI GetStringUTFChars uses modified UTF-8 for UTF-16 surrogate
        // code units. Preserve those code units while also accepting standard
        // four-byte UTF-8 supplied by source files.
        if (codepoint <= 0xFFFF) {
            if (codepoint < 0x80 && continuation_count != 0) return false;
            if (codepoint < 0x800 && continuation_count == 2) return false;
            if (codepoint < 0x10000 && continuation_count == 3) return false;
            output.push_back(static_cast<uint16_t>(codepoint));
        } else if (codepoint <= 0x10FFFF) {
            codepoint -= 0x10000;
            output.push_back(static_cast<uint16_t>(0xD800 + (codepoint >> 10)));
            output.push_back(static_cast<uint16_t>(0xDC00 + (codepoint & 0x3FF)));
        } else {
            return false;
        }
    }
    return true;
}

static regex_t* compileOnigurumaUtf16(const std::string& pattern_str, OnigOptionType options = ONIG_OPTION_DEFAULT) {
    if (pattern_str.empty()) return nullptr;
    std::vector<uint16_t> u16;
    if (!decodePatternUtf8ToUtf16(pattern_str, u16)) {
        LOGE("WindbellTSH", "Invalid UTF-8 in Oniguruma pattern: %s", pattern_str.c_str());
        return nullptr;
    }
    regex_t* reg = nullptr;
    OnigErrorInfo einfo;
    int r = onig_new(
        &reg,
        reinterpret_cast<const UChar*>(u16.data()),
        reinterpret_cast<const UChar*>(u16.data() + u16.size()),
        options,
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

std::shared_ptr<TshGrammar> TshCompiler::compileInternal(const std::string& source, const TSLanguage* ts_lang) {
    auto grammar = std::make_shared<TshGrammar>();
    std::unordered_map<std::string, std::string> defines_map;
    std::string dynamic_query_accumulator;
    std::unordered_map<std::string, bool> tree_sitter_query_ids;

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
        rule.regex_options = grammar->default_regex_options;
        std::string raw_match_pattern;
        std::string raw_start_pattern;
        std::string raw_end_pattern;
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
            } else if (item.text == "flags") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return false;
                while (true) {
                    auto flag = lexer.nextToken();
                    if (flag.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (flag.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (flag.text == "i" || flag.text == "ignoreCase") rule.regex_options |= ONIG_OPTION_IGNORECASE;
                    else if (flag.text == "m" || flag.text == "dotAll") rule.regex_options |= ONIG_OPTION_MULTILINE;
                    else if (flag.text == "s" || flag.text == "singlelineAnchors") rule.regex_options |= ONIG_OPTION_SINGLELINE;
                    else if (flag.text == "x" || flag.text == "extended") rule.regex_options |= ONIG_OPTION_EXTEND;
                    else return false;
                }
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
                if (item.text == "end") raw_end_pattern = pattern.text;
                else raw_start_pattern = pattern.text;
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
            } else if (item.text == "contains" || item.text == "patterns" ||
                       item.text == "captures" || item.text == "beginCaptures" ||
                       item.text == "startCaptures" || item.text == "endCaptures") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto open = lexer.nextToken();
                const bool capture_block = item.text == "captures" ||
                    item.text == "beginCaptures" || item.text == "startCaptures" ||
                    item.text == "endCaptures";
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
                        if (child_token.type != TshLexer::TokenType::TOK_NUMBER &&
                            child_token.type != TshLexer::TokenType::TOK_IDENT &&
                            child_token.type != TshLexer::TokenType::TOK_STRING) return false;
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                        auto style_name = lexer.nextToken();
                        auto* numeric_map = &rule.group_style_map;
                        auto* named_map = &rule.named_group_style_map;
                        if (item.text == "beginCaptures" || item.text == "startCaptures") {
                            numeric_map = &rule.start_group_style_map;
                            named_map = &rule.start_named_group_style_map;
                        } else if (item.text == "endCaptures") {
                            numeric_map = &rule.end_group_style_map;
                            named_map = &rule.end_named_group_style_map;
                        }
                        if (child_token.type == TshLexer::TokenType::TOK_NUMBER) {
                            (*numeric_map)[std::stoi(child_token.text)] = ensureStyleId(style_name.text);
                        } else {
                            (*named_map)[child_token.text] = ensureStyleId(style_name.text);
                        }
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
            rule.onig_start = compileOnigurumaUtf16(raw_match_pattern, rule.regex_options);
            if (!rule.onig_start) return false;
        }
        if (!raw_start_pattern.empty()) {
            rule.onig_start = compileOnigurumaUtf16(raw_start_pattern, rule.regex_options);
            if (!rule.onig_start) return false;
        }
        if (!raw_end_pattern.empty()) {
            rule.onig_end = compileOnigurumaUtf16(raw_end_pattern, rule.regex_options);
            if (!rule.onig_end) return false;
        }

        if (rule.type == RegexRuleType::BLOCK) {
            return (rule.has_style || !rule.start_group_style_map.empty() ||
                    !rule.start_named_group_style_map.empty() || !rule.end_group_style_map.empty() ||
                    !rule.end_named_group_style_map.empty()) && rule.onig_start && rule.onig_end;
        }
        return rule.onig_start != nullptr;
    };

    TshLexer::Token tok = lexer.nextToken();
    bool versioned_document = false;
    bool versioned_document_closed = false;
    std::unordered_map<std::string, bool> seen_root_sections;
    auto mark_root_section = [&](const std::string& name) {
        return seen_root_sections.emplace(name, true).second;
    };
    if (tok.type == TshLexer::TokenType::TOK_IDENT && tok.text == "tsh") {
        const auto version = lexer.nextToken();
        if (version.type != TshLexer::TokenType::TOK_NUMBER || version.text != "2") return nullptr;
        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
        versioned_document = true;
        tok = lexer.nextToken();
    }

    while (tok.type != TshLexer::TokenType::TOK_EOF &&
           !(versioned_document && tok.type == TshLexer::TokenType::TOK_RBRACE)) {
        if (versioned_document && tok.type == TshLexer::TokenType::TOK_EOF) break;
        if (tok.type == TshLexer::TokenType::TOK_IDENT) {

            // Legacy shorthand retained with explicit, validated semantics.
            if (tok.text == "ignoreCase") {
                if (!mark_root_section("ignoreCase")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.text == "true") grammar->default_regex_options |= ONIG_OPTION_IGNORECASE;
                else if (value.text != "false") return nullptr;
            }

            // Language-wide regex execution semantics.
            else if (tok.text == "matching") {
                if (!mark_root_section("matching")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type == TshLexer::TokenType::TOK_EOF ||
                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto value = lexer.nextToken();
                    const bool enabled = value.text == "true";
                    if (value.text != "true" && value.text != "false") return nullptr;
                    if (key.text == "caseSensitive") {
                        if (enabled) grammar->default_regex_options &= ~ONIG_OPTION_IGNORECASE;
                        else grammar->default_regex_options |= ONIG_OPTION_IGNORECASE;
                    } else if (key.text == "multiline") {
                        if (enabled) grammar->default_regex_options &= ~ONIG_OPTION_SINGLELINE;
                        else grammar->default_regex_options |= ONIG_OPTION_SINGLELINE;
                    } else if (key.text == "dotAll") {
                        if (enabled) grammar->default_regex_options |= ONIG_OPTION_MULTILINE;
                        else grammar->default_regex_options &= ~ONIG_OPTION_MULTILINE;
                    } else if (key.text == "extended") {
                        if (enabled) grammar->default_regex_options |= ONIG_OPTION_EXTEND;
                        else grammar->default_regex_options &= ~ONIG_OPTION_EXTEND;
                    } else if (key.text == "unicode") {
                        // Oniguruma is always compiled in UTF-16LE mode. Reject
                        // disabling Unicode rather than silently changing encoding.
                        if (!enabled) return nullptr;
                    } else {
                        return nullptr;
                    }
                }
            }

            // Language identity and editor/file association metadata.
            else if (tok.text == "language") {
                if (!mark_root_section("language")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                auto read_values = [&](std::vector<std::string>& values) -> bool {
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return false;
                    while (true) {
                        auto value = lexer.nextToken();
                        if (value.type == TshLexer::TokenType::TOK_RBRACKET) return true;
                        if (value.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                            value.type != TshLexer::TokenType::TOK_IDENT) return false;
                        values.push_back(value.text);
                    }
                };
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    if (key.text == "id" || key.text == "name" || key.text == "firstLine") {
                        auto value = lexer.nextToken();
                        if (key.text == "id") grammar->id = value.text;
                        else if (key.text == "name") grammar->name = value.text;
                        else if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                        else grammar->first_line_pattern = value.text;
                    } else if (key.text == "extensions") {
                        std::vector<std::string> values;
                        if (!read_values(values)) return nullptr;
                        for (auto value : values) {
                            if (!value.empty() && value.front() == '.') value.erase(value.begin());
                            if (!value.empty()) grammar->file_extensions.push_back(std::move(value));
                        }
                    } else if (key.text == "aliases") {
                        if (!read_values(grammar->aliases)) return nullptr;
                    } else if (key.text == "mimetypes" || key.text == "mimeTypes") {
                        if (!read_values(grammar->mime_types)) return nullptr;
                    } else {
                        return nullptr;
                    }
                }
            }

            // =================================================================
            // 1. 解析语法元信息: name: ["Java", ".java"]
            // =================================================================
            else if (tok.text == "name") {
                if (!mark_root_section("name")) return nullptr;
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
                if (!mark_root_section("schemes")) return nullptr;
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
                    if (grammar->schemes.find(scheme_name) != grammar->schemes.end()) return nullptr;
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

                        if (mode_tok.text == "extends") {
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto parent = lexer.nextToken();
                            if (parent.type != TshLexer::TokenType::TOK_STRING &&
                                parent.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                            scheme.extends = parent.text;
                            continue;
                        }

                        if (mode_tok.text == "ui") {
                            auto ui_open = lexer.nextToken();
                            if (ui_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            while (true) {
                                auto token_name = lexer.nextToken();
                                if (token_name.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (token_name.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (token_name.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                                if (token_name.text == "light" || token_name.text == "dark") {
                                    const bool light_mode = token_name.text == "light";
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                    while (true) {
                                        auto key = lexer.nextToken();
                                        if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                        if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                        if (key.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                        auto value = lexer.nextToken();
                                        if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        if (value.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(value.text)) return nullptr;
                                        auto& target = light_mode ? scheme.ui_light_colors : scheme.ui_dark_colors;
                                        target[key.text] = parseColorHex(value.text);
                                    }
                                } else {
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                    if (value.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(value.text)) return nullptr;
                                    scheme.ui_colors[token_name.text] = parseColorHex(value.text);
                                }
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
                            if (color_tok.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(color_tok.text)) return nullptr;

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

            else if (tok.text == "defaultScheme") {
                if (!mark_root_section("defaultScheme")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                grammar->default_scheme_name = lexer.nextToken().text;
            }

            // DSL default rendering mode: false = light/day, true = dark/night.
            else if (tok.text == "isDark") {
                if (!mark_root_section("isDark")) return nullptr;
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
                if (!mark_root_section("styles")) return nullptr;
                auto styles_open = lexer.nextToken();
                if (styles_open.type == TshLexer::TokenType::TOK_COLON) styles_open = lexer.nextToken();

                if (styles_open.type == TshLexer::TokenType::TOK_LBRACE) {
                    while (true) {
                        auto style_name_tok = lexer.nextToken();
                        if (style_name_tok.type == TshLexer::TokenType::TOK_RBRACE) break;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (style_name_tok.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                        const std::string style_name = style_name_tok.text;
                        if (grammar->style_definitions.find(style_name) != grammar->style_definitions.end()) return nullptr;
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
                            } else if (field.text == "scope" || field.text == "description" ||
                                       field.text == "parent") {
                                auto value = lexer.nextToken();
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                if (field.text == "scope") definition.scope = value.text;
                                else if (field.text == "description") definition.description = value.text;
                                else definition.parent = value.text;
                            } else if (field.text == "deprecated") {
                                auto value = lexer.nextToken();
                                if (value.text == "true") definition.deprecated = true;
                                else if (value.text == "false") definition.deprecated = false;
                                else return nullptr;
                            } else if (field.text == "metadata") {
                                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                while (true) {
                                    auto key = lexer.nextToken();
                                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (key.type != TshLexer::TokenType::TOK_IDENT &&
                                        key.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (value.type != TshLexer::TokenType::TOK_IDENT &&
                                        value.type != TshLexer::TokenType::TOK_STRING &&
                                        value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                    definition.metadata[key.text] = value.text;
                                }
                            } else {
                                return nullptr;
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
                        if (light_tok.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(light_tok.text)) return nullptr;
                        auto dark_sep = lexer.nextToken();
                        if (dark_sep.type != TshLexer::TokenType::TOK_COMMA) return nullptr;
                        auto dark_tok = lexer.nextToken();
                        if (dark_tok.type != TshLexer::TokenType::TOK_IDENT || !isValidColorHex(dark_tok.text)) return nullptr;

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
                if (!mark_root_section("defines")) return nullptr;
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
            // Declarative editor metadata: comments, pairs, indentation and triggers.
            // These are data for the host editor; the native core does not hard-code them.
            // =================================================================
            else if (tok.text == "editor") {
                if (!mark_root_section("editor")) return nullptr;
                auto editor_open = lexer.nextToken();
                if (editor_open.type == TshLexer::TokenType::TOK_COLON) editor_open = lexer.nextToken();
                if (editor_open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;

                auto read_string_array = [&](std::vector<std::string>& output) -> bool {
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return false;
                    while (true) {
                        auto value = lexer.nextToken();
                        if (value.type == TshLexer::TokenType::TOK_RBRACKET) return true;
                        if (value.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                            value.type != TshLexer::TokenType::TOK_IDENT) return false;
                        output.push_back(value.text);
                    }
                };

                while (true) {
                    auto field = lexer.nextToken();
                    if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (field.type == TshLexer::TokenType::TOK_EOF) return nullptr;

                    if (field.text == "comments") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto comment_kind = lexer.nextToken();
                            if (comment_kind.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (comment_kind.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            if (comment_kind.text == "line") {
                                if (!read_string_array(grammar->editor_metadata.line_comments)) return nullptr;
                            } else if (comment_kind.text == "block") {
                                if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                while (true) {
                                    auto pair_token = lexer.nextToken();
                                    if (pair_token.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                    if (pair_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (pair_token.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                    lexer.putBack(std::move(pair_token));
                                    std::vector<std::string> pair;
                                    if (!read_string_array(pair)) return nullptr;
                                    if (pair.size() != 2) return nullptr;
                                    grammar->editor_metadata.block_comments.emplace_back(pair[0], pair[1]);
                                }
                            } else {
                                return nullptr;
                            }
                        }
                    } else if (field.text == "brackets" || field.text == "surroundingPairs") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        std::vector<std::string> values;
                        if (!read_string_array(values)) return nullptr;
                        auto& target = field.text == "brackets" ?
                            grammar->editor_metadata.brackets : grammar->editor_metadata.surrounding_pairs;
                        for (const auto& value : values) {
                            if (value.size() < 2) return nullptr;
                            target.emplace_back(value.substr(0, 1), value.substr(value.size() - 1, 1));
                        }
                    } else if (field.text == "bracketNotIn") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        if (!read_string_array(grammar->editor_metadata.bracket_not_in)) return nullptr;
                    } else if (field.text == "autoClosingPairs") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                        while (true) {
                            auto pair_token = lexer.nextToken();
                            if (pair_token.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (pair_token.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (pair_token.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            TshAutoClosingPair pair;
                            while (true) {
                                auto key = lexer.nextToken();
                                if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                if (key.text == "notIn") {
                                    if (!read_string_array(pair.not_in)) return nullptr;
                                } else {
                                    auto value = lexer.nextToken();
                                    if (key.text == "open") pair.open = value.text;
                                    else if (key.text == "close") pair.close = value.text;
                                }
                            }
                            if (pair.open.empty() || pair.close.empty()) return nullptr;
                            grammar->editor_metadata.auto_closing_pairs.push_back(std::move(pair));
                        }
                    } else if (field.text == "tabSize" || field.text == "fontSize" ||
                               field.text == "lineHeight" || field.text == "letterSpacing" ||
                               field.text == "insertSpaces" || field.text == "autoIndent" ||
                               field.text == "trimTrailingWhitespace" || field.text == "formatOnType" ||
                               field.text == "formatOnPaste" || field.text == "renderWhitespace" ||
                               field.text == "lineNumbers" || field.text == "maxTokenizationLineLength") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_IDENT &&
                            value.type != TshLexer::TokenType::TOK_NUMBER &&
                            value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                        if (field.text == "tabSize" || field.text == "maxTokenizationLineLength") {
                            if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                            try {
                                size_t consumed = 0;
                                const int numeric = std::stoi(value.text, &consumed);
                                if (consumed != value.text.size() || numeric <= 0) return nullptr;
                            } catch (...) { return nullptr; }
                        } else if (field.text == "fontSize" || field.text == "lineHeight" ||
                                   field.text == "letterSpacing") {
                            if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                            try {
                                size_t consumed = 0;
                                const float numeric = std::stof(value.text, &consumed);
                                if (consumed != value.text.size() || !std::isfinite(numeric) ||
                                    (field.text != "letterSpacing" && numeric <= 0.0f)) return nullptr;
                            } catch (...) { return nullptr; }
                        } else if (field.text == "insertSpaces" || field.text == "autoIndent" ||
                                   field.text == "trimTrailingWhitespace" || field.text == "formatOnType" ||
                                   field.text == "formatOnPaste" || field.text == "lineNumbers") {
                            if (value.text != "true" && value.text != "false") return nullptr;
                        } else if (field.text == "renderWhitespace") {
                            if (value.text != "none" && value.text != "boundary" &&
                                value.text != "selection" && value.text != "all") return nullptr;
                        }
                        grammar->editor_metadata.options[field.text] = value.text;
                    } else if (field.text == "wordPattern") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                        grammar->editor_metadata.word_pattern = value.text;
                    } else if (field.text == "indentation") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto key = lexer.nextToken();
                            if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                            if (key.text == "increaseIndentPattern") grammar->editor_metadata.increase_indent_pattern = value.text;
                            else if (key.text == "decreaseIndentPattern") grammar->editor_metadata.decrease_indent_pattern = value.text;
                            else if (key.text == "indentNextLine") grammar->editor_metadata.indent_next_line_pattern = value.text;
                            else if (key.text == "unIndentedLinePattern") grammar->editor_metadata.unindented_line_pattern = value.text;
                        }
                    } else if (field.text == "completion") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto key = lexer.nextToken();
                            if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            if (key.text == "triggerCharacters") {
                                if (!read_string_array(grammar->editor_metadata.completion_trigger_characters)) return nullptr;
                            } else {
                                lexer.nextToken();
                            }
                        }
                    } else {
                        // Unknown editor properties are rejected rather than silently misread.
                        return nullptr;
                    }
                }
            }

            // Embedded Tree-sitter query sources. Captures are bound by
            // semantic style name and compiled as one query program.
            else if (tok.text == "treeSitter") {
                if (!mark_root_section("treeSitter")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type != TshLexer::TokenType::TOK_IDENT || key.text != "queries") return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                        lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                    while (true) {
                        auto entry = lexer.nextToken();
                        if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                        if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                        if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        std::string query_id;
                        std::string query_source;
                        bool enabled = true;
                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (field.type == TshLexer::TokenType::TOK_EOF ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if (field.text == "id") {
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                query_id = value.text;
                            } else if (field.text == "source") {
                                if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                query_source = value.text;
                            } else if (field.text == "enabled") {
                                if (value.text == "true") enabled = true;
                                else if (value.text == "false") enabled = false;
                                else return nullptr;
                            } else {
                                return nullptr;
                            }
                        }
                        if (query_source.empty()) return nullptr;
                        if (!query_id.empty() && !tree_sitter_query_ids.emplace(query_id, true).second) return nullptr;
                        if (enabled) dynamic_query_accumulator += query_source + "\n";
                    }
                }
            }

            // =================================================================
            // 4. 解析宏观 Tree-sitter AST 选择器: ast: [ ... ]
            // =================================================================
            else if (tok.text == "ast") {
                if (!mark_root_section("ast")) return nullptr;
                lexer.nextToken(); // :
                lexer.nextToken(); // [
                while (true) {
                    auto at = lexer.nextToken();
                    if (at.type == TshLexer::TokenType::TOK_RBRACKET || at.type == TshLexer::TokenType::TOK_EOF) break;
                    if (at.type == TshLexer::TokenType::TOK_LBRACE) {
                        AstSelectorRule ast_rule;
                        ast_rule.declaration_order = ast_declaration_order++;
                        std::string node_name, field_name, parent_name, style_bind_name;
                        auto read_ast_value = [&]() -> TshLexer::Token {
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) {
                                return {TshLexer::TokenType::TOK_EOF, ""};
                            }
                            return lexer.nextToken();
                        };

                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;

                            if (field.text == "node" || field.text == "field" || field.text == "parent" ||
                                field.text == "style" || field.text == "id") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                if (field.text == "node") node_name = value.text;
                                else if (field.text == "field") field_name = value.text;
                                else if (field.text == "parent") parent_name = value.text;
                                else if (field.text == "style") style_bind_name = value.text;
                                else ast_rule.id = value.text;
                            } else if (field.text == "query") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                dynamic_query_accumulator += value.text + "\n";
                            } else if (field.text == "priority") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                ast_rule.priority = std::stoi(value.text);
                            } else if (field.text == "enabled") {
                                auto value = read_ast_value();
                                if (value.text == "true") ast_rule.enabled = true;
                                else if (value.text == "false") ast_rule.enabled = false;
                                else return nullptr;
                            } else if (field.text == "overlap") {
                                auto value = read_ast_value();
                                if (value.text == "allow") ast_rule.allow_overlap = true;
                                else if (value.text == "deny") ast_rule.allow_overlap = false;
                                else return nullptr;
                            } else {
                                return nullptr;
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
                                if (ast_rule.target_symbol == 0) return nullptr;
                            }
                            if (!field_name.empty()) {
                                ast_rule.field_id = ts_language_field_id_for_name(ts_lang, field_name.c_str(), static_cast<uint32_t>(field_name.length()));
                                if (ast_rule.field_id == 0) return nullptr;
                            }
                            if (!parent_name.empty()) {
                                ast_rule.parent_symbol = ts_language_symbol_for_name(ts_lang, parent_name.c_str(), static_cast<uint32_t>(parent_name.length()), true);
                                if (ast_rule.parent_symbol == 0) return nullptr;
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
                if (!mark_root_section("contains")) return nullptr;
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
            } else {
                // Root-level typos must never be silently ignored. All accepted
                // keys are parsed above; vendor extensions belong in an explicit
                // extension namespace rather than being mistaken for core syntax.
                return nullptr;
            }
        }
        tok = lexer.nextToken();
    }
    if (versioned_document) {
        versioned_document_closed = tok.type == TshLexer::TokenType::TOK_RBRACE;
        if (!versioned_document_closed || lexer.nextToken().type != TshLexer::TokenType::TOK_EOF) {
            return nullptr;
        }
    }

    if (!grammar->default_scheme_name.empty() &&
        grammar->schemes.find(grammar->default_scheme_name) == grammar->schemes.end()) {
        return nullptr;
    }

    // Resolve scheme inheritance only after every declaration is parsed, so
    // a child scheme may extend a scheme declared later in the file.
    std::unordered_map<std::string, uint8_t> scheme_visit;
    std::function<bool(const std::string&)> resolve_scheme = [&](const std::string& name) -> bool {
        auto found = grammar->schemes.find(name);
        if (found == grammar->schemes.end()) return false;
        uint8_t& state = scheme_visit[name];
        if (state == 2) return true;
        if (state == 1) return false; // inheritance cycle
        state = 1;
        ColorScheme& child = found->second;
        if (!child.extends.empty()) {
            if (!resolve_scheme(child.extends)) return false;
            const ColorScheme parent = grammar->schemes.at(child.extends);
            auto child_colors = std::move(child.colors);
            auto child_ui = std::move(child.ui_colors);
            auto child_light_ui = std::move(child.ui_light_colors);
            auto child_dark_ui = std::move(child.ui_dark_colors);
            child.colors = parent.colors;
            child.ui_colors = parent.ui_colors;
            child.ui_light_colors = parent.ui_light_colors;
            child.ui_dark_colors = parent.ui_dark_colors;
            for (const auto& entry : child_colors) {
                auto& target = child.colors[entry.first];
                if (entry.second.has_light_color) {
                    target.light_color = entry.second.light_color;
                    target.has_light_color = true;
                }
                if (entry.second.has_dark_color) {
                    target.dark_color = entry.second.dark_color;
                    target.has_dark_color = true;
                }
                target.light_font_flags |= entry.second.light_font_flags;
                target.dark_font_flags |= entry.second.dark_font_flags;
            }
            for (const auto& entry : child_ui) child.ui_colors[entry.first] = entry.second;
            for (const auto& entry : child_light_ui) child.ui_light_colors[entry.first] = entry.second;
            for (const auto& entry : child_dark_ui) child.ui_dark_colors[entry.first] = entry.second;
        }
        state = 2;
        return true;
    };
    for (const auto& entry : grammar->schemes) {
        if (!resolve_scheme(entry.first)) return nullptr;
    }

    // Materialize the symbol catalog in stable ID order. Scheme colors remain
    // exclusively in ColorScheme; the style catalog contains semantic metadata only.
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
            style.deprecated = definition_it->second.deprecated;
            style.metadata = definition_it->second.metadata;
        }
        if (style.style_id >= grammar->styles.size()) {
            grammar->styles.resize(static_cast<size_t>(style.style_id) + 1);
        }
        grammar->styles[style.style_id] = std::move(style);
    }

    // Keep extension aliases deterministic while avoiding duplicates when a
    // legacy name tuple and the explicit language block both declare one.
    {
        std::vector<std::string> unique_extensions;
        for (const auto& extension : grammar->file_extensions) {
            if (std::find(unique_extensions.begin(), unique_extensions.end(), extension) == unique_extensions.end()) {
                unique_extensions.push_back(extension);
            }
        }
        grammar->file_extensions = std::move(unique_extensions);
    }

    // Validate semantic style inheritance and resolve palette fallback by
    // declared parent style. Style names remain user-defined; no token taxonomy
    // or color defaults are embedded in the runtime.
    std::unordered_map<std::string, uint8_t> style_visit;
    std::function<bool(const std::string&)> validate_style_parent = [&](const std::string& name) -> bool {
        uint8_t& state = style_visit[name];
        if (state == 2) return true;
        if (state == 1) return false;
        state = 1;
        auto definition = grammar->style_definitions.find(name);
        if (definition != grammar->style_definitions.end() && !definition->second.parent.empty()) {
            const std::string& parent = definition->second.parent;
            if (grammar->style_name_to_id.find(parent) == grammar->style_name_to_id.end()) return false;
            if (!validate_style_parent(parent)) return false;
        }
        state = 2;
        return true;
    };
    for (const auto& entry : grammar->style_name_to_id) {
        if (!validate_style_parent(entry.first)) return nullptr;
    }

    for (auto& scheme_entry : grammar->schemes) {
        auto& palette = scheme_entry.second.colors;
        for (const auto& style_entry : grammar->style_definitions) {
            const std::string& style_name = style_entry.first;
            const std::string& parent_name = style_entry.second.parent;
            if (parent_name.empty()) continue;
            const uint16_t style_id = grammar->style_name_to_id.at(style_name);
            auto& own = palette[style_id];
            std::string ancestor = parent_name;
            std::unordered_map<std::string, bool> seen;
            while (!ancestor.empty() && seen.emplace(ancestor, true).second) {
                const uint16_t parent_id = grammar->style_name_to_id.at(ancestor);
                auto parent_color = palette.find(parent_id);
                if (parent_color != palette.end()) {
                    if (!own.has_light_color && parent_color->second.has_light_color) {
                        own.light_color = parent_color->second.light_color;
                        own.has_light_color = true;
                        own.light_font_flags |= parent_color->second.light_font_flags;
                    }
                    if (!own.has_dark_color && parent_color->second.has_dark_color) {
                        own.dark_color = parent_color->second.dark_color;
                        own.has_dark_color = true;
                        own.dark_font_flags |= parent_color->second.dark_font_flags;
                    }
                }
                auto parent_definition = grammar->style_definitions.find(ancestor);
                ancestor = parent_definition == grammar->style_definitions.end() ?
                    std::string() : parent_definition->second.parent;
            }
        }
    }

    if ((!grammar->ast_selector_rules.empty() || !dynamic_query_accumulator.empty()) && !ts_lang) {
        return nullptr;
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
        if (!grammar->dynamic_ts_query) return nullptr;
        const uint32_t capture_count = ts_query_capture_count(grammar->dynamic_ts_query);
        for (uint32_t i = 0; i < capture_count; ++i) {
            uint32_t name_length = 0;
            const char* capture_name = ts_query_capture_name_for_id(
                grammar->dynamic_ts_query, static_cast<uint32_t>(i), &name_length);
            if (!capture_name || grammar->style_name_to_id.find(
                std::string(capture_name, name_length)) == grammar->style_name_to_id.end()) {
                return nullptr;
            }
        }
    }

    return grammar;
}

} // namespace windbell::tsh