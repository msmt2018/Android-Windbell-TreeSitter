#pragma once
#include <memory>
#include "tree_sitter/api.h"
std::shared_ptr<void> retainTreeSitterLanguageLibrary(const TSLanguage *language) noexcept;
