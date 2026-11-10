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
#include <limits>
#include <unordered_set>
#include <cstdint>
#include <new>
#include "ts__log.h"

namespace windbell::tsh {


namespace {

// Proves a deliberately small, unambiguous subset of Oniguruma patterns has
// a finite maximum width. Unsupported constructs are valid patterns, but are
// conservatively marked as requiring document context. This is metadata only;
// it does not alter current matching behavior.
static bool analyzeBoundedRegexWidth(
    const std::string& pattern,
    uint64_t* maximum_utf16_units
) {
    if (!maximum_utf16_units || pattern.empty()) return false;
    uint64_t total = 0;
    size_t i = 0;
    const auto add_width = [&](uint64_t width, uint64_t repeat,
                               uint64_t& target) -> bool {
        if (repeat && width > (std::numeric_limits<uint64_t>::max() - target) / repeat)
            return false;
        target += width * repeat;
        return true;
    };

    while (i < pattern.size()) {
        // Even a case-folded literal can match a supplementary Unicode scalar.
        // Two UTF-16 code units is the conservative maximum for one atom.
        uint64_t atom_width = 2;
        const unsigned char c = static_cast<unsigned char>(pattern[i++]);
        if (c == '(' || c == ')' || c == '|' || c == '^' || c == '$')
            return false;
        if (c == '[') {
            // A Unicode character class may match a supplementary scalar,
            // represented by two UTF-16 code units.
            atom_width = 2;
            bool closed = false;
            bool first = true;
            while (i < pattern.size()) {
                const char member = pattern[i++];
                if (member == ']' && !first) { closed = true; break; }
                first = false;
                if (member == '[') return false;
                if (member == '\\') {
                    if (i >= pattern.size()) return false;
                    const char escaped = pattern[i++];
                    if (escaped == 'p' || escaped == 'P' || escaped == 'x' ||
                        escaped == 'u' || escaped == 'c' || escaped == 'M')
                        return false;
                }
            }
            if (!closed) return false;
        } else if (c == '\\') {
            if (i >= pattern.size()) return false;
            const char escaped = pattern[i++];
            if (escaped == 'A' || escaped == 'z' || escaped == 'Z' ||
                escaped == 'G' || escaped == 'b' || escaped == 'B' ||
                escaped == 'K' || escaped == 'k' || escaped == 'g' ||
                escaped == 'Q' || escaped == 'E' ||
                (escaped >= '1' && escaped <= '9'))
                return false;
            // Escaped character classes and Unicode escapes may match a
            // supplementary scalar, so reserve two UTF-16 code units.
            atom_width = 2;
            if (escaped == 'x' || escaped == 'u') {
                if (i < pattern.size() && pattern[i] == '{') return false;
                const size_t digits = escaped == 'x' ? 2 : 4;
                if (i + digits > pattern.size()) return false;
                for (size_t n = 0; n < digits; ++n) {
                    if (!std::isxdigit(static_cast<unsigned char>(pattern[i + n]))) return false;
                }
                i += digits;
            } else if (escaped == 'c' || escaped == 'C' || escaped == 'M' ||
                       escaped == 'R' || escaped == 'X') {
                return false;
            }
        } else if (c == '.') {
            // Dot may match a supplementary Unicode scalar.
            atom_width = 2;
        } else if (c == '*' || c == '+' || c == '?' || c == '{' || c == '}') {
            return false;
        }

        uint64_t minimum_repeat = 1;
        uint64_t maximum_repeat = 1;
        if (i < pattern.size() && pattern[i] == '?') {
            minimum_repeat = 0;
            maximum_repeat = 1;
            ++i;
        } else if (i < pattern.size() && pattern[i] == '{') {
            const size_t close = pattern.find('}', i + 1);
            if (close == std::string::npos) return false;
            const std::string bounds = pattern.substr(i + 1, close - i - 1);
            const size_t comma = bounds.find(',');
            try {
                size_t consumed = 0;
                minimum_repeat = std::stoull(bounds.substr(0, comma), &consumed);
                if (consumed != (comma == std::string::npos ? bounds.size() : comma))
                    return false;
                if (comma == std::string::npos) maximum_repeat = minimum_repeat;
                else {
                    const std::string upper = bounds.substr(comma + 1);
                    if (upper.empty()) return false;
                    consumed = 0;
                    maximum_repeat = std::stoull(upper, &consumed);
                    if (consumed != upper.size()) return false;
                }
            } catch (...) { return false; }
            if (maximum_repeat < minimum_repeat) return false;
            i = close + 1;
        }
        if (i < pattern.size() && (pattern[i] == '?' || pattern[i] == '+'))
            return false;
        if (!add_width(atom_width, maximum_repeat, total)) return false;
    }
    *maximum_utf16_units = total;
    return true;
}
static void annotateRegexRuleExecution(CompiledRegexRule& rule) {
    uint64_t width = 0;
    const bool bounded = rule.type == RegexRuleType::MATCH &&
        analyzeBoundedRegexWidth(rule.raw_match_pattern, &width);
    rule.execution_capability = bounded ?
        RegexExecutionCapability::BOUNDED_WINDOW :
        RegexExecutionCapability::DOCUMENT_CONTEXT_REQUIRED;
    rule.maximum_match_bytes = bounded && width <= std::numeric_limits<uint64_t>::max() / sizeof(uint16_t) ?
        width * sizeof(uint16_t) : 0;
    if (bounded && rule.maximum_match_bytes == 0) {
        rule.execution_capability = RegexExecutionCapability::DOCUMENT_CONTEXT_REQUIRED;
    }
    for (auto& nested : rule.nested_rules) annotateRegexRuleExecution(nested);
}

} // namespace

static thread_local uint32_t g_tsh_last_source_offset = 0;
static thread_local std::string g_tsh_last_error_detail;

// 内部高性能 DSL 词法分析器（支持 JSON5、嵌套块、注释过滤、正则解析）
class TshLexer {
public:
    enum class TokenType {
        TOK_EOF,
        TOK_INVALID,
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
        auto invalid = [&](const std::string& reason) {
            g_tsh_last_error_detail = reason;
            return Token{TokenType::TOK_INVALID, reason};
        };
        if (lexical_error_) {
            g_tsh_last_error_detail = "unterminated block comment";
            return {TokenType::TOK_INVALID, g_tsh_last_error_detail};
        }
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

        // Quoted strings use JSON-style escapes; backticks are raw multiline strings.
        if (c == '"' || c == '\'' || c == '`') {
            const char quote = c;
            const bool raw_string = quote == '`';
            ++cursor_;
            std::string content;
            auto append_utf8 = [&](uint32_t cp) {
                if (cp == 0) {
                    // JNI modified UTF-8 encodes U+0000 as C0 80, never a C NUL.
                    content.push_back(static_cast<char>(0xC0));
                    content.push_back(static_cast<char>(0x80));
                } else if (cp <= 0x7F) content.push_back(static_cast<char>(cp));
                else if (cp <= 0x7FF) {
                    content.push_back(static_cast<char>(0xC0 | (cp >> 6)));
                    content.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                } else if (cp <= 0xFFFF) {
                    content.push_back(static_cast<char>(0xE0 | (cp >> 12)));
                    content.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                    content.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                } else {
                    // JNI uses modified UTF-8: non-BMP scalars are encoded
                    // as their two UTF-16 surrogate code units.
                    const uint32_t scalar = cp - 0x10000;
                    const uint32_t high = 0xD800 + (scalar >> 10);
                    const uint32_t low = 0xDC00 + (scalar & 0x3FF);
                    for (uint32_t unit : {high, low}) {
                        content.push_back(static_cast<char>(0xE0 | (unit >> 12)));
                        content.push_back(static_cast<char>(0x80 | ((unit >> 6) & 0x3F)));
                        content.push_back(static_cast<char>(0x80 | (unit & 0x3F)));
                    }
                }
            };
            auto read_hex4 = [&](uint32_t& value) -> bool {
                if (cursor_ + 4 > src_.size()) return false;
                value = 0;
                for (int i = 0; i < 4; ++i) {
                    const unsigned char digit = static_cast<unsigned char>(src_[cursor_++]);
                    value <<= 4;
                    if (digit >= '0' && digit <= '9') value |= digit - '0';
                    else if (digit >= 'a' && digit <= 'f') value |= digit - 'a' + 10;
                    else if (digit >= 'A' && digit <= 'F') value |= digit - 'A' + 10;
                    else return false;
                }
                return true;
            };
            while (cursor_ < src_.size() && src_[cursor_] != quote) {
                const char current = src_[cursor_++];
                if (!raw_string && (current == '\n' || current == '\r')) {
                    return invalid("unescaped newline in quoted string");
                }
                if (raw_string || current != '\\') {
                    content.push_back(current);
                    continue;
                }
                if (cursor_ >= src_.size()) return invalid("unterminated escape");
                const char escaped = src_[cursor_++];
                switch (escaped) {
                    case '"': content.push_back('"'); break;
                    case '\'': content.push_back('\''); break;
                    case '\\': content.push_back('\\'); break;
                    case '/': content.push_back('/'); break;
                    case 'b': content.push_back('\b'); break;
                    case 'f': content.push_back('\f'); break;
                    case 'n': content.push_back('\n'); break;
                    case 'r': content.push_back('\r'); break;
                    case 't': content.push_back('\t'); break;
                    case 'u': {
                        uint32_t cp = 0;
                        if (!read_hex4(cp)) return invalid("invalid unicode escape");
                        if (cp >= 0xD800 && cp <= 0xDBFF) {
                            if (cursor_ + 6 > src_.size() || src_[cursor_] != '\\' ||
                                src_[cursor_ + 1] != 'u') {
                                return invalid("unpaired high surrogate");
                            }
                            cursor_ += 2;
                            uint32_t low = 0;
                            if (!read_hex4(low) || low < 0xDC00 || low > 0xDFFF) {
                                return invalid("invalid low surrogate");
                            }
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                            return invalid("unpaired low surrogate");
                        }
                        append_utf8(cp);
                        break;
                    }
                    default: return invalid("unsupported string escape");
                }
            }
            if (cursor_ >= src_.size()) return invalid("unterminated string");
            ++cursor_;
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
            if (cursor_ >= src_.size()) return invalid("unterminated regex");
            cursor_++;
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

        ++cursor_;
        g_tsh_last_error_detail = std::string("unexpected character: ") + c;
        return {TokenType::TOK_INVALID, g_tsh_last_error_detail};
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
            if (depth != 0) {
                lexical_error_ = true;
                g_tsh_last_source_offset = static_cast<uint32_t>(cursor_);
                return "";
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
                if (cursor_ + 1 >= src_.size()) {
                    cursor_ = src_.size();
                    lexical_error_ = true;
                    return;
                }
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
    bool lexical_error_ = false;
};

std::shared_ptr<TshGrammar> TshCompiler::compile(const std::string& source, const TSLanguage* ts_lang) {
    auto compiled = compileDetailed(source, ts_lang);
    {
        std::lock_guard<std::mutex> lock(diagnostic_mutex_);
        last_diagnostics_ = compiled.diagnostics;
    }
    return std::move(compiled.grammar);
}

TshCompileResult TshCompiler::compileDetailed(const std::string& source, const TSLanguage* ts_lang) {
    g_tsh_last_source_offset = 0;
    g_tsh_last_error_detail.clear();
    auto result = compileInternal(source, ts_lang);
    std::vector<TshCompileDiagnostic> diagnostics;
    if (!result) {
        const uint32_t offset = std::min<uint32_t>(
            g_tsh_last_source_offset, static_cast<uint32_t>(source.size()));
        uint32_t line = 1;
        uint32_t column = 1;
        for (uint32_t i = 0; i < offset; ++i) {
            const uint8_t byte = static_cast<uint8_t>(source[i]);
            if (byte == '\n') {
                ++line;
                column = 1;
            } else if (byte == '\r') {
                // CR is part of CRLF and does not create a second visual column.
            } else if ((byte & 0xC0) != 0x80) {
                // Count UTF-8 leading bytes, not continuation bytes. This matches
                // UTF-16 editor columns for modified-UTF-8 surrogate code units.
                ++column;
            }
        }
        diagnostics.push_back({
            "TSH_PARSE_OR_COMPILE_ERROR",
            g_tsh_last_error_detail.empty()
                ? "TSH compilation failed near this location. Check the current property value, delimiters, references, regex/query syntax and required fields."
                : std::string("TSH compilation failed: ") + g_tsh_last_error_detail,
            offset, line, column
        });
    }
    return TshCompileResult{std::move(result), std::move(diagnostics)};
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

static bool expandRepositoryIncludes(
    const std::string& input,
    const std::unordered_map<std::string, std::string>& repository,
    std::unordered_map<std::string, bool>& active,
    std::string& output
) {
    size_t cursor = 0;
    while (cursor < input.size()) {
        const size_t include_pos = input.find("include(", cursor);
        if (include_pos == std::string::npos) {
            output.append(input, cursor, std::string::npos);
            return true;
        }
        output.append(input, cursor, include_pos - cursor);
        size_t name_start = include_pos + 8;
        while (name_start < input.size() && std::isspace(static_cast<unsigned char>(input[name_start]))) ++name_start;
        if (name_start >= input.size() || (input[name_start] != '"' && input[name_start] != '\'')) return false;
        const char quote = input[name_start++];
        const size_t name_end = input.find(quote, name_start);
        if (name_end == std::string::npos) return false;
        std::string name = input.substr(name_start, name_end - name_start);
        size_t close = name_end + 1;
        while (close < input.size() && std::isspace(static_cast<unsigned char>(input[close]))) ++close;
        if (close >= input.size() || input[close] != ')') return false;
        if (!name.empty() && name.front() == '#') name.erase(name.begin());
        auto entry = repository.find(name);
        if (entry == repository.end() || active[name]) return false;
        active[name] = true;
        if (!expandRepositoryIncludes(entry->second, repository, active, output)) return false;
        active.erase(name);
        cursor = close + 1;
    }
    return true;
}

static uint32_t multiplyColorAlpha(uint32_t color, float multiplier) {
    const uint32_t original_alpha = (color >> 24) & 0xFFu;
    const uint32_t scaled_alpha = static_cast<uint32_t>(
        std::lround(static_cast<float>(original_alpha) * multiplier)
    );
    return (color & 0x00FFFFFFu) | (std::min(scaled_alpha, 255u) << 24);
}

static bool applyFontModifier(TshFontAttributes& font, const std::string& name) {
    uint16_t bit = 0;
    bool enabled = true;
    if (name == "B" || name == "bold") bit = 0x01;
    else if (name == "I" || name == "italic") bit = 0x02;
    else if (name == "U" || name == "underline") bit = 0x04;
    else if (name == "S" || name == "strike" || name == "strikethrough") bit = 0x08;
    else if (name == "dim") bit = 0x10;
    else if (name == "no-bold") { bit = 0x01; enabled = false; }
    else if (name == "no-italic") { bit = 0x02; enabled = false; }
    else if (name == "no-underline") { bit = 0x04; enabled = false; }
    else if (name == "no-strike" || name == "no-strikethrough") { bit = 0x08; enabled = false; }
    else if (name == "no-dim") { bit = 0x10; enabled = false; }
    else return false;

    font.defined_mask |= bit;
    if (enabled) font.flags |= bit;
    else font.flags &= static_cast<uint16_t>(~bit);
    return true;
}

static bool parseAlphaValue(const TshLexer::Token& token, float& out) {
    if (token.type != TshLexer::TokenType::TOK_NUMBER) return false;
    try {
        size_t consumed = 0;
        const float value = std::stof(token.text, &consumed);
        if (consumed != token.text.size() || !std::isfinite(value) ||
            value < 0.0f || value > 1.0f) return false;
        out = value;
        return true;
    } catch (...) {
        return false;
    }
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

static void releaseCompiledRegexRule(CompiledRegexRule& rule) {
    for (auto& nested : rule.nested_rules) releaseCompiledRegexRule(nested);
    if (rule.onig_start) { onig_free(rule.onig_start); rule.onig_start = nullptr; }
    if (rule.onig_end) { onig_free(rule.onig_end); rule.onig_end = nullptr; }
}

class CompiledRegexRuleGuard {
public:
    explicit CompiledRegexRuleGuard(CompiledRegexRule& rule) : rule_(rule) {}
    ~CompiledRegexRuleGuard() { if (active_) releaseCompiledRegexRule(rule_); }
    void release() { active_ = false; }
private:
    CompiledRegexRule& rule_;
    bool active_ = true;
};

class RegexStateRuleGuard {
public:
    explicit RegexStateRuleGuard(RegexStateRule& rule) : rule_(rule) {}
    ~RegexStateRuleGuard() {
        if (active_) {
            if (rule_.pattern) { onig_free(rule_.pattern); rule_.pattern = nullptr; }
            for (auto& entry : rule_.cases) {
                if (entry.matcher) { onig_free(entry.matcher); entry.matcher = nullptr; }
            }
        }
    }
    void release() { active_ = false; }
private:
    RegexStateRule& rule_;
    bool active_ = true;
};

static std::string trimScopeToken(const std::string& value) {
    const size_t first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    const size_t last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

static bool scopeAtomMatches(const std::string& selector_atom, const std::string& scope) {
    if (selector_atom == "*") return true;
    if (selector_atom.size() > 2 &&
        selector_atom.compare(selector_atom.size() - 2, 2, ".*") == 0) {
        const std::string prefix = selector_atom.substr(0, selector_atom.size() - 1);
        return scope.rfind(prefix, 0) == 0;
    }
    return scope == selector_atom ||
        (scope.size() > selector_atom.size() &&
         scope.compare(0, selector_atom.size(), selector_atom) == 0 &&
         scope[selector_atom.size()] == '.');
}

static int matchOneTextMateSelector(const std::string& selector, const std::vector<std::string>& scopes) {
    std::vector<std::string> tokens;
    std::string current;
    auto flush = [&]() {
        if (!current.empty()) {
            tokens.push_back(current);
            current.clear();
        }
    };
    for (char ch : selector) {
        if (std::isspace(static_cast<unsigned char>(ch))) {
            flush();
        } else if (ch == '>') {
            flush();
            tokens.emplace_back(">");
        } else {
            current.push_back(ch);
        }
    }
    flush();

    std::vector<std::pair<std::string, bool>> positive;
    std::vector<std::string> excluded;
    bool exclusion_mode = false;
    bool direct_child = false;
    for (const auto& token : tokens) {
        if (token == ">") { direct_child = true; continue; }
        if (token == "-") { exclusion_mode = true; direct_child = false; continue; }
        if (exclusion_mode) excluded.push_back(token);
        else {
            positive.emplace_back(token, direct_child);
            direct_child = false;
        }
    }
    if (positive.empty()) return -1;

    int previous_index = -1;
    int specificity = 0;
    for (size_t term = 0; term < positive.size(); ++term) {
        const auto& atom = positive[term].first;
        const bool direct = positive[term].second;
        int found = -1;
        const int start = term == 0 ? 0 : previous_index + 1;
        const int end = direct && term > 0 ?
            std::min<int>(static_cast<int>(scopes.size()), previous_index + 2) :
            static_cast<int>(scopes.size());
        for (int index = start; index < end; ++index) {
            if (scopeAtomMatches(atom, scopes[static_cast<size_t>(index)])) {
                found = index;
                break;
            }
        }
        if (found < 0) return -1;
        previous_index = found;
        specificity += 100 + static_cast<int>(std::min<size_t>(atom.size(), 32));
        if (direct) specificity += 20;
    }
    for (const auto& atom : excluded) {
        for (const auto& scope : scopes) {
            if (scopeAtomMatches(atom, scope)) return -1;
        }
    }
    return specificity;
}

static void splitSelectorAlternatives(
    const std::string& input,
    std::vector<std::string>& output
) {
    size_t start = 0;
    int depth = 0;
    for (size_t i = 0; i <= input.size(); ++i) {
        const char ch = i < input.size() ? input[i] : ',';
        if (ch == '(') ++depth;
        else if (ch == ')' && depth > 0) --depth;
        else if ((ch == ',' || ch == '|') && depth == 0) {
            const std::string part = trimScopeToken(input.substr(start, i - start));
            if (!part.empty()) output.push_back(part);
            start = i + 1;
        }
    }
}

static bool expandSelectorGroups(
    const std::string& input,
    std::vector<std::string>& output,
    size_t depth = 0
) {
    if (depth > 32) return false;
    size_t open = std::string::npos;
    int nesting = 0;
    size_t close = std::string::npos;
    for (size_t i = 0; i < input.size(); ++i) {
        if (input[i] == '(') {
            if (nesting++ == 0) open = i;
        } else if (input[i] == ')') {
            if (nesting == 0) return false;
            if (--nesting == 0) { close = i; break; }
        }
    }
    if (nesting != 0) return false;
    if (open == std::string::npos) {
        output.push_back(trimScopeToken(input));
        return true;
    }

    std::vector<std::string> alternatives;
    splitSelectorAlternatives(input.substr(open + 1, close - open - 1), alternatives);
    if (alternatives.empty()) return false;
    const std::string prefix = input.substr(0, open);
    const std::string suffix = input.substr(close + 1);
    for (const auto& alternative : alternatives) {
        std::vector<std::string> expanded;
        if (!expandSelectorGroups(prefix + alternative + suffix, expanded, depth + 1)) return false;
        output.insert(output.end(), expanded.begin(), expanded.end());
        if (output.size() > 256) return false;
    }
    return true;
}

static int textMateSelectorSpecificity(const std::string& selector, const std::vector<std::string>& scopes) {
    std::vector<std::string> top_level;
    splitSelectorAlternatives(selector, top_level);
    int best = -1;
    for (const auto& alternative : top_level) {
        std::vector<std::string> expanded;
        if (!expandSelectorGroups(alternative, expanded)) continue;
        for (std::string candidate : expanded) {
            candidate = trimScopeToken(candidate);
            if (candidate.rfind("L:", 0) == 0 || candidate.rfind("R:", 0) == 0) {
                candidate.erase(0, 2);
            }
            best = std::max(best, matchOneTextMateSelector(candidate, scopes));
        }
    }
    return best;
}

static std::vector<std::string> buildStyleScopeStack(
    const TshGrammar& grammar, const TshStyle& style
) {
    std::vector<std::string> scopes;
    if (!grammar.id.empty()) scopes.push_back("source." + grammar.id);
    std::vector<const TshStyle*> chain;
    const TshStyle* current = &style;
    std::unordered_map<std::string, bool> seen;
    while (current && seen.emplace(current->name, true).second) {
        chain.push_back(current);
        if (current->parent.empty()) break;
        auto parent = std::find_if(grammar.styles.begin(), grammar.styles.end(),
            [&](const TshStyle& candidate) { return candidate.name == current->parent; });
        current = parent == grammar.styles.end() ? nullptr : &*parent;
    }
    std::reverse(chain.begin(), chain.end());
    for (const TshStyle* entry : chain) {
        std::istringstream stream(entry->scope);
        std::string scope;
        while (stream >> scope) scopes.push_back(scope);
    }
    return scopes;
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
        bool modified_utf8_null = false;
        for (size_t n = 0; n < continuation_count; ++n) {
            const uint8_t next = static_cast<uint8_t>(input[i++]);
            if (n == 0 && first == 0xC0 && next == 0x80) modified_utf8_null = true;
            if ((next & 0xC0) != 0x80) return false;
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        // JNI GetStringUTFChars uses modified UTF-8 for UTF-16 surrogate
        // code units. Preserve those code units while also accepting standard
        // four-byte UTF-8 supplied by source files.
        if (codepoint <= 0xFFFF) {
            if (codepoint < 0x80 && continuation_count != 0 && !modified_utf8_null) return false;
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

static std::string escapeOnigurumaLiteral(const std::string& value) {
    static const std::string metacharacters = R"(\\.^$|()[]{}*+?)";
    std::string escaped;
    escaped.reserve(value.size() * 2);
    for (char ch : value) {
        if (metacharacters.find(ch) != std::string::npos) escaped.push_back('\\');
        escaped.push_back(ch);
    }
    return escaped;
}

static bool expandRegexVariables(
    const std::string& input,
    const std::unordered_map<std::string, std::string>& variables,
    std::unordered_map<std::string, bool>& active,
    std::string& output
) {
    output.clear();
    for (size_t i = 0; i < input.size();) {
        const char ch = input[i];
        if (ch == '\\') {
            output.push_back(ch);
            ++i;
            if (i < input.size()) output.push_back(input[i++]);
            continue;
        }
        if (ch == '[') {
            const size_t start = i++;
            bool escaped = false;
            while (i < input.size()) {
                const char current = input[i++];
                if (escaped) { escaped = false; continue; }
                if (current == '\\') { escaped = true; continue; }
                if (current == ']') break;
            }
            output.append(input, start, i - start);
            continue;
        }
        if (ch == '@' && i + 1 < input.size() &&
            (std::isalpha(static_cast<unsigned char>(input[i + 1])) || input[i + 1] == '_')) {
            size_t end = i + 2;
            while (end < input.size() &&
                (std::isalnum(static_cast<unsigned char>(input[end])) || input[end] == '_')) ++end;
            const std::string name = input.substr(i + 1, end - i - 1);
            const auto found = variables.find(name);
            if (found == variables.end() || active[name]) return false;
            active[name] = true;
            std::string expanded;
            if (!expandRegexVariables(found->second, variables, active, expanded)) return false;
            active.erase(name);
            output += "(?:" + expanded + ")";
            i = end;
            continue;
        }
        output.push_back(ch);
        ++i;
    }
    return true;
}

static bool validateNumericCaptureGroups(
    regex_t* regex,
    const std::unordered_map<int, uint16_t>& groups
) {
    if (!regex) return groups.empty();
    const int capture_count = onig_number_of_captures(regex);
    for (const auto& entry : groups) {
        // Group zero is the complete match; positive groups must exist.
        if (entry.first < 0 || entry.first > capture_count) {
            g_tsh_last_error_detail = "capture group index is out of range: " +
                std::to_string(entry.first) + " (pattern has " +
                std::to_string(capture_count) + " capture groups)";
            return false;
        }
    }
    return true;
}

static bool validateNamedCaptureGroups(
    regex_t* regex,
    const std::unordered_map<std::string, uint16_t>& groups
) {
    if (!regex) return groups.empty();
    for (const auto& entry : groups) {
        // DSL identifiers are UTF-8; Oniguruma patterns are compiled as UTF-16LE.
        // Named group identifiers are ASCII-compatible, so widen each byte here.
        std::vector<uint16_t> name;
        name.reserve(entry.first.size());
        for (unsigned char ch : entry.first) name.push_back(static_cast<uint16_t>(ch));
        const int group = onig_name_to_backref_number(
            regex,
            reinterpret_cast<const UChar*>(name.data()),
            reinterpret_cast<const UChar*>(name.data() + name.size()),
            nullptr
        );
        if (group < 0) {
            g_tsh_last_error_detail = "named capture group does not exist: " + entry.first;
            return false;
        }
    }
    return true;
}

static bool validateCaptureStyleMapCollisions(
    regex_t* regex,
    const std::unordered_map<int, uint16_t>& numeric_groups,
    const std::unordered_map<std::string, uint16_t>& named_groups
) {
    if (!regex) return numeric_groups.empty() && named_groups.empty();
    std::unordered_map<int, std::string> declared_groups;
    for (const auto& entry : numeric_groups) {
        declared_groups.emplace(entry.first, "#" + std::to_string(entry.first));
    }
    for (const auto& entry : named_groups) {
        std::vector<uint16_t> name;
        name.reserve(entry.first.size());
        for (unsigned char ch : entry.first) name.push_back(static_cast<uint16_t>(ch));
        const int group = onig_name_to_backref_number(
            regex,
            reinterpret_cast<const UChar*>(name.data()),
            reinterpret_cast<const UChar*>(name.data() + name.size()),
            nullptr
        );
        if (group < 0) return false; // The detailed missing-name diagnostic is emitted earlier.
        auto inserted = declared_groups.emplace(group, entry.first);
        if (!inserted.second) {
            g_tsh_last_error_detail =
                "multiple capture style declarations target the same group: " +
                inserted.first->second + " and " + entry.first;
            return false;
        }
    }
    return true;
}

static regex_t* compileOnigurumaUtf16(const std::string& pattern_str, OnigOptionType options = ONIG_OPTION_DEFAULT) {
    if (pattern_str.empty()) return nullptr;
    std::vector<uint16_t> u16;
    if (!decodePatternUtf8ToUtf16(pattern_str, u16)) {
        g_tsh_last_error_detail = "regex contains invalid UTF-8";
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
        UChar err[ONIG_MAX_ERROR_MESSAGE_LEN] = {};
        onig_error_code_to_str(err, r, &einfo);
        std::string message;
        for (size_t i = 0; i < ONIG_MAX_ERROR_MESSAGE_LEN && err[i] != 0; ++i) {
            const uint8_t ch = static_cast<uint8_t>(err[i]);
            message.push_back(ch < 0x80 ? static_cast<char>(ch) : '?');
        }
        g_tsh_last_error_detail = "Oniguruma regex error: " + message;
        LOGE("WindbellTSH", "Oniguruma UTF-16 Regex Compile Failed: %s, pat: %s",
             message.c_str(), pattern_str.c_str());
        return nullptr;
    }
    return reg;
}

std::shared_ptr<TshGrammar> TshCompiler::compileInternal(const std::string& source, const TSLanguage* ts_lang) {
    auto grammar = std::make_shared<TshGrammar>();
    std::unordered_map<std::string, std::string> defines_map;
    std::unordered_map<std::string, bool> tree_sitter_query_ids;
    uint32_t native_query_declaration_order = 0;

    uint32_t global_style_counter = 0;
    bool style_id_overflow = false;
    auto ensureStyleId = [&](const std::string& name) -> uint16_t {
        auto it = grammar->style_name_to_id.find(name);
        if (it != grammar->style_name_to_id.end()) return it->second;
        if (global_style_counter > std::numeric_limits<uint16_t>::max()) {
            style_id_overflow = true;
            return 0;
        }
        const uint16_t id = static_cast<uint16_t>(global_style_counter++);
        grammar->style_name_to_id.emplace(name, id);
        return id;
    };
    TshLexer lexer(source);
    uint32_t regex_declaration_order = 0;
    uint32_t ast_declaration_order = 0;
    std::unordered_map<std::string, bool> regex_rule_ids;

    std::function<bool(CompiledRegexRule&)> parseRegexRule;
    parseRegexRule = [&](CompiledRegexRule& rule) -> bool {
        CompiledRegexRuleGuard rule_guard(rule);
        rule.regex_options = grammar->default_regex_options;
        std::string raw_match_pattern;
        std::string raw_start_pattern;
        std::string raw_end_pattern;
        std::unordered_map<std::string, bool> seen_rule_properties;
        while (true) {
            auto item = lexer.nextToken();
            if (item.type == TshLexer::TokenType::TOK_RBRACE) break;
            if (item.type == TshLexer::TokenType::TOK_EOF) return false;
            if (item.type == TshLexer::TokenType::TOK_COMMA) continue;

            if (item.type == TshLexer::TokenType::TOK_IDENT) {
                std::string property = item.text;
                // These aliases write the same compiled slot and must not
                // overwrite one another within a single rule.
                if (property == "begin") property = "start";
                else if (property == "builtin") property = "match";
                else if (property == "patterns") property = "contains";
                if (!seen_rule_properties.emplace(property, true).second) {
                    g_tsh_last_error_detail = "duplicate rule property: " + item.text;
                    return false;
                }
            }

            if (item.type == TshLexer::TokenType::TOK_NUMBER) {
                int group_index = -1;
                try {
                    size_t consumed = 0;
                    group_index = std::stoi(item.text, &consumed);
                    if (consumed != item.text.size() || group_index < 0) return false;
                } catch (...) { return false; }
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto style_name = lexer.nextToken();
                if (style_name.type != TshLexer::TokenType::TOK_STRING &&
                    style_name.type != TshLexer::TokenType::TOK_IDENT) return false;
                if (!rule.group_style_map.emplace(
                        group_index, ensureStyleId(style_name.text)).second) {
                    g_tsh_last_error_detail = "duplicate numeric capture declaration: " +
                        std::to_string(group_index);
                    return false;
                }
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
                    if (!include_name.empty() && include_name.front() == '#') include_name.erase(include_name.begin());
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
                auto pattern = lexer.nextToken();
                const bool nested_pattern_block = pattern.type == TshLexer::TokenType::TOK_LBRACE;
                if (nested_pattern_block) {
                    auto match_key = lexer.nextToken();
                    if (match_key.text != "match" ||
                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                    pattern = lexer.nextToken();
                }
                std::string pattern_text;
                if (pattern.type == TshLexer::TokenType::TOK_IDENT && pattern.text == "include") {
                    const std::string include_argument = lexer.extractParenthesizedBlock();
                    if (include_argument.empty()) return false;
                    pattern_text = "include(" + include_argument + ")";
                } else if (pattern.type == TshLexer::TokenType::TOK_REGEX ||
                           pattern.type == TshLexer::TokenType::TOK_STRING) {
                    pattern_text = pattern.text;
                } else {
                    return false;
                }
                if (nested_pattern_block &&
                    lexer.nextToken().type != TshLexer::TokenType::TOK_RBRACE) return false;
                rule.type = RegexRuleType::BLOCK;
                if (item.text == "end") raw_end_pattern = pattern_text;
                else raw_start_pattern = pattern_text;
            } else if (item.text == "style" || item.text == "name" || item.text == "contentName") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto style_name = lexer.nextToken();
                if (style_name.type != TshLexer::TokenType::TOK_STRING &&
                    style_name.type != TshLexer::TokenType::TOK_IDENT) return false;
                if (item.text == "contentName") {
                    rule.content_style_id = ensureStyleId(style_name.text);
                    rule.has_content_style = true;
                } else {
                    rule.style_id = ensureStyleId(style_name.text);
                    rule.has_style = true;
                }
            } else if (item.text == "id") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_STRING &&
                    value.type != TshLexer::TokenType::TOK_IDENT) return false;
                rule.id = value.text;
            } else if (item.text == "priority") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_NUMBER) return false;
                try {
                    size_t consumed = 0;
                    rule.priority = std::stoi(value.text, &consumed);
                    if (consumed != value.text.size()) return false;
                } catch (...) { return false; }
            } else if (item.text == "enabled") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.text == "true") rule.enabled = true;
                else if (value.text == "false") rule.enabled = false;
                else return false;
            } else if (item.text == "overlap") {
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return false;
                auto value = lexer.nextToken();
                if (value.text == "allow") rule.allow_overlap = true;
                else if (value.text == "deny") rule.allow_overlap = false;
                else return false;
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
                        if (style_name.type != TshLexer::TokenType::TOK_STRING &&
                            style_name.type != TshLexer::TokenType::TOK_IDENT) return false;
                        if (child_token.type == TshLexer::TokenType::TOK_NUMBER) {
                            try {
                                size_t consumed = 0;
                                const int group = std::stoi(child_token.text, &consumed);
                                if (consumed != child_token.text.size() || group < 0) return false;
                                if (!numeric_map->emplace(group, ensureStyleId(style_name.text)).second) {
                                    g_tsh_last_error_detail = "duplicate numeric capture declaration: " +
                                        std::to_string(group);
                                    return false;
                                }
                            } catch (...) { return false; }
                        } else {
                            if (!named_map->emplace(child_token.text, ensureStyleId(style_name.text)).second) {
                                g_tsh_last_error_detail = "duplicate named capture declaration: " +
                                    child_token.text;
                                return false;
                            }
                        }
                    } else {
                        if (child_token.type != TshLexer::TokenType::TOK_LBRACE) return false;
                        CompiledRegexRule nested;
                        nested.declaration_order = regex_declaration_order++;
                        if (!parseRegexRule(nested)) return false;
                        rule.nested_rules.push_back(std::move(nested));
                    }
                }
            } else {
                return false;
            }
        }

        auto expand_pattern = [&](std::string& pattern) -> bool {
            if (pattern.empty()) return true;
            std::unordered_map<std::string, bool> active;
            std::string expanded;
            if (!expandRepositoryIncludes(pattern, defines_map, active, expanded)) return false;
            pattern = std::move(expanded);
            return true;
        };
        // A rule is either a single-expression MATCH or a delimiter-based
        // BLOCK. Allowing both forms previously overwrote onig_start after
        // compiling the first expression, leaking that regex and making the
        // resulting rule depend on declaration order.
        if (!raw_match_pattern.empty() && !raw_start_pattern.empty()) {
            g_tsh_last_error_detail =
                "rule cannot declare both match and begin/start; choose one rule form";
            return false;
        }
        if (rule.type == RegexRuleType::BLOCK && raw_match_pattern.size() > 0) {
            g_tsh_last_error_detail =
                "block rules must use begin/start and end delimiters, not match";
            return false;
        }

        if (!expand_pattern(raw_match_pattern) || !expand_pattern(raw_start_pattern) ||
            !expand_pattern(raw_end_pattern)) return false;
        rule.raw_match_pattern = raw_match_pattern;
        rule.raw_start_pattern = raw_start_pattern;
        rule.raw_end_pattern = raw_end_pattern;
        if (!raw_match_pattern.empty()) {
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

        if (!rule.id.empty() && !regex_rule_ids.emplace(rule.id, true).second) return false;
        // Reject invalid numeric capture references at compile time. Previously
        // these references were silently ignored by the runtime, making a typo in
        // a .tsh file look like a successful but partially styled grammar.
        if (!validateNumericCaptureGroups(rule.onig_start, rule.group_style_map) ||
            !validateNumericCaptureGroups(rule.onig_start, rule.start_group_style_map) ||
            !validateNumericCaptureGroups(rule.onig_end, rule.end_group_style_map) ||
            !validateNamedCaptureGroups(rule.onig_start, rule.named_group_style_map) ||
            !validateNamedCaptureGroups(rule.onig_start, rule.start_named_group_style_map) ||
            !validateNamedCaptureGroups(rule.onig_end, rule.end_named_group_style_map) ||
            !validateCaptureStyleMapCollisions(
                rule.onig_start, rule.group_style_map, rule.named_group_style_map) ||
            !validateCaptureStyleMapCollisions(
                rule.onig_start, rule.start_group_style_map, rule.start_named_group_style_map) ||
            !validateCaptureStyleMapCollisions(
                rule.onig_end, rule.end_group_style_map, rule.end_named_group_style_map)) return false;

        const bool has_delimiter_captures = !rule.start_group_style_map.empty() ||
            !rule.start_named_group_style_map.empty() || !rule.end_group_style_map.empty() ||
            !rule.end_named_group_style_map.empty();
        // "captures" is the whole-match rule capture map. A BLOCK has two
        // independent delimiter expressions, so accepting this map would be
        // ambiguous and, historically, silently ignored by the block runtime.
        // Require beginCaptures/startCaptures and endCaptures instead.
        const bool has_generic_captures = !rule.group_style_map.empty() ||
            !rule.named_group_style_map.empty();
        if (rule.type == RegexRuleType::BLOCK && has_generic_captures) {
            g_tsh_last_error_detail =
                "captures is only valid on match rules; use beginCaptures/startCaptures or endCaptures on block rules";
            return false;
        }
        const bool valid = rule.type == RegexRuleType::BLOCK ?
            ((rule.has_style || rule.has_content_style || has_delimiter_captures) &&
             rule.onig_start && rule.onig_end) :
            (rule.onig_start != nullptr && !rule.has_content_style && !has_delimiter_captures);
        if (valid) {
            annotateRegexRuleExecution(rule);
            rule_guard.release();
        }
        return valid;
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
        if (tok.type == TshLexer::TokenType::TOK_INVALID) return nullptr;
        if (tok.type == TshLexer::TokenType::TOK_IDENT) {

            // Legacy shorthand retained with explicit, validated semantics.
            if (tok.text == "ignoreCase") {
                if (!mark_root_section("ignoreCase")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
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
                    if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
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
                        else {
                            regex_t* validation = compileOnigurumaUtf16(value.text);
                            if (!validation) return nullptr;
                            if (grammar->first_line_regex) onig_free(grammar->first_line_regex);
                            grammar->first_line_regex = validation;
                            grammar->first_line_pattern = value.text;
                        }
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
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                    lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto t = lexer.nextToken();
                    if (t.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (t.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (t.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (grammar->name.empty()) grammar->name = t.text;
                    if (!t.text.empty() && t.text[0] == '.') {
                        grammar->file_extensions.push_back(t.text.substr(1));
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

                        if (mode_tok.text == "scopeRules") {
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                            while (true) {
                                auto entry = lexer.nextToken();
                                if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                TshScopeColorRule scope_rule;
                                scope_rule.declaration_order = static_cast<uint32_t>(scheme.scope_rules.size());
                                while (true) {
                                    auto key = lexer.nextToken();
                                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (key.type == TshLexer::TokenType::TOK_EOF ||
                                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (key.text == "selector") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        scope_rule.selector = value.text;
                                    } else if (key.text == "light" || key.text == "dark") {
                                        if (value.type != TshLexer::TokenType::TOK_IDENT ||
                                            !isValidColorHex(value.text)) return nullptr;
                                        if (key.text == "light") {
                                            scope_rule.light_color = parseColorHex(value.text);
                                            scope_rule.has_light_color = true;
                                        } else {
                                            scope_rule.dark_color = parseColorHex(value.text);
                                            scope_rule.has_dark_color = true;
                                        }
                                    } else {
                                        return nullptr;
                                    }
                                }
                                if (scope_rule.selector.empty() ||
                                    (!scope_rule.has_light_color && !scope_rule.has_dark_color)) return nullptr;
                                scheme.scope_rules.push_back(std::move(scope_rule));
                            }
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
                                sid = ensureStyleId(symbol_name);
                            } else {
                                sid = style_it->second;
                            }

                            TshStylePaint paint;
                            paint.foreground = {parseColorHex(color_tok.text), true};
                            float foreground_alpha = 1.0f;
                            float background_alpha = 1.0f;
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
                                    if (flag_tok.text == "bg" || flag_tok.text == "background") {
                                        auto background_tok = lexer.nextToken();
                                        if (background_tok.type != TshLexer::TokenType::TOK_IDENT ||
                                            !isValidColorHex(background_tok.text)) return nullptr;
                                        paint.background = {parseColorHex(background_tok.text), true};
                                    } else if (flag_tok.text == "alpha" || flag_tok.text == "opacity") {
                                        if (!parseAlphaValue(lexer.nextToken(), foreground_alpha)) return nullptr;
                                    } else if (flag_tok.text == "bg-alpha" || flag_tok.text == "background-alpha") {
                                        if (!parseAlphaValue(lexer.nextToken(), background_alpha)) return nullptr;
                                    }
                                    else {
                                        if (!applyFontModifier(paint.font, flag_tok.text)) return nullptr;
                                    }
                                    continue;
                                }
                                // Commas are optional: the next identifier begins
                                // another style entry and must remain in the stream.
                                lexer.putBack(std::move(modifier));
                                break;
                            }

                            paint.foreground.argb = multiplyColorAlpha(
                                paint.foreground.argb, foreground_alpha);
                            if (paint.background.present) {
                                paint.background.argb = multiplyColorAlpha(
                                    paint.background.argb, background_alpha);
                            }

                            auto& color_entry = scheme.colors[sid];
                            if (is_light) {
                                // Multiple declarations for one style compose:
                                // supplied values override, omitted properties survive.
                                TshStylePaint merged = color_entry.lightPaint();
                                merged.overlay(paint);
                                color_entry.setLightPaint(merged);
                                color_entry.explicit_light_color = true;
                            } else {
                                TshStylePaint merged = color_entry.darkPaint();
                                merged.overlay(paint);
                                color_entry.setDarkPaint(merged);
                                color_entry.explicit_dark_color = true;
                            }
                        }
                    }
                    grammar->schemes[scheme_name] = std::move(scheme);
                }
            }

            else if (tok.text == "defaultScheme") {
                if (!mark_root_section("defaultScheme")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_STRING &&
                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                grammar->default_scheme_name = value.text;
            }

            // DSL default rendering mode: false = light/day, true = dark/night.
            else if (tok.text == "isDark") {
                if (!mark_root_section("isDark")) return nullptr;
                auto separator = lexer.nextToken();
                if (separator.type != TshLexer::TokenType::TOK_COLON) return nullptr;
                auto value = lexer.nextToken();
                if (value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
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
                        TshStylePaint light_paint;
                        TshStylePaint dark_paint;
                        light_paint.foreground = {parseColorHex(light_tok.text), true};
                        dark_paint.foreground = {parseColorHex(dark_tok.text), true};
                        float foreground_alpha = 1.0f;

                        auto modifier = lexer.nextToken();
                        if (modifier.type == TshLexer::TokenType::TOK_COMMA) {
                            modifier = lexer.nextToken();
                        }
                        while (modifier.type == TshLexer::TokenType::TOK_AT) {
                            auto flag = lexer.nextToken();
                            if (flag.text == "alpha" || flag.text == "opacity") {
                                if (!parseAlphaValue(lexer.nextToken(), foreground_alpha)) return nullptr;
                            } else {
                                if (!applyFontModifier(light_paint.font, flag.text) ||
                                    !applyFontModifier(dark_paint.font, flag.text)) return nullptr;
                            }
                            modifier = lexer.nextToken();
                            if (modifier.type == TshLexer::TokenType::TOK_COMMA) {
                                modifier = lexer.nextToken();
                            }
                        }
                        light_paint.foreground.argb = multiplyColorAlpha(
                            light_paint.foreground.argb, foreground_alpha);
                        dark_paint.foreground.argb = multiplyColorAlpha(
                            dark_paint.foreground.argb, foreground_alpha);
                        color.setLightPaint(light_paint);
                        color.setDarkPaint(dark_paint);
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
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                    lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto dt = lexer.nextToken();
                    if (dt.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (dt.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (dt.type == TshLexer::TokenType::TOK_EOF ||
                        (dt.type != TshLexer::TokenType::TOK_STRING &&
                         dt.type != TshLexer::TokenType::TOK_IDENT)) return nullptr;
                    const std::string d_name = dt.text;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto d_val = lexer.nextToken();
                    if (d_val.type != TshLexer::TokenType::TOK_REGEX &&
                        d_val.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (!defines_map.emplace(d_name, d_val.text).second) return nullptr;
                }
            }

            // TextMate-compatible named repository. Entries are regex fragments
            // addressable through include("#name") from match/begin/end patterns.
            // Keep the same expansion and cycle detection path as legacy defines.
            else if (tok.text == "repository") {
                if (!mark_root_section("repository")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                const auto close_type = open.type == TshLexer::TokenType::TOK_LBRACE
                    ? TshLexer::TokenType::TOK_RBRACE
                    : open.type == TshLexer::TokenType::TOK_LBRACKET
                        ? TshLexer::TokenType::TOK_RBRACKET
                        : TshLexer::TokenType::TOK_INVALID;
                if (close_type == TshLexer::TokenType::TOK_INVALID) return nullptr;
                while (true) {
                    auto entry = lexer.nextToken();
                    if (entry.type == close_type) break;
                    if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (entry.type == TshLexer::TokenType::TOK_EOF ||
                        (entry.type != TshLexer::TokenType::TOK_IDENT &&
                         entry.type != TshLexer::TokenType::TOK_STRING)) return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto pattern = lexer.nextToken();
                    std::string repository_value;
                    if (pattern.type == TshLexer::TokenType::TOK_REGEX ||
                        pattern.type == TshLexer::TokenType::TOK_STRING) {
                        repository_value = pattern.text;
                    } else if (pattern.type == TshLexer::TokenType::TOK_IDENT &&
                               pattern.text == "include") {
                        const std::string target = lexer.extractParenthesizedBlock();
                        if (target.empty()) return nullptr;
                        repository_value = "include(" + target + ")";
                    } else {
                        return nullptr;
                    }
                    if (repository_value.empty() ||
                        !defines_map.emplace(entry.text, repository_value).second) return nullptr;
                }
            }

            // Legacy Monarch syntax is compatibility input only. It compiles to
            // RegexStateRule entries executed by the TSH Regex pipeline.
            else if (tok.text == "monarch") {
                if (!mark_root_section("monarch")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                std::unordered_map<std::string, bool> seen_regex_state_fields;
                bool regex_state_section_seen = false;
                uint32_t regex_state_default_options = grammar->default_regex_options;
                while (true) {
                    auto field = lexer.nextToken();
                    if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (field.type == TshLexer::TokenType::TOK_EOF ||
                        !seen_regex_state_fields.emplace(field.text, true).second) return nullptr;
                    if (field.text == "variables") {
                        if (regex_state_section_seen ||
                            lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto variable = lexer.nextToken();
                            if (variable.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (variable.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (variable.type != TshLexer::TokenType::TOK_IDENT ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if ((value.type != TshLexer::TokenType::TOK_REGEX &&
                                 value.type != TshLexer::TokenType::TOK_STRING) ||
                                value.text.empty() ||
                                !grammar->regex_variables.emplace(variable.text, value.text).second) return nullptr;
                        }
                    } else if (field.text == "tokenPostfix") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                        grammar->regex_token_postfix = value.text;
                    } else if (field.text == "ignoreCase") {
                        if (regex_state_section_seen ||
                            lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.text == "true") regex_state_default_options |= ONIG_OPTION_IGNORECASE;
                        else if (value.text == "false") regex_state_default_options &= ~ONIG_OPTION_IGNORECASE;
                        else return nullptr;
                    } else if (field.text == "start" || field.text == "defaultToken") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if ((value.type != TshLexer::TokenType::TOK_STRING &&
                             value.type != TshLexer::TokenType::TOK_IDENT) ||
                            value.text.empty()) return nullptr;
                        if (field.text == "start") {
                            grammar->regex_start_state = value.text;
                            if (!grammar->regex_start_state.empty() && grammar->regex_start_state.front() == '@')
                                grammar->regex_start_state.erase(grammar->regex_start_state.begin());
                        } else {
                            grammar->regex_default_style_name = value.text;
                            grammar->regex_has_default_style = true;
                        }
                    } else if (field.text == "tokenizer") {
                        regex_state_section_seen = true;
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                        while (true) {
                            auto state_name = lexer.nextToken();
                            if (state_name.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (state_name.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if ((state_name.type != TshLexer::TokenType::TOK_IDENT &&
                                 state_name.type != TshLexer::TokenType::TOK_STRING) ||
                                state_name.text.empty()) return nullptr;
                            if (grammar->regex_states.find(state_name.text) != grammar->regex_states.end()) return nullptr;
                            if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                            auto& state_rules = grammar->regex_states[state_name.text];
                            while (true) {
                                auto entry = lexer.nextToken();
                                if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                RegexStateRule rule;
                                RegexStateRuleGuard rule_guard(rule);
                                rule.declaration_order = regex_declaration_order++;
                                uint32_t options = regex_state_default_options;
                                std::string pattern;
                                std::unordered_map<std::string, bool> seen_rule_fields;
                                while (true) {
                                    auto key = lexer.nextToken();
                                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (key.type == TshLexer::TokenType::TOK_EOF ||
                                        !seen_rule_fields.emplace(key.text, true).second ||
                                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto value = lexer.nextToken();
                                    if (key.text == "match" || key.text == "regex") {
                                        if (value.type != TshLexer::TokenType::TOK_REGEX &&
                                            value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                        pattern = value.text;
                                    } else if (key.text == "token" || key.text == "style") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        if (!value.text.empty()) {
                                            rule.token_name = value.text;
                                            rule.has_style = true;
                                        }
                                    } else if (key.text == "cases") {
                                        if (value.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                        bool saw_default_case = false;
                                        std::unordered_map<std::string, bool> seen_case_keys;
                                        while (true) {
                                            auto case_key = lexer.nextToken();
                                            if (case_key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                            if (case_key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                            if (saw_default_case ||
                                                (case_key.type != TshLexer::TokenType::TOK_STRING &&
                                                 case_key.type != TshLexer::TokenType::TOK_IDENT) ||
                                                !seen_case_keys.emplace(case_key.text, true).second ||
                                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                            auto case_token = lexer.nextToken();
                                            if ((case_token.type != TshLexer::TokenType::TOK_STRING &&
                                                 case_token.type != TshLexer::TokenType::TOK_IDENT) ||
                                                case_token.text.empty()) return nullptr;
                                            RegexStateCase case_entry;
                                            case_entry.token_name = case_token.text;
                                            if (case_key.text == "@default") {
                                                case_entry.is_default = true;
                                                saw_default_case = true;
                                            } else {
                                                std::string case_pattern;
                                                if (!case_key.text.empty() && case_key.text.front() == '@') {
                                                    std::unordered_map<std::string, bool> active_variables;
                                                    if (!expandRegexVariables(case_key.text,
                                                            grammar->regex_variables,
                                                            active_variables, case_pattern)) return nullptr;
                                                } else {
                                                    case_pattern = escapeOnigurumaLiteral(case_key.text);
                                                }
                                                uint64_t case_maximum_width = 0;
                                                if (analyzeBoundedRegexWidth(case_pattern, &case_maximum_width) &&
                                                    case_maximum_width <=
                                                        std::numeric_limits<uint64_t>::max() / sizeof(uint16_t)) {
                                                    case_entry.execution_capability =
                                                        RegexExecutionCapability::BOUNDED_WINDOW;
                                                    case_entry.maximum_match_bytes =
                                                        case_maximum_width * sizeof(uint16_t);
                                                    if (case_entry.maximum_match_bytes == 0) {
                                                        case_entry.execution_capability =
                                                            RegexExecutionCapability::DOCUMENT_CONTEXT_REQUIRED;
                                                    }
                                                }
                                                case_entry.matcher = compileOnigurumaUtf16(case_pattern, options);
                                                if (!case_entry.matcher) return nullptr;
                                            }
                                            rule.cases.push_back(std::move(case_entry));
                                        }
                                    } else if (key.text == "include") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        rule.include_state = value.text;
                                        if (!rule.include_state.empty() && rule.include_state.front() == '@')
                                            rule.include_state.erase(rule.include_state.begin());
                                    } else if (key.text == "next" || key.text == "push" || key.text == "switchTo") {
                                        if (value.type != TshLexer::TokenType::TOK_STRING &&
                                            value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                        if (key.text == "next") rule.next_state = value.text;
                                        else if (key.text == "push") rule.push_state = value.text;
                                        else rule.switch_state = value.text;
                                    } else if (key.text == "pop" || key.text == "rematch" || key.text == "enabled") {
                                        if (value.text != "true" && value.text != "false") return nullptr;
                                        if (key.text == "pop") rule.pop = value.text == "true";
                                        else if (key.text == "rematch") rule.rematch = value.text == "true";
                                        else rule.enabled = value.text == "true";
                                    } else if (key.text == "priority") {
                                        if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                        try {
                                            size_t consumed = 0;
                                            rule.priority = std::stoi(value.text, &consumed);
                                            if (consumed != value.text.size()) return nullptr;
                                        } catch (...) { return nullptr; }
                                    } else if (key.text == "flags") {
                                        if (value.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                        while (true) {
                                            auto flag = lexer.nextToken();
                                            if (flag.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                            if (flag.type == TshLexer::TokenType::TOK_COMMA) continue;
                                            if (flag.text == "i") options |= ONIG_OPTION_IGNORECASE;
                                            else if (flag.text == "m") options |= ONIG_OPTION_MULTILINE;
                                            else if (flag.text == "s") options |= ONIG_OPTION_SINGLELINE;
                                            else if (flag.text == "x") options |= ONIG_OPTION_EXTEND;
                                            else return nullptr;
                                        }
                                    } else {
                                        return nullptr;
                                    }
                                }
                                if (!pattern.empty()) {
                                    std::unordered_map<std::string, bool> active_variables;
                                    std::string expanded_pattern;
                                    if (!expandRegexVariables(pattern, grammar->regex_variables,
                                            active_variables, expanded_pattern)) return nullptr;
                                    rule.raw_pattern = expanded_pattern;
                                    uint64_t maximum_width = 0;
                                    if (analyzeBoundedRegexWidth(expanded_pattern, &maximum_width)) {
                                        rule.execution_capability = RegexExecutionCapability::BOUNDED_WINDOW;
                                        // TextSource and Oniguruma use UTF-16LE byte offsets.
                                        // UChar is an 8-bit storage byte in this adapter,
                                        // so the overflow guard must use the 2-byte code-unit
                                        // width used by the stored document, not sizeof(UChar).
                                        if (maximum_width <= std::numeric_limits<uint64_t>::max() / sizeof(uint16_t))
                                            rule.maximum_match_bytes = maximum_width * sizeof(uint16_t);
                                        else
                                            rule.execution_capability = RegexExecutionCapability::DOCUMENT_CONTEXT_REQUIRED;
                                    }
                                    // A state rule is window-safe only when both its
                                    // primary matcher and every non-default case matcher
                                    // have a proven finite width. A bounded primary
                                    // pattern alone is not sufficient: case regexes can
                                    // contain anchors, lookarounds or unbounded repeats.
                                    for (const auto& case_entry : rule.cases) {
                                        if (case_entry.matcher &&
                                            case_entry.execution_capability !=
                                                RegexExecutionCapability::BOUNDED_WINDOW) {
                                            rule.execution_capability =
                                                RegexExecutionCapability::DOCUMENT_CONTEXT_REQUIRED;
                                            rule.maximum_match_bytes = 0;
                                            break;
                                        }
                                        rule.maximum_match_bytes = std::max(
                                            rule.maximum_match_bytes,
                                            case_entry.maximum_match_bytes);
                                    }
                                    rule.pattern = compileOnigurumaUtf16(expanded_pattern, options);
                                    if (!rule.pattern) return nullptr;
                                }
                                if (rule.include_state.empty() && !rule.pattern) return nullptr;
                                state_rules.push_back(std::move(rule));
                                rule_guard.release();
                            }
                        }
                    } else {
                        return nullptr;
                    }
                }
                if (grammar->regex_states.empty()) return nullptr;
                for (auto& state : grammar->regex_states) {
                    for (auto& rule : state.second) {
                        if (rule.has_style) {
                            rule.style_id = ensureStyleId(rule.token_name + grammar->regex_token_postfix);
                        }
                        for (auto& case_entry : rule.cases) {
                            case_entry.style_id = ensureStyleId(case_entry.token_name + grammar->regex_token_postfix);
                        }
                    }
                }
                // Validate every declared variable, including unused definitions,
                // so misspellings and cycles never remain latent in a compiled grammar.
                for (const auto& variable : grammar->regex_variables) {
                    std::unordered_map<std::string, bool> active_variables;
                    std::string expanded_variable;
                    if (!expandRegexVariables(variable.second, grammar->regex_variables,
                            active_variables, expanded_variable)) return nullptr;
                }
            }

            // TextMate-style repository of reusable regular-expression fragments.
            // A rule may reference an entry as include("#repositoryName").
            else if (tok.text == "repository") {
                if (!mark_root_section("repository")) return nullptr;
                auto open = lexer.nextToken();
                if (open.type == TshLexer::TokenType::TOK_COLON) open = lexer.nextToken();
                if (open.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                while (true) {
                    auto key = lexer.nextToken();
                    if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                    if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (key.type != TshLexer::TokenType::TOK_IDENT &&
                        key.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                    auto pattern = lexer.nextToken();
                    if (pattern.type != TshLexer::TokenType::TOK_REGEX &&
                        pattern.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                    if (!defines_map.emplace(key.text, pattern.text).second) return nullptr;
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
                            if (value.size() != 2) return nullptr;
                            target.emplace_back(value.substr(0, 1), value.substr(1, 1));
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
                            std::unordered_map<std::string, bool> seen_pair_fields;
                            while (true) {
                                auto key = lexer.nextToken();
                                if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (key.type == TshLexer::TokenType::TOK_EOF ||
                                    !seen_pair_fields.emplace(key.text, true).second ||
                                    lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                if (key.text == "notIn") {
                                    if (!read_string_array(pair.not_in)) return nullptr;
                                } else if (key.text == "open" || key.text == "close") {
                                    auto value = lexer.nextToken();
                                    if ((value.type != TshLexer::TokenType::TOK_STRING &&
                                         value.type != TshLexer::TokenType::TOK_IDENT) ||
                                        value.text.empty()) return nullptr;
                                    if (key.text == "open") pair.open = value.text;
                                    else pair.close = value.text;
                                } else {
                                    return nullptr;
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
                            if (value.type != TshLexer::TokenType::TOK_IDENT ||
                                (value.text != "true" && value.text != "false")) return nullptr;
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
                            regex_t* validation = compileOnigurumaUtf16(value.text);
                            if (!validation) return nullptr;
                            onig_free(validation);
                            if (key.text == "increaseIndentPattern") grammar->editor_metadata.increase_indent_pattern = value.text;
                            else if (key.text == "decreaseIndentPattern") grammar->editor_metadata.decrease_indent_pattern = value.text;
                            else if (key.text == "indentNextLine") grammar->editor_metadata.indent_next_line_pattern = value.text;
                            else if (key.text == "unIndentedLinePattern") grammar->editor_metadata.unindented_line_pattern = value.text;
                            else return nullptr;
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
                                return nullptr;
                            }
                        }
                    } else if (field.text == "electricCharacters") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                            !read_string_array(grammar->editor_metadata.electric_characters)) return nullptr;
                    } else if (field.text == "autoCloseBefore") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                        auto value = lexer.nextToken();
                        if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                        regex_t* validation = compileOnigurumaUtf16(value.text);
                        if (!validation) return nullptr;
                        onig_free(validation);
                        grammar->editor_metadata.auto_close_before_pattern = value.text;
                    } else if (field.text == "onEnterRules") {
                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                            lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                        while (true) {
                            auto entry = lexer.nextToken();
                            if (entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                            if (entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                            TshOnEnterRule rule;
                            bool has_before = false;
                            while (true) {
                                auto key = lexer.nextToken();
                                if (key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                if (key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                if (key.type == TshLexer::TokenType::TOK_EOF ||
                                    lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                if (key.text == "action") {
                                    if (lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                    while (true) {
                                        auto action_key = lexer.nextToken();
                                        if (action_key.type == TshLexer::TokenType::TOK_RBRACE) break;
                                        if (action_key.type == TshLexer::TokenType::TOK_COMMA) continue;
                                        if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                        auto action_value = lexer.nextToken();
                                        if (action_value.type != TshLexer::TokenType::TOK_STRING &&
                                            action_value.type != TshLexer::TokenType::TOK_IDENT &&
                                            action_value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                        if (action_key.text == "indent") {
                                            if (action_value.text != "none" && action_value.text != "indent" &&
                                                action_value.text != "indentOutdent" && action_value.text != "outdent") return nullptr;
                                            rule.indent_action = action_value.text;
                                        } else if (action_key.text == "appendText") {
                                            rule.append_text = action_value.text;
                                        } else if (action_key.text == "removeText") {
                                            try {
                                                size_t consumed = 0;
                                                const int value = std::stoi(action_value.text, &consumed);
                                                if (consumed != action_value.text.size() || value < 0) return nullptr;
                                                rule.remove_text = static_cast<uint32_t>(value);
                                            } catch (...) { return nullptr; }
                                        } else {
                                            return nullptr;
                                        }
                                    }
                                } else {
                                    auto value = lexer.nextToken();
                                    if (value.type != TshLexer::TokenType::TOK_REGEX) return nullptr;
                                    regex_t* validation = compileOnigurumaUtf16(value.text);
                                    if (!validation) return nullptr;
                                    onig_free(validation);
                                    if (key.text == "beforeText") { rule.before_text = value.text; has_before = true; }
                                    else if (key.text == "afterText") rule.after_text = value.text;
                                    else if (key.text == "previousLineText") rule.previous_line_text = value.text;
                                    else return nullptr;
                                }
                            }
                            if (!has_before || rule.indent_action.empty()) return nullptr;
                            grammar->editor_metadata.on_enter_rules.push_back(std::move(rule));
                        }
                    } else {
                        // Unknown editor properties are rejected rather than silently misread.
                        return nullptr;
                    }
                }
            }

            // Explicit native Tree-sitter query programs. The source is authored
            // verbatim in .tsh and passed directly to ts_query_new. No SCM file
            // is generated, cached, translated, or loaded from disk.
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
                        NativeQueryProgram program;
                        auto release_predicate_regexes = [&]() {
                            for (auto& predicate : program.predicate_regexes) {
                                if (predicate.regex) {
                                    onig_free(predicate.regex);
                                    predicate.regex = nullptr;
                                }
                            }
                        };
                        program.declaration_order = native_query_declaration_order++;
                        std::string query_source;
                        while (true) {
                            auto field = lexer.nextToken();
                            if (field.type == TshLexer::TokenType::TOK_RBRACE) break;
                            if (field.type == TshLexer::TokenType::TOK_COMMA) continue;
                            if (field.type == TshLexer::TokenType::TOK_EOF ||
                                lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                            auto value = lexer.nextToken();
                            if (field.text == "patterns") {
                                if (value.type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                                while (true) {
                                    auto pattern_entry = lexer.nextToken();
                                    if (pattern_entry.type == TshLexer::TokenType::TOK_RBRACKET) break;
                                    if (pattern_entry.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (pattern_entry.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                    NativeQueryProgram::PatternMetadata metadata;
                                    bool has_index = false;
                                    while (true) {
                                        auto pattern_field = lexer.nextToken();
                                        if (pattern_field.type == TshLexer::TokenType::TOK_RBRACE) break;
                                        if (pattern_field.type == TshLexer::TokenType::TOK_COMMA) continue;
                                        if (pattern_field.type != TshLexer::TokenType::TOK_IDENT ||
                                            lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                        auto pattern_value = lexer.nextToken();
                                        if (pattern_field.text == "index") {
                                            if (pattern_value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                            try {
                                                size_t consumed = 0;
                                                const unsigned long parsed = std::stoul(pattern_value.text, &consumed);
                                                if (consumed != pattern_value.text.size() ||
                                                    parsed > std::numeric_limits<uint32_t>::max()) return nullptr;
                                                metadata.pattern_index = static_cast<uint32_t>(parsed);
                                                has_index = true;
                                            } catch (...) { return nullptr; }
                                        } else if (pattern_field.text == "enabled") {
                                            if (pattern_value.text == "true") metadata.enabled = true;
                                            else if (pattern_value.text == "false") metadata.enabled = false;
                                            else return nullptr;
                                        } else if (pattern_field.text == "priority") {
                                            if (pattern_value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                            try {
                                                size_t consumed = 0;
                                                metadata.priority = std::stoi(pattern_value.text, &consumed);
                                                if (consumed != pattern_value.text.size()) return nullptr;
                                                metadata.has_priority = true;
                                            } catch (...) { return nullptr; }
                                        } else if (pattern_field.text == "order") {
                                            if (pattern_value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                            try {
                                                size_t consumed = 0;
                                                const unsigned long parsed_order =
                                                    std::stoul(pattern_value.text, &consumed);
                                                if (consumed != pattern_value.text.size() ||
                                                    parsed_order > std::numeric_limits<uint32_t>::max()) return nullptr;
                                                metadata.declaration_order =
                                                    static_cast<uint32_t>(parsed_order);
                                            } catch (...) { return nullptr; }
                                        } else {
                                            return nullptr;
                                        }
                                    }
                                    if (!has_index ||
                                        !program.pattern_metadata.emplace(metadata.pattern_index, metadata).second)
                                        return nullptr;
                                }
                            } else if (field.text == "captures") {
                                if (value.type != TshLexer::TokenType::TOK_LBRACE) return nullptr;
                                while (true) {
                                    auto capture = lexer.nextToken();
                                    if (capture.type == TshLexer::TokenType::TOK_RBRACE) break;
                                    if (capture.type == TshLexer::TokenType::TOK_COMMA) continue;
                                    if (capture.type != TshLexer::TokenType::TOK_IDENT ||
                                        lexer.nextToken().type != TshLexer::TokenType::TOK_COLON) return nullptr;
                                    auto style = lexer.nextToken();
                                    if (style.type != TshLexer::TokenType::TOK_IDENT &&
                                        style.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                    if (!program.capture_bindings.emplace(capture.text, style.text).second)
                                        return nullptr;
                                }
                            } else if (field.text == "id") {
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                program.id = value.text;
                            } else if (field.text == "source") {
                                if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                query_source = value.text;
                            } else if (field.text == "enabled") {
                                if (value.text == "true") program.enabled = true;
                                else if (value.text == "false") program.enabled = false;
                                else return nullptr;
                            } else if (field.text == "priority") {
                                if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                try {
                                    size_t consumed = 0;
                                    program.priority = std::stoi(value.text, &consumed);
                                    if (consumed != value.text.size()) return nullptr;
                                } catch (...) { return nullptr; }
                            } else {
                                return nullptr;
                            }
                        }
                        if (query_source.empty()) return nullptr;
                        if (!program.id.empty() &&
                            !tree_sitter_query_ids.emplace(program.id, true).second) return nullptr;
                        if (!program.enabled && !program.pattern_metadata.empty()) {
                            g_tsh_last_error_detail =
                                "pattern metadata cannot be declared for a disabled query block";
                            return nullptr;
                        }
                        if (program.enabled) {
                            if (!ts_lang) return nullptr;
                            uint32_t error_offset = 0;
                            TSQueryError error_type = TSQueryErrorNone;
                            program.query = ts_query_new(
                                ts_lang, query_source.c_str(),
                                static_cast<uint32_t>(query_source.size()),
                                &error_offset, &error_type);
                            if (!program.query) {
                                g_tsh_last_source_offset = error_offset;
                                g_tsh_last_error_detail =
                                    "native Tree-sitter query compilation failed (error " +
                                    std::to_string(static_cast<int>(error_type)) + ")";
                                return nullptr;
                            }
                            try {
                            // Predicate steps are evaluated by TSH at complete-match
                            // granularity. Reject unsupported or malformed forms at compile time.
                            const uint32_t compiled_pattern_count = ts_query_pattern_count(program.query);
                            program.predicates_by_pattern.resize(compiled_pattern_count);
                            for (uint32_t pattern = 0;
                                 pattern < compiled_pattern_count; ++pattern) {
                                uint32_t step_count = 0;
                                const TSQueryPredicateStep* steps =
                                    ts_query_predicates_for_pattern(program.query, pattern, &step_count);
                                uint32_t step = 0;
                                uint32_t predicate_index = 0;
                                while (steps && step < step_count) {
                                    std::vector<TSQueryPredicateStep> predicate;
                                    while (step < step_count &&
                                           steps[step].type != TSQueryPredicateStepTypeDone) {
                                        predicate.push_back(steps[step++]);
                                    }
                                    if (step < step_count) ++step;
                                    if (predicate.empty() ||
                                        predicate.front().type != TSQueryPredicateStepTypeString) {
                                        g_tsh_last_error_detail = "malformed native query predicate";
                                        release_predicate_regexes();
                                        ts_query_delete(program.query);
                                        program.query = nullptr;
                                        return nullptr;
                                    }
                                    uint32_t op_length = 0;
                                    const char* op_text = ts_query_string_value_for_id(
                                        program.query, predicate.front().value_id, &op_length);
                                    const std::string op = op_text
                                        ? std::string(op_text, op_length) : std::string();
                                    const bool equality = op == "eq?" || op == "not-eq?" ||
                                        op == "any-eq?" || op == "any-not-eq?";
                                    const bool membership = op == "any-of?" || op == "not-any-of?";
                                    const bool regex_match = op == "match?" || op == "not-match?";
                                    const bool contains = op == "contains?" || op == "not-contains?" ||
                                        op == "any-contains?" || op == "any-not-contains?";
                                    const bool has_ancestor = op == "has-ancestor?" ||
                                        op == "not-has-ancestor?" || op == "any-has-ancestor?" ||
                                        op == "any-not-has-ancestor?";
                                    const bool has_parent = op == "has-parent?" ||
                                        op == "not-has-parent?" || op == "any-has-parent?" ||
                                        op == "any-not-has-parent?";
                                    const bool kind_eq = op == "kind-eq?" || op == "not-kind-eq?" ||
                                        op == "any-kind-eq?" || op == "any-not-kind-eq?";
                                    const bool structural = has_ancestor || has_parent || kind_eq;
                                    const bool supported = equality || membership || regex_match ||
                                        contains || structural;
                                    const bool fixed_arity = equality || regex_match;
                                    bool valid = predicate.size() >= 3 &&
                                        predicate[1].type == TSQueryPredicateStepTypeCapture &&
                                        (!fixed_arity || predicate.size() == 3);
                                    for (size_t arg = 2; valid && arg < predicate.size(); ++arg) {
                                        const bool capture_comparison =
                                            equality && predicate.size() == 3 &&
                                            predicate[arg].type == TSQueryPredicateStepTypeCapture;
                                        valid = capture_comparison ||
                                            predicate[arg].type == TSQueryPredicateStepTypeString;
                                    }
                                    if (!supported || !valid) {
                                        g_tsh_last_error_detail = supported
                                            ? "invalid native query predicate arguments"
                                            : "unsupported native query predicate: " + op;
                                        release_predicate_regexes();
                                        ts_query_delete(program.query);
                                        program.query = nullptr;
                                        return nullptr;
                                    }
                                    if (regex_match) {
                                        uint32_t pattern_length = 0;
                                        const char* pattern_text = ts_query_string_value_for_id(
                                            program.query, predicate[2].value_id, &pattern_length);
                                        if (!pattern_text) {
                                            g_tsh_last_error_detail = "invalid regex predicate pattern";
                                            release_predicate_regexes();
                                            ts_query_delete(program.query);
                                            program.query = nullptr;
                                            return nullptr;
                                        }
                                        regex_t* compiled = compileOnigurumaUtf16(
                                            std::string(pattern_text, pattern_length), ONIG_OPTION_NONE);
                                        if (!compiled) {
                                            g_tsh_last_error_detail = "invalid regex in native query predicate";
                                            release_predicate_regexes();
                                            ts_query_delete(program.query);
                                            program.query = nullptr;
                                            return nullptr;
                                        }
                                        NativeQueryProgram::PredicateRegex entry;
                                        entry.pattern_index = pattern;
                                        entry.predicate_index = predicate_index;
                                        entry.capture_id = predicate[1].value_id;
                                        entry.regex = compiled;
                                        entry.negate = op == "not-match?";
                                        uint64_t maximum_width_units = 0;
                                        if (analyzeBoundedRegexWidth(
                                                std::string(pattern_text, pattern_length),
                                                &maximum_width_units) &&
                                            maximum_width_units > 0 &&
                                            maximum_width_units <=
                                                (8u * 1024u * 1024u) / sizeof(uint16_t)) {
                                            entry.execution_capability =
                                                RegexExecutionCapability::BOUNDED_WINDOW;
                                            entry.maximum_match_bytes =
                                                maximum_width_units * sizeof(uint16_t);
                                        }
                                        try {
                                            program.predicate_regexes.push_back(entry);
                                        } catch (...) {
                                            onig_free(compiled);
                                            throw;
                                        }
                                    }
                                    // Keep immutable predicate steps with the query.
                                    // The execution hot path reuses these instead of
                                    // allocating/copying a vector for every match.
                                    program.predicates_by_pattern[pattern].push_back(std::move(predicate));
                                    ++predicate_index;
                                }
                            }

                            const uint32_t pattern_count = ts_query_pattern_count(program.query);
                            for (const auto& item : program.pattern_metadata) {
                                if (item.first >= pattern_count) {
                                    g_tsh_last_error_detail = "native query pattern metadata index is out of range";
                                    release_predicate_regexes();
                                    ts_query_delete(program.query);
                                    program.query = nullptr;
                                    return nullptr;
                                }
                            }

                            const uint32_t capture_count = ts_query_capture_count(program.query);
                            for (uint32_t capture_id = 0; capture_id < capture_count; ++capture_id) {
                                uint32_t name_length = 0;
                                const char* capture_name = ts_query_capture_name_for_id(
                                    program.query, capture_id, &name_length);
                                if (!capture_name) {
                                    g_tsh_last_error_detail = "native query contains an unnamed capture";
                                    release_predicate_regexes();
                                    ts_query_delete(program.query);
                                    program.query = nullptr;
                                    return nullptr;
                                }
                                const std::string capture_name_string(capture_name, name_length);
                                auto explicit_binding = program.capture_bindings.find(capture_name_string);
                                const std::string& style_name = explicit_binding != program.capture_bindings.end()
                                    ? explicit_binding->second : capture_name_string;
                                auto style = grammar->style_name_to_id.find(style_name);
                                if (style == grammar->style_name_to_id.end()) {
                                    g_tsh_last_error_detail = "native query capture @" + capture_name_string +
                                        " has no valid style binding (style: " + style_name + ")";
                                    release_predicate_regexes();
                                    ts_query_delete(program.query);
                                    program.query = nullptr;
                                    return nullptr;
                                }
                                program.capture_style_ids.emplace(
                                    static_cast<uint32_t>(capture_id), style->second);
                            }
                            for (const auto& binding : program.capture_bindings) {
                                bool found = false;
                                for (uint32_t capture_id = 0; capture_id < capture_count; ++capture_id) {
                                    uint32_t name_length = 0;
                                    const char* capture_name = ts_query_capture_name_for_id(
                                        program.query, capture_id, &name_length);
                                    if (capture_name && binding.first ==
                                        std::string(capture_name, name_length)) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    g_tsh_last_error_detail = "capture binding references unknown capture @" +
                                        binding.first;
                                    release_predicate_regexes();
                                    ts_query_delete(program.query);
                                    program.query = nullptr;
                                    return nullptr;
                                }
                            }
                            // Keep ownership inside the guarded region until the
                            // compiled query is transferred to the grammar.
                            grammar->native_queries.push_back(std::move(program));
                            } catch (const std::bad_alloc&) {
                                // A partially compiled query owns both its Tree-sitter
                                // query and any regex predicates compiled before the
                                // allocation failed. Release them before returning;
                                // never let a C++ allocation exception cross JNI.
                                release_predicate_regexes();
                                if (program.query) {
                                    ts_query_delete(program.query);
                                    program.query = nullptr;
                                }
                                try {
                                    g_tsh_last_error_detail =
                                        "out of memory while compiling native query";
                                } catch (...) {
                                    g_tsh_last_error_detail.clear();
                                }
                                return nullptr;
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 4. 解析宏观 Tree-sitter AST 选择器: ast: [ ... ]
            // =================================================================
            else if (tok.text == "ast") {
                if (!mark_root_section("ast")) return nullptr;
                if (lexer.nextToken().type != TshLexer::TokenType::TOK_COLON ||
                    lexer.nextToken().type != TshLexer::TokenType::TOK_LBRACKET) return nullptr;
                while (true) {
                    auto at = lexer.nextToken();
                    if (at.type == TshLexer::TokenType::TOK_RBRACKET) break;
                    if (at.type == TshLexer::TokenType::TOK_EOF) return nullptr;
                    if (at.type == TshLexer::TokenType::TOK_COMMA) continue;
                    if (at.type == TshLexer::TokenType::TOK_LBRACE) {
                        AstSelectorRule ast_rule;
                        ast_rule.declaration_order = ast_declaration_order++;
                        std::unordered_set<std::string> ast_fields;
                        std::string node_name, field_name, parent_name, ancestor_name, child_name, previous_sibling_name, next_sibling_name, style_bind_name;
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
                            if (!ast_fields.insert(field.text).second) {
                                g_tsh_last_error_detail =
                                    "duplicate ATS selector property: " + field.text;
                                return nullptr;
                            }

                            if (field.text == "node" || field.text == "field" || field.text == "parent" ||
                                field.text == "ancestor" || field.text == "child" ||
                                field.text == "previousSibling" || field.text == "nextSibling" ||
                                field.text == "style" || field.text == "id") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_STRING &&
                                    value.type != TshLexer::TokenType::TOK_IDENT) return nullptr;
                                if (field.text == "node") node_name = value.text;
                                else if (field.text == "field") field_name = value.text;
                                else if (field.text == "parent") parent_name = value.text;
                                else if (field.text == "ancestor") ancestor_name = value.text;
                                else if (field.text == "child") child_name = value.text;
                                else if (field.text == "previousSibling") previous_sibling_name = value.text;
                                else if (field.text == "nextSibling") next_sibling_name = value.text;
                                else if (field.text == "style") style_bind_name = value.text;
                                else ast_rule.id = value.text;
                            } else if (field.text == "query") {
                                // AST selectors are native symbol/field predicates.
                                // Full Tree-sitter query programs belong exclusively
                                // to treeSitter.queries and are passed unchanged to
                                // ts_query_new; never synthesize query text from ATS.
                                g_tsh_last_error_detail =
                                    "ast selectors cannot contain query source; use treeSitter.queries for native query programs";
                                return nullptr;
                            } else if (field.text == "text" || field.text == "textPrefix" ||
                                       field.text == "textSuffix") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_STRING) return nullptr;
                                if (field.text == "text") {
                                    ast_rule.text_equals = value.text;
                                    ast_rule.has_text_equals = true;
                                } else if (field.text == "textPrefix") {
                                    ast_rule.text_prefix = value.text;
                                    ast_rule.has_text_prefix = true;
                                } else {
                                    ast_rule.text_suffix = value.text;
                                    ast_rule.has_text_suffix = true;
                                }
                            } else if (field.text == "textRegex") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_REGEX &&
                                    value.type != TshLexer::TokenType::TOK_STRING) {
                                    g_tsh_last_error_detail = "ATS textRegex requires a regex literal or quoted pattern";
                                    return nullptr;
                                }
                                ast_rule.text_regex_pattern = value.text;
                                ast_rule.has_text_regex = true;
                            } else if (field.text == "firstChild" || field.text == "lastChild") {
                                auto value = read_ast_value();
                                int8_t flag = -1;
                                if (value.text == "true") flag = 1;
                                else if (value.text == "false") flag = 0;
                                else return nullptr;
                                if (field.text == "firstChild") ast_rule.first_child = flag;
                                else ast_rule.last_child = flag;
                            } else if (field.text == "childIndex" || field.text == "namedChildIndex") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                try {
                                    size_t consumed = 0;
                                    const unsigned long parsed = std::stoul(value.text, &consumed);
                                    if (consumed != value.text.size() ||
                                        parsed > std::numeric_limits<uint32_t>::max()) return nullptr;
                                    if (field.text == "childIndex")
                                        ast_rule.child_index = static_cast<uint32_t>(parsed);
                                    else
                                        ast_rule.named_child_index = static_cast<uint32_t>(parsed);
                                } catch (...) { return nullptr; }
                            } else if (field.text == "named" || field.text == "missing" ||
                                       field.text == "extra" || field.text == "error" ||
                                       field.text == "hasError") {
                                auto value = read_ast_value();
                                int8_t flag = -1;
                                if (value.text == "true") flag = 1;
                                else if (value.text == "false") flag = 0;
                                else return nullptr;
                                if (field.text == "named") ast_rule.named = flag;
                                else if (field.text == "missing") ast_rule.missing = flag;
                                else if (field.text == "extra") ast_rule.extra = flag;
                                else if (field.text == "error") ast_rule.error = flag;
                                else ast_rule.has_error = flag;
                            } else if (field.text == "priority") {
                                auto value = read_ast_value();
                                if (value.type != TshLexer::TokenType::TOK_NUMBER) return nullptr;
                                try {
                                    size_t consumed = 0;
                                    ast_rule.priority = std::stoi(value.text, &consumed);
                                    if (consumed != value.text.size()) return nullptr;
                                } catch (...) { return nullptr; }
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
                                const uint16_t sid = ensureStyleId(style_bind_name);
                                style_it = grammar->style_name_to_id.find(style_bind_name);
                            }
                            ast_rule.style_id = style_it->second;
                            ast_rule.has_style = true;
                        }

                        if (ts_lang) {
                            if (!node_name.empty()) {
                                const bool symbol_is_named = !field_name.empty() ||
                                    ast_rule.named < 0 || ast_rule.named != 0;
                                ast_rule.target_symbol = ts_language_symbol_for_name(
                                    ts_lang, node_name.c_str(),
                                    static_cast<uint32_t>(node_name.length()), symbol_is_named);
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
                            if (!ancestor_name.empty()) {
                                ast_rule.ancestor_symbol = ts_language_symbol_for_name(
                                    ts_lang, ancestor_name.c_str(),
                                    static_cast<uint32_t>(ancestor_name.length()), true);
                                if (ast_rule.ancestor_symbol == 0) return nullptr;
                            }
                            if (!child_name.empty()) {
                                ast_rule.child_symbol = ts_language_symbol_for_name(
                                    ts_lang, child_name.c_str(),
                                    static_cast<uint32_t>(child_name.length()), true);
                                if (ast_rule.child_symbol == 0) return nullptr;
                            }
                            if (!previous_sibling_name.empty()) {
                                ast_rule.previous_sibling_symbol = ts_language_symbol_for_name(
                                    ts_lang, previous_sibling_name.c_str(),
                                    static_cast<uint32_t>(previous_sibling_name.length()), true);
                                if (ast_rule.previous_sibling_symbol == 0) return nullptr;
                            }
                            if (!next_sibling_name.empty()) {
                                ast_rule.next_sibling_symbol = ts_language_symbol_for_name(
                                    ts_lang, next_sibling_name.c_str(),
                                    static_cast<uint32_t>(next_sibling_name.length()), true);
                                if (ast_rule.next_sibling_symbol == 0) return nullptr;
                            }
                        }

                        if (ast_rule.has_text_regex) {
                            if (ast_rule.text_regex_pattern.empty()) {
                                g_tsh_last_error_detail = "ATS textRegex pattern must not be empty";
                                return nullptr;
                            }
                            ast_rule.text_regex = compileOnigurumaUtf16(
                                ast_rule.text_regex_pattern, ONIG_OPTION_DEFAULT);
                            if (!ast_rule.text_regex) return nullptr;
                            uint64_t maximum_width = 0;
                            if (analyzeBoundedRegexWidth(
                                    ast_rule.text_regex_pattern, &maximum_width) &&
                                maximum_width <= std::numeric_limits<uint64_t>::max() /
                                    sizeof(uint16_t)) {
                                ast_rule.text_regex_bounded = true;
                                ast_rule.text_regex_maximum_bytes =
                                    maximum_width * sizeof(uint16_t);
                            }
                        }
                        grammar->ast_selector_rules.push_back(std::move(ast_rule));
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

    if (!grammar->regex_default_style_name.empty()) {
        grammar->regex_default_style_id =
            ensureStyleId(grammar->regex_default_style_name + grammar->regex_token_postfix);
    }

    if (!grammar->regex_states.empty()) {
        if (grammar->regex_states.find(grammar->regex_start_state) == grammar->regex_states.end()) return nullptr;
        auto validate_state_reference = [&](std::string value, bool allow_stack_ops) -> bool {
            if (value.empty()) return true;
            const bool has_prefix = value.front() == '@';
            if (has_prefix) value.erase(value.begin());
            if (has_prefix && allow_stack_ops &&
                (value == "pop" || value == "popall" || value == "push")) return true;
            return grammar->regex_states.find(value) != grammar->regex_states.end();
        };
        std::unordered_map<std::string, uint8_t> include_visit;
        std::function<bool(const std::string&)> validate_includes = [&](const std::string& state_name) -> bool {
            uint8_t& state = include_visit[state_name];
            if (state == 2) return true;
            if (state == 1) return false;
            state = 1;
            auto rules_it = grammar->regex_states.find(state_name);
            if (rules_it == grammar->regex_states.end()) return false;
            for (const auto& rule : rules_it->second) {
                if (!rule.include_state.empty() && !validate_includes(rule.include_state)) return false;
            }
            state = 2;
            return true;
        };
        for (const auto& state : grammar->regex_states) {
            if (!validate_includes(state.first)) return nullptr;
        }
        for (const auto& state : grammar->regex_states) {
            for (const auto& rule : state.second) {
                if (!rule.include_state.empty() &&
                    grammar->regex_states.find(rule.include_state) == grammar->regex_states.end()) return nullptr;
                if (!validate_state_reference(rule.next_state, true) ||
                    !validate_state_reference(rule.push_state, false) ||
                    !validate_state_reference(rule.switch_state, false)) return nullptr;
                const int transitions = (!rule.next_state.empty() ? 1 : 0) +
                    (!rule.push_state.empty() ? 1 : 0) +
                    (!rule.switch_state.empty() ? 1 : 0) + (rule.pop ? 1 : 0);
                if (transitions > 1) return nullptr;
            }
        }

        // Compute a conservative capability for the complete state graph that
        // can actually execute from regex_start_state. Include edges and state
        // transitions are both relevant: a bounded root rule may enter an
        // unbounded nested state several tokens later.
        grammar->regex_state_graph_bounded = true;
        grammar->regex_state_graph_maximum_match_bytes = 0;
        std::unordered_set<std::string> reachable_states;
        std::vector<std::string> pending_states{grammar->regex_start_state};
        const auto enqueue_state = [&](const std::string& target) {
            if (target.empty()) return;
            std::string state_target = target;
            if (state_target.front() == '@') {
                state_target.erase(state_target.begin());
                // These @-prefixed values are stack operations, not named states.
                if (state_target == "pop" || state_target == "popall" ||
                    state_target == "push") return;
            }
            pending_states.push_back(std::move(state_target));
        };
        while (!pending_states.empty()) {
            std::string state_name = std::move(pending_states.back());
            pending_states.pop_back();
            if (!reachable_states.insert(state_name).second) continue;
            const auto state_it = grammar->regex_states.find(state_name);
            if (state_it == grammar->regex_states.end()) {
                // References were validated above; retain a defensive failure
                // in case the graph is mutated during future compiler changes.
                grammar->regex_state_graph_bounded = false;
                break;
            }
            for (const auto& rule : state_it->second) {
                if (!rule.enabled) continue;
                enqueue_state(rule.include_state);
                enqueue_state(rule.next_state);
                enqueue_state(rule.push_state);
                enqueue_state(rule.switch_state);

                // Include-only entries have no matcher of their own.
                if (!rule.pattern) {
                    if (!rule.raw_pattern.empty() ||
                        std::any_of(rule.cases.begin(), rule.cases.end(),
                            [](const RegexStateCase& entry) { return !entry.is_default; })) {
                        grammar->regex_state_graph_bounded = false;
                        break;
                    }
                    continue;
                }
                if (rule.execution_capability != RegexExecutionCapability::BOUNDED_WINDOW ||
                    rule.maximum_match_bytes == 0) {
                    grammar->regex_state_graph_bounded = false;
                    break;
                }
                grammar->regex_state_graph_maximum_match_bytes = std::max(
                    grammar->regex_state_graph_maximum_match_bytes,
                    rule.maximum_match_bytes);
                for (const auto& case_entry : rule.cases) {
                    if (case_entry.is_default) continue;
                    if (!case_entry.matcher ||
                        case_entry.execution_capability != RegexExecutionCapability::BOUNDED_WINDOW ||
                        case_entry.maximum_match_bytes == 0) {
                        grammar->regex_state_graph_bounded = false;
                        break;
                    }
                    grammar->regex_state_graph_maximum_match_bytes = std::max(
                        grammar->regex_state_graph_maximum_match_bytes,
                        case_entry.maximum_match_bytes);
                }
                if (!grammar->regex_state_graph_bounded) break;
            }
            if (!grammar->regex_state_graph_bounded) break;
        }
        // A graph containing only include/transition directives has no
        // executable finite-width matcher and must not enter the window path.
        // Keep each TextSource tokenizer window within a predictable memory
        // budget. The core window adds 256 KiB to this proven lookahead width.
        constexpr uint64_t kMaxRegexStateLookaheadBytes = 8u * 1024u * 1024u;
        if (!grammar->regex_state_graph_bounded ||
            grammar->regex_state_graph_maximum_match_bytes == 0 ||
            grammar->regex_state_graph_maximum_match_bytes > kMaxRegexStateLookaheadBytes) {
            grammar->regex_state_graph_bounded = false;
            grammar->regex_state_graph_maximum_match_bytes = 0;
        }
    }

    if (style_id_overflow) return nullptr;

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
            auto child_scope_rules = std::move(child.scope_rules);
            child.colors = parent.colors;
            for (auto& inherited : child.colors) {
                inherited.second.explicit_light_color = false;
                inherited.second.explicit_dark_color = false;
            }
            child.ui_colors = parent.ui_colors;
            child.ui_light_colors = parent.ui_light_colors;
            child.ui_dark_colors = parent.ui_dark_colors;
            child.scope_rules = parent.scope_rules;
            uint32_t next_scope_order = static_cast<uint32_t>(child.scope_rules.size());
            for (auto& scope_rule : child_scope_rules) {
                scope_rule.declaration_order = next_scope_order++;
                child.scope_rules.push_back(std::move(scope_rule));
            }
            for (const auto& entry : child_colors) {
                child.colors[entry.first].overlay(entry.second);
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

    // Apply TextMate scope-selector theme rules to styles that do not already
    // have an explicit per-style color in the selected scheme.
    for (auto& scheme_entry : grammar->schemes) {
        auto& scheme = scheme_entry.second;
        for (const auto& style : grammar->styles) {
            const auto scope_stack = buildStyleScopeStack(*grammar, style);
            if (scope_stack.empty()) continue;
            auto& palette = scheme.colors[style.style_id];
            const bool explicit_light = palette.explicit_light_color;
            const bool explicit_dark = palette.explicit_dark_color;
            int best_light_specificity = -1;
            int best_dark_specificity = -1;
            uint32_t best_light_order = 0;
            uint32_t best_dark_order = 0;
            uint32_t best_light_color = 0;
            uint32_t best_dark_color = 0;
            for (const auto& scope_rule : scheme.scope_rules) {
                const int specificity = textMateSelectorSpecificity(scope_rule.selector, scope_stack);
                if (specificity < 0) continue;
                if (scope_rule.has_light_color && !explicit_light &&
                    (specificity > best_light_specificity ||
                     (specificity == best_light_specificity && scope_rule.declaration_order >= best_light_order))) {
                    best_light_specificity = specificity;
                    best_light_order = scope_rule.declaration_order;
                    best_light_color = scope_rule.light_color;
                }
                if (scope_rule.has_dark_color && !explicit_dark &&
                    (specificity > best_dark_specificity ||
                     (specificity == best_dark_specificity && scope_rule.declaration_order >= best_dark_order))) {
                    best_dark_specificity = specificity;
                    best_dark_order = scope_rule.declaration_order;
                    best_dark_color = scope_rule.dark_color;
                }
            }
            if (!explicit_light && best_light_specificity >= 0) {
                palette.setLightForeground({best_light_color, true});
            }
            if (!explicit_dark && best_dark_specificity >= 0) {
                palette.setDarkForeground({best_dark_color, true});
            }
        }
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
                    own.inheritMissing(parent_color->second);
                }
                auto parent_definition = grammar->style_definitions.find(ancestor);
                ancestor = parent_definition == grammar->style_definitions.end() ?
                    std::string() : parent_definition->second.parent;
            }
        }
    }

    if (!grammar->ast_selector_rules.empty() && !ts_lang) {
        return nullptr;
    }

    // Build immutable ATS dispatch indexes once. Runtime traversal only visits
    // rules that can target the current node (plus intentionally generic rules).
    grammar->ast_rules_by_node_symbol.clear();
    grammar->ast_field_rules_by_parent_symbol.clear();
    grammar->ast_wildcard_rules.clear();
    for (uint32_t rule_index = 0;
         rule_index < grammar->ast_selector_rules.size(); ++rule_index) {
        const AstSelectorRule& rule = grammar->ast_selector_rules[rule_index];
        if (rule.field_id != 0) {
            if (rule.target_symbol != 0) {
                grammar->ast_field_rules_by_parent_symbol[rule.target_symbol].push_back(rule_index);
            } else {
                grammar->ast_wildcard_rules.push_back(rule_index);
            }
        } else if (rule.target_symbol != 0) {
            grammar->ast_rules_by_node_symbol[rule.target_symbol].push_back(rule_index);
        } else {
            grammar->ast_wildcard_rules.push_back(rule_index);
        }
    }

    // Each native query block was compiled independently by Tree-sitter's
    // own query parser. No SCM source is synthesized or written to disk.

    return grammar;
}

} // namespace windbell::tsh