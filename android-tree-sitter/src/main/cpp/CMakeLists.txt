#
#  This file is part of android-tree-sitter.
#
#  android-tree-sitter library is free software; you can redistribute it and/or
#  modify it under the terms of the GNU Lesser General Public
#  License as published by the Free Software Foundation; either
#  version 2.1 of the License, or (at your option) any later version.
#
#  android-tree-sitter library is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
#  Lesser General Public License for more details.
#
#   You should have received a copy of the GNU General Public License
#  along with android-tree-sitter.  If not, see <https://www.gnu.org/licenses/>.
#

#
#  Windbell Treesitter Highlighting Configuration Core (Windbell-TSH)
#

cmake_minimum_required(VERSION 3.22.1)
project("android-tree-sitter")
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(PROJECT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# 引入项目通用配置
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../cmake/common-config.cmake)


# Tree-sitter 0.28 exposes progress callbacks but not an exact VM work budget.
# Apply a private source overlay only when every pinned-source anchor is present.
# Older Tree-sitter sources keep their original amalgamation and fail maxSteps closed.
set(TSH_TREE_SITTER_LIB_SOURCE "${TS_DIR}/lib/src/lib.c")
set(TSH_TREE_SITTER_HAS_EXACT_QUERY_STEP_BUDGET FALSE)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${TS_DIR}/lib/src/lib.c"
    "${TS_DIR}/lib/src/query.c"
    "${TS_DIR}/lib/src/alloc.c"
    "${TS_DIR}/lib/src/stack.c"
    "${TS_DIR}/lib/src/parser.c"
    "${TS_DIR}/lib/src/subtree.c"
)
file(READ "${TS_DIR}/lib/src/query.c" TSH_TREE_SITTER_QUERY_SOURCE)
file(READ "${TS_DIR}/lib/src/lib.c" TSH_TREE_SITTER_LIB_AMALGAMATION)

# Compile a private copy of the real Tree-sitter implementation files.
# lib.c is only an include amalgamation; editing its text does not edit alloc.c,
# stack.c, parser.c or subtree.c.
set(TSH_RUNTIME_PERF_OVERLAY_DIR "${CMAKE_CURRENT_BINARY_DIR}/tree-sitter-runtime-perf")
file(REMOVE_RECURSE "${TSH_RUNTIME_PERF_OVERLAY_DIR}")
file(MAKE_DIRECTORY "${TSH_RUNTIME_PERF_OVERLAY_DIR}")
file(COPY "${TS_DIR}/lib/src/." DESTINATION "${TSH_RUNTIME_PERF_OVERLAY_DIR}")
set(TSH_TREE_SITTER_LIB_SOURCE "${TSH_RUNTIME_PERF_OVERLAY_DIR}/lib.c")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${TS_DIR}/lib/src/alloc.c" "${TS_DIR}/lib/src/stack.c"
    "${TS_DIR}/lib/src/parser.c" "${TS_DIR}/lib/src/subtree.c"
    "${TS_DIR}/lib/src/reusable_node.h"
    "${TS_DIR}/lib/src/lib.c" "${TS_DIR}/lib/src/query.c")

# Thread-safe size-class recycling for short-lived parser allocations. The
# cache is globally bounded and flushed before the final OOM path.
set(TSH_ALLOC_SOURCE [=[
#include "alloc.h"
#include "tree_sitter/api.h"
#include <pthread.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef union TsAllocHeader TsAllocHeader;
union TsAllocHeader {
  struct { size_t capacity; size_t requested; int32_t class_index; TsAllocHeader *next; } meta;
  max_align_t alignment;
};
static const size_t ts_pool_sizes[] = {32, 64, 128, 256, 512, 1024};
#define TS_POOL_CLASS_COUNT (sizeof(ts_pool_sizes) / sizeof(ts_pool_sizes[0]))
#define TS_POOL_MAX_BLOCKS_PER_CLASS 128u
#define TS_POOL_MAX_CACHED_BYTES (512u * 1024u)
static pthread_mutex_t ts_pool_mutex = PTHREAD_MUTEX_INITIALIZER;
static TsAllocHeader *ts_pool_heads[TS_POOL_CLASS_COUNT];
static size_t ts_pool_counts[TS_POOL_CLASS_COUNT];
static size_t ts_pool_cached_bytes;
static uint64_t ts_alloc_calls, ts_alloc_cache_hits, ts_alloc_requested_bytes;
static uint64_t ts_alloc_live_bytes, ts_alloc_peak_live_bytes;

static int32_t ts_pool_class_for_size(size_t size) {
  for (uint32_t i = 0; i < TS_POOL_CLASS_COUNT; i++)
    if (size <= ts_pool_sizes[i]) return (int32_t)i;
  return -1;
}
static void *ts_alloc_block(size_t capacity, size_t requested, int32_t class_index) {
  if (capacity > SIZE_MAX - sizeof(TsAllocHeader)) return NULL;
  TsAllocHeader *h = (TsAllocHeader *)malloc(sizeof(TsAllocHeader) + capacity);
  if (!h) return NULL;
  h->meta.capacity = capacity; h->meta.requested = requested;
  h->meta.class_index = class_index; h->meta.next = NULL;
  return (void *)(h + 1);
}
static void ts_pool_flush(void) {
  pthread_mutex_lock(&ts_pool_mutex);
  for (uint32_t i = 0; i < TS_POOL_CLASS_COUNT; i++) {
    TsAllocHeader *h = ts_pool_heads[i]; ts_pool_heads[i] = NULL; ts_pool_counts[i] = 0;
    while (h) { TsAllocHeader *next = h->meta.next; free(h); h = next; }
  }
  ts_pool_cached_bytes = 0;
  pthread_mutex_unlock(&ts_pool_mutex);
}
static void ts_record_live(size_t requested) {
  uint64_t live = __atomic_add_fetch(&ts_alloc_live_bytes, requested, __ATOMIC_RELAXED);
  uint64_t peak = __atomic_load_n(&ts_alloc_peak_live_bytes, __ATOMIC_RELAXED);
  while (live > peak && !__atomic_compare_exchange_n(
      &ts_alloc_peak_live_bytes, &peak, live, 1, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {}
}
static void *ts_malloc_default(size_t size) {
  const size_t requested = size;
  if (size == 0) size = 1;
  int32_t ci = ts_pool_class_for_size(size);
  size_t capacity = ci >= 0 ? ts_pool_sizes[ci] : size;
  TsAllocHeader *h = NULL;
  __atomic_add_fetch(&ts_alloc_calls, 1, __ATOMIC_RELAXED);
  __atomic_add_fetch(&ts_alloc_requested_bytes, requested, __ATOMIC_RELAXED);
  if (ci >= 0) {
    pthread_mutex_lock(&ts_pool_mutex);
    h = ts_pool_heads[ci];
    if (h) {
      ts_pool_heads[ci] = h->meta.next; ts_pool_counts[ci]--;
      ts_pool_cached_bytes -= sizeof(TsAllocHeader) + h->meta.capacity;
      h->meta.next = NULL;
    }
    pthread_mutex_unlock(&ts_pool_mutex);
    if (h) {
      __atomic_add_fetch(&ts_alloc_cache_hits, 1, __ATOMIC_RELAXED);
      h->meta.requested = requested;
    }
  }
  void *result = h ? (void *)(h + 1) : ts_alloc_block(capacity, requested, ci);
  if (!result) { ts_pool_flush(); result = ts_alloc_block(capacity, requested, ci); }
  if (!result) {
    fprintf(stderr, "tree-sitter OOM requested=%zu capacity=%zu calls=%llu cache_hits=%llu total=%llu live=%llu peak=%llu\n",
      requested, capacity,
      (unsigned long long)__atomic_load_n(&ts_alloc_calls, __ATOMIC_RELAXED),
      (unsigned long long)__atomic_load_n(&ts_alloc_cache_hits, __ATOMIC_RELAXED),
      (unsigned long long)__atomic_load_n(&ts_alloc_requested_bytes, __ATOMIC_RELAXED),
      (unsigned long long)__atomic_load_n(&ts_alloc_live_bytes, __ATOMIC_RELAXED),
      (unsigned long long)__atomic_load_n(&ts_alloc_peak_live_bytes, __ATOMIC_RELAXED));
    abort();
  }
  ts_record_live(requested);
  return result;
}
static void ts_free_default(void *buffer) {
  if (!buffer) return;
  TsAllocHeader *h = ((TsAllocHeader *)buffer) - 1;
  __atomic_sub_fetch(&ts_alloc_live_bytes, h->meta.requested, __ATOMIC_RELAXED);
  int32_t ci = h->meta.class_index;
  size_t bytes = sizeof(TsAllocHeader) + h->meta.capacity;
  if (ci >= 0 && (uint32_t)ci < TS_POOL_CLASS_COUNT) {
    pthread_mutex_lock(&ts_pool_mutex);
    if (ts_pool_counts[ci] < TS_POOL_MAX_BLOCKS_PER_CLASS &&
        bytes <= TS_POOL_MAX_CACHED_BYTES - ts_pool_cached_bytes) {
      h->meta.next = ts_pool_heads[ci]; ts_pool_heads[ci] = h;
      ts_pool_counts[ci]++; ts_pool_cached_bytes += bytes;
      pthread_mutex_unlock(&ts_pool_mutex); return;
    }
    pthread_mutex_unlock(&ts_pool_mutex);
  }
  free(h);
}
static void *ts_calloc_default(size_t count, size_t size) {
  if (size && count > SIZE_MAX / size) { fprintf(stderr, "tree-sitter calloc overflow\n"); abort(); }
  size_t total = count * size;
  void *result = ts_malloc_default(total);
  if (total) memset(result, 0, total);
  return result;
}
static void *ts_realloc_default(void *buffer, size_t size) {
  if (!buffer) return ts_malloc_default(size);
  if (!size) { ts_free_default(buffer); return NULL; }
  TsAllocHeader *h = ((TsAllocHeader *)buffer) - 1;
  if (size <= h->meta.capacity) {
    size_t old = h->meta.requested; h->meta.requested = size;
    if (size > old) ts_record_live(size - old);
    else if (old > size) __atomic_sub_fetch(&ts_alloc_live_bytes, old - size, __ATOMIC_RELAXED);
    return buffer;
  }
  size_t old = h->meta.requested;
  void *replacement = ts_malloc_default(size);
  memcpy(replacement, buffer, old);
  ts_free_default(buffer);
  return replacement;
}
TS_PUBLIC void *(*ts_current_malloc)(size_t) = ts_malloc_default;
TS_PUBLIC void *(*ts_current_calloc)(size_t, size_t) = ts_calloc_default;
TS_PUBLIC void *(*ts_current_realloc)(void *, size_t) = ts_realloc_default;
TS_PUBLIC void (*ts_current_free)(void *) = ts_free_default;
void ts_set_allocator(void *(*m)(size_t), void *(*c)(size_t,size_t),
                      void *(*r)(void *,size_t), void (*f)(void *)) {
  // Tree-sitter allocations must always be released by the matching
  // allocator family. Mixing a caller-provided malloc with libc free (or
  // realloc) is undefined behavior, so accept custom allocators only as a
  // complete quartet. A partial quartet deliberately falls back to libc.
  if (m && c && r && f) {
    ts_current_malloc = m;
    ts_current_calloc = c;
    ts_current_realloc = r;
    ts_current_free = f;
  } else if (!m && !c && !r && !f) {
    ts_current_malloc = ts_malloc_default;
    ts_current_calloc = ts_calloc_default;
    ts_current_realloc = ts_realloc_default;
    ts_current_free = ts_free_default;
  } else {
    ts_current_malloc = malloc;
    ts_current_calloc = calloc;
    ts_current_realloc = realloc;
    ts_current_free = free;
  }
}
]=])

file(READ "${TS_DIR}/lib/src/stack.c" TSH_STACK_SOURCE)
file(READ "${TS_DIR}/lib/src/parser.c" TSH_PARSER_SOURCE)
file(READ "${TS_DIR}/lib/src/subtree.c" TSH_SUBTREE_SOURCE)
file(READ "${TS_DIR}/lib/src/reusable_node.h" TSH_REUSABLE_NODE_HEADER)

# MAX_ITERATOR_COUNT in upstream stack.c is used as a semantic branch cap:
# stack__iter silently discards every additional GLR path. Keep its value only
# as an initial allocation hint; let the Array grow so no parse path is dropped.
string(REPLACE
    "#define MAX_ITERATOR_COUNT 64"
    "#define INITIAL_ITERATOR_CAPACITY 64"
    TSH_STACK_SOURCE "${TSH_STACK_SOURCE}")
string(REPLACE
    "if (self->iterators.size >= MAX_ITERATOR_COUNT) continue;"
    ""
    TSH_STACK_SOURCE "${TSH_STACK_SOURCE}")
string(FIND "${TSH_STACK_SOURCE}" "MAX_ITERATOR_COUNT" TSH_STACK_BRANCH_CAP_POSITION)
if(NOT TSH_STACK_BRANCH_CAP_POSITION LESS 0)
    message(FATAL_ERROR "Tree-sitter stack iterator branch cap was not removed")
endif()

# Keep upstream StackIterator ownership semantics: each iterator retains its
# Subtrees and releases them through ts_subtree_array_delete. The borrowed-path
# experiment corrupted allocator metadata during incremental parsing on Android.

string(REPLACE
    "array_reserve(&self->heads, 4);\n  array_reserve(&self->slices, 4);\n  array_reserve(&self->iterators, 4);"
    "array_reserve(&self->heads, 8);\n  array_reserve(&self->slices, INITIAL_ITERATOR_CAPACITY);\n  array_reserve(&self->iterators, INITIAL_ITERATOR_CAPACITY);"
    TSH_STACK_SOURCE "${TSH_STACK_SOURCE}")
string(FIND "${TSH_STACK_SOURCE}"
    "array_reserve(&self->heads, 8);\n  array_reserve(&self->slices, INITIAL_ITERATOR_CAPACITY);\n  array_reserve(&self->iterators, INITIAL_ITERATOR_CAPACITY);"
    TSH_STACK_RESERVE_PATCH_POSITION)
if(TSH_STACK_RESERVE_PATCH_POSITION LESS 0)
    message(FATAL_ERROR "Pinned Tree-sitter stack iterator reserve patch did not apply")
endif()

set(TSH_SUBTREE_COPY_OLD [=[void ts_subtree_array_copy(SubtreeArray self, SubtreeArray *dest) {
  dest->size = self.size;
  dest->capacity = self.capacity;
  dest->contents = self.contents;
  if (self.capacity > 0) {
    dest->contents = ts_calloc(self.capacity, sizeof(Subtree));
    memcpy(dest->contents, self.contents, self.size * sizeof(Subtree));
    for (uint32_t i = 0; i < self.size; i++) {
      ts_subtree_retain(*array_get(dest, i));
    }
  }
}]=])
set(TSH_SUBTREE_COPY_NEW [=[void ts_subtree_array_copy(SubtreeArray self, SubtreeArray *dest) {
  // Copy only live children; spare capacity was repeatedly allocated and zeroed
  // for every GLR iterator fork although the copied array contains no spare data.
  dest->size = self.size;
  dest->capacity = self.size;
  dest->contents = NULL;
  if (self.size > 0) {
    dest->contents = ts_malloc((size_t)self.size * sizeof(Subtree));
    memcpy(dest->contents, self.contents, (size_t)self.size * sizeof(Subtree));
    for (uint32_t i = 0; i < self.size; i++) ts_subtree_retain(*array_get(dest, i));
  }
}]=])
string(REPLACE "${TSH_SUBTREE_COPY_OLD}" "${TSH_SUBTREE_COPY_NEW}"
    TSH_SUBTREE_SOURCE "${TSH_SUBTREE_SOURCE}")
string(FIND "${TSH_SUBTREE_SOURCE}" "dest->capacity = self.size;"
    TSH_SUBTREE_COPY_PATCH_POSITION)
if(TSH_SUBTREE_COPY_PATCH_POSITION LESS 0)
    message(FATAL_ERROR "Pinned Tree-sitter subtree copy patch did not apply")
endif()

string(REPLACE
    "uint32_t halted_version_count = ts_stack_halted_version_count(self->stack);"
    "uint32_t halted_version_count = initial_version_count > 1 ? ts_stack_halted_version_count(self->stack) : 0;"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(FIND "${TSH_PARSER_SOURCE}"
    "initial_version_count > 1 ? ts_stack_halted_version_count(self->stack) : 0"
    TSH_PARSER_REDUCE_PATCH_POSITION)
if(TSH_PARSER_REDUCE_PATCH_POSITION LESS 0)
    message(FATAL_ERROR "Pinned Tree-sitter parser reduce patch did not apply")
endif()


# Parser phase telemetry: measure lexer callback execution separately from
# reduction work and parent-node construction. clock() reports process CPU
# ticks, so these counters exclude time spent blocked in the input callback.
string(REPLACE
    "#include <inttypes.h>\n"
    "#include <inttypes.h>\n#include <time.h>\n"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "  unsigned operation_count;\n"
    "  unsigned operation_count;\n  uint64_t lex_calls;\n  uint64_t lex_ticks;\n  uint64_t reduce_calls;\n  uint64_t reduce_ticks;\n  uint64_t ast_parent_nodes;\n  uint64_t ast_parent_ticks;\n  uint64_t reduce_child_visits;\n"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "static StackVersion ts_parser__reduce(\n  TSParser *self,\n  StackVersion version,\n  TSSymbol symbol,\n  uint32_t count,\n  int dynamic_precedence,\n  uint16_t production_id,\n  bool is_fragile,\n  bool end_of_non_terminal_extra\n) {\n  uint32_t initial_version_count = ts_stack_version_count(self->stack);"
    "static StackVersion ts_parser__reduce(\n  TSParser *self,\n  StackVersion version,\n  TSSymbol symbol,\n  uint32_t count,\n  int dynamic_precedence,\n  uint16_t production_id,\n  bool is_fragile,\n  bool end_of_non_terminal_extra\n) {\n#ifndef NDEBUG\n  const clock_t reduce_started = clock();\n  self->reduce_calls++;\n#endif\n  uint32_t initial_version_count = ts_stack_version_count(self->stack);"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "    MutableSubtree parent = ts_subtree_new_node(\n      symbol, &children, production_id, self->language\n    );"
    "#ifndef NDEBUG\n    const uint32_t ast_child_count = children.size;\n    const clock_t ast_started = clock();\n#endif\n    MutableSubtree parent = ts_subtree_new_node(\n      symbol, &children, production_id, self->language\n    );\n#ifndef NDEBUG\n    self->ast_parent_nodes++;\n    self->ast_parent_ticks += (uint64_t)(clock() - ast_started);\n    self->reduce_child_visits += ast_child_count;\n#endif"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "        parent = ts_subtree_new_node(\n          symbol, &next_slice_children, production_id, self->language\n        );"
    "#ifndef NDEBUG\n        const uint32_t ast_child_count = next_slice_children.size;\n        const clock_t ast_started = clock();\n#endif\n        parent = ts_subtree_new_node(\n          symbol, &next_slice_children, production_id, self->language\n        );\n#ifndef NDEBUG\n        self->ast_parent_nodes++;\n        self->ast_parent_ticks += (uint64_t)(clock() - ast_started);\n        self->reduce_child_visits += ast_child_count;\n#endif"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "  return ts_stack_version_count(self->stack) > initial_version_count\n    ? initial_version_count\n    : STACK_VERSION_NONE;\n}"
    "#ifndef NDEBUG\n  self->reduce_ticks += (uint64_t)(clock() - reduce_started);\n#endif\n  return ts_stack_version_count(self->stack) > initial_version_count\n    ? initial_version_count\n    : STACK_VERSION_NONE;\n}"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "      lookahead = ts_parser__lex(self, version, state);"
    " #ifndef NDEBUG\n      const clock_t lex_started = clock();\n      self->lex_calls++;\n#endif\n      lookahead = ts_parser__lex(self, version, state);\n#ifndef NDEBUG\n      self->lex_ticks += (uint64_t)(clock() - lex_started);\n#endif"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "  self->operation_count = 0;\n\n  if (ts_parser_has_outstanding_parse(self)) {"
    "  self->operation_count = 0;\n  if (!ts_parser_has_outstanding_parse(self)) {\n    self->lex_calls = self->lex_ticks = 0;\n    self->reduce_calls = self->reduce_ticks = 0;\n    self->ast_parent_nodes = self->ast_parent_ticks = 0;\n    self->reduce_child_visits = 0;\n  }\n\n  if (ts_parser_has_outstanding_parse(self)) {"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")
string(REPLACE
    "balance:\n  ts_assert(self->finished_tree.ptr);"
    "balance:\n#ifndef NDEBUG\n  LOG(\"parser_work lexer_calls:%\" PRIu64 \" lexer_cpu_ms:%.3f reduce_calls:%\" PRIu64 \" reduce_cpu_ms:%.3f ast_parent_nodes:%\" PRIu64 \" ast_parent_cpu_ms:%.3f reduce_child_visits:%\" PRIu64,\n    self->lex_calls, (double)self->lex_ticks * 1000.0 / CLOCKS_PER_SEC,\n    self->reduce_calls, (double)self->reduce_ticks * 1000.0 / CLOCKS_PER_SEC,\n    self->ast_parent_nodes, (double)self->ast_parent_ticks * 1000.0 / CLOCKS_PER_SEC,\n    self->reduce_child_visits);\n#endif\n  ts_assert(self->finished_tree.ptr);"
    TSH_PARSER_SOURCE "${TSH_PARSER_SOURCE}")

foreach(TSH_METRIC_ANCHOR
    "uint64_t reduce_child_visits;"
    "self->reduce_calls++;"
    "self->ast_parent_nodes++;"
    "self->lex_calls++;"
    "parser_work lexer_calls:"
    "#include <time.h>"
    "self->reduce_ticks += (uint64_t)(clock() - reduce_started);"
    "self->ast_parent_ticks += (uint64_t)(clock() - ast_started);"
    "self->reduce_child_visits += ast_child_count;")
  string(FIND "${TSH_PARSER_SOURCE}" "${TSH_METRIC_ANCHOR}" TSH_METRIC_POSITION)
  if(TSH_METRIC_POSITION LESS 0)
    message(FATAL_ERROR "Tree-sitter parser phase telemetry patch did not apply: ${TSH_METRIC_ANCHOR}")
  endif()
endforeach()

file(WRITE "${TSH_RUNTIME_PERF_OVERLAY_DIR}/alloc.c" "${TSH_ALLOC_SOURCE}")
file(WRITE "${TSH_RUNTIME_PERF_OVERLAY_DIR}/stack.c" "${TSH_STACK_SOURCE}")
file(WRITE "${TSH_RUNTIME_PERF_OVERLAY_DIR}/parser.c" "${TSH_PARSER_SOURCE}")
set(TSH_REUSABLE_NODE_OLD [=[static inline ReusableNode reusable_node_new(void) {
  return (ReusableNode) {array_new(), NULL_SUBTREE};
}]=])
set(TSH_REUSABLE_NODE_NEW [=[static inline ReusableNode reusable_node_new(void) {
  ReusableNode result = {array_new(), NULL_SUBTREE};
  // This stack survives reusable_node_clear/reset across incremental edits.
  // Reserve common nesting depth once instead of reallocating on every descent.
  array_reserve(&result.stack, 32);
  return result;
}]=])
string(REPLACE "${TSH_REUSABLE_NODE_OLD}" "${TSH_REUSABLE_NODE_NEW}"
    TSH_REUSABLE_NODE_HEADER "${TSH_REUSABLE_NODE_HEADER}")
string(FIND "${TSH_REUSABLE_NODE_HEADER}" "array_reserve(&result.stack, 32);"
    TSH_REUSABLE_NODE_PATCH_POSITION)
if(TSH_REUSABLE_NODE_PATCH_POSITION LESS 0)
    message(FATAL_ERROR "Pinned Tree-sitter reusable-node reserve patch did not apply")
endif()
file(WRITE "${TSH_RUNTIME_PERF_OVERLAY_DIR}/subtree.c" "${TSH_SUBTREE_SOURCE}")
file(WRITE "${TSH_RUNTIME_PERF_OVERLAY_DIR}/reusable_node.h" "${TSH_REUSABLE_NODE_HEADER}")

set(TSH_QUERY_STEP_FIELD_MARKER "  unsigned work_count;\n")
set(TSH_QUERY_STEP_INIT_MARKER "    .work_count = 0,\n  };\n")
set(TSH_QUERY_STEP_EXEC_RESET_MARKER "  self->work_count = 0;\n  self->query_options = NULL;\n")
set(TSH_QUERY_STEP_API_MARKER "bool ts_query_cursor_did_exceed_match_limit(const TSQueryCursor *self) {")
set(TSH_QUERY_STEP_ADVANCE_MARKER "    self->work_count += 1 + self->states.size;\n")
string(FIND "${TSH_TREE_SITTER_QUERY_SOURCE}" "${TSH_QUERY_STEP_FIELD_MARKER}" TSH_QUERY_STEP_FIELD_POSITION)
string(FIND "${TSH_TREE_SITTER_QUERY_SOURCE}" "${TSH_QUERY_STEP_INIT_MARKER}" TSH_QUERY_STEP_INIT_POSITION)
string(FIND "${TSH_TREE_SITTER_QUERY_SOURCE}" "${TSH_QUERY_STEP_EXEC_RESET_MARKER}" TSH_QUERY_STEP_EXEC_RESET_POSITION)
string(FIND "${TSH_TREE_SITTER_QUERY_SOURCE}" "${TSH_QUERY_STEP_API_MARKER}" TSH_QUERY_STEP_API_POSITION)
string(FIND "${TSH_TREE_SITTER_QUERY_SOURCE}" "${TSH_QUERY_STEP_ADVANCE_MARKER}" TSH_QUERY_STEP_ADVANCE_POSITION)

if(NOT TSH_QUERY_STEP_FIELD_POSITION LESS 0 AND
   NOT TSH_QUERY_STEP_INIT_POSITION LESS 0 AND
   NOT TSH_QUERY_STEP_EXEC_RESET_POSITION LESS 0 AND
   NOT TSH_QUERY_STEP_API_POSITION LESS 0 AND
   NOT TSH_QUERY_STEP_ADVANCE_POSITION LESS 0)
    string(REPLACE
        "${TSH_QUERY_STEP_FIELD_MARKER}"
        "  unsigned work_count;\n  uint64_t total_work_count;\n  uint64_t max_work_count;\n  bool has_work_limit;\n  bool did_exceed_step_limit;\n"
        TSH_TREE_SITTER_QUERY_SOURCE
        "${TSH_TREE_SITTER_QUERY_SOURCE}"
    )
    string(REPLACE
        "${TSH_QUERY_STEP_INIT_MARKER}"
        "    .work_count = 0,\n    .total_work_count = 0,\n    .max_work_count = 0,\n    .has_work_limit = false,\n    .did_exceed_step_limit = false,\n  };\n"
        TSH_TREE_SITTER_QUERY_SOURCE
        "${TSH_TREE_SITTER_QUERY_SOURCE}"
    )
    string(REPLACE
        "${TSH_QUERY_STEP_EXEC_RESET_MARKER}"
        "  self->work_count = 0;\n  self->total_work_count = 0;\n  self->max_work_count = 0;\n  self->has_work_limit = false;\n  self->did_exceed_step_limit = false;\n  self->query_options = NULL;\n"
        TSH_TREE_SITTER_QUERY_SOURCE
        "${TSH_TREE_SITTER_QUERY_SOURCE}"
    )
    set(TSH_QUERY_STEP_API [=[
void ts_query_cursor_set_step_limit(TSQueryCursor *self, uint64_t max_steps) {
  self->max_work_count = max_steps;
  self->has_work_limit = true;
  self->did_exceed_step_limit = self->total_work_count > max_steps;
  if (self->did_exceed_step_limit) self->halted = true;
}

uint64_t ts_query_cursor_steps_used(const TSQueryCursor *self) {
  return self->total_work_count;
}

bool ts_query_cursor_did_exceed_step_limit(const TSQueryCursor *self) {
  return self->did_exceed_step_limit;
}

]=])
    string(REPLACE
        "${TSH_QUERY_STEP_API_MARKER}"
        "${TSH_QUERY_STEP_API}\n${TSH_QUERY_STEP_API_MARKER}"
        TSH_TREE_SITTER_QUERY_SOURCE
        "${TSH_TREE_SITTER_QUERY_SOURCE}"
    )
    set(TSH_QUERY_STEP_ADVANCE [=[
    const uint64_t step_cost = UINT64_C(1) + (uint64_t)self->states.size;
    if (self->has_work_limit &&
        (self->total_work_count >= self->max_work_count ||
         step_cost > self->max_work_count - self->total_work_count)) {
      self->did_exceed_step_limit = true;
      self->halted = true;
      continue;
    }
    if (step_cost > (uint64_t)INT64_MAX - self->total_work_count) {
      self->total_work_count = (uint64_t)INT64_MAX;
    } else {
      self->total_work_count += step_cost;
    }
    self->work_count += 1 + self->states.size;
]=])
    string(REPLACE
        "${TSH_QUERY_STEP_ADVANCE_MARKER}"
        "${TSH_QUERY_STEP_ADVANCE}"
        TSH_TREE_SITTER_QUERY_SOURCE
        "${TSH_TREE_SITTER_QUERY_SOURCE}"
    )
    set(TSH_QUERY_STEP_BUDGET_OVERLAY "${TSH_RUNTIME_PERF_OVERLAY_DIR}")
    file(WRITE "${TSH_QUERY_STEP_BUDGET_OVERLAY}/query.c" "${TSH_TREE_SITTER_QUERY_SOURCE}")
    set(TSH_TREE_SITTER_LIB_SOURCE "${TSH_QUERY_STEP_BUDGET_OVERLAY}/lib.c")
    set(TSH_TREE_SITTER_HAS_EXACT_QUERY_STEP_BUDGET TRUE)
    add_compile_definitions(TSH_TREE_SITTER_HAS_EXACT_QUERY_STEP_BUDGET=1)
else()
    message(STATUS "Bundled Tree-sitter source does not expose the supported Query VM patch points; exact maxSteps is unavailable and will fail closed")
endif()



# Tree-sitter 核心头文件路径
include_directories("${TS_DIR}/lib/src")
include_directories("${TS_DIR}/lib/include")

# Detect the Tree-sitter 0.28 Query Cursor controls without forcing a
# submodule update on consumers that still build the previous API. Once the
# vendored tree-sitter-lib is upgraded, the same TSH source automatically
# enables bounded query execution and cancellation.
include(CheckCXXSourceCompiles)
# Re-evaluate the complete ABI probe after its source changes; otherwise a
# stale cached success from an earlier, weaker probe could enable the code.
unset(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS CACHE)
set(CMAKE_REQUIRED_INCLUDES "${TS_DIR}/lib/include")
check_cxx_source_compiles("
    #include <tree_sitter/api.h>
    #include <type_traits>
    using ProgressCallback = decltype(TSQueryCursorOptions::progress_callback);
    using ExpectedProgressCallback = bool (*)(TSQueryCursorState *);
    using ExecWithOptions = decltype(&ts_query_cursor_exec_with_options);
    using ExpectedExecWithOptions = void (*)(
        TSQueryCursor *, const TSQuery *, TSNode, const TSQueryCursorOptions *);
    using SetMatchLimit = decltype(&ts_query_cursor_set_match_limit);
    using DidExceedMatchLimit = decltype(&ts_query_cursor_did_exceed_match_limit);
    static_assert(std::is_same<ProgressCallback, ExpectedProgressCallback>::value,
        \"Tree-sitter progress callback signature is incompatible\");
    static_assert(std::is_same<ExecWithOptions, ExpectedExecWithOptions>::value,
        \"Tree-sitter exec-with-options signature is incompatible\");
    static_assert(sizeof(TSQueryCursorOptions) > 0, \"Query cursor options are available\");
    int main() { return 0; }
" TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
unset(CMAKE_REQUIRED_INCLUDES)
if(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS)
    add_compile_definitions(TSH_TREE_SITTER_HAS_QUERY_CURSOR_OPTIONS=1)
endif()

# ==============================================================================
# Oniguruma (鬼车) 路径配置：位于当前 cpp/oniguruma 目录
# ==============================================================================
set(ONIG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/oniguruma")
set(ONIG_SRC_DIR "${ONIG_DIR}/src")

# project-sora/oniguruma's regenc.h includes config.h directly. The upstream
# CMake project generates it from config.h.cmake.in; this project compiles the
# Oniguruma sources in its own target, so reproduce the required feature checks
# and generate the same header into our binary directory.
include(CheckIncludeFiles)
include(CheckSymbolExists)
include(CheckTypeSize)

set(PACKAGE onig)
set(PACKAGE_VERSION 6.9.10)
set(VERSION ${PACKAGE_VERSION})
set(USE_CRNL_AS_LINE_TERMINATOR 0)

check_include_files(alloca.h HAVE_ALLOCA_H)
check_include_files(stdint.h HAVE_STDINT_H)
check_include_files(sys/times.h HAVE_SYS_TIMES_H)
check_include_files(sys/time.h HAVE_SYS_TIME_H)
check_include_files(time.h HAVE_TIME_H)
check_include_files(sys/types.h HAVE_SYS_TYPES_H)
check_include_files(unistd.h HAVE_UNISTD_H)
check_include_files(inttypes.h HAVE_INTTYPES_H)
check_type_size(int SIZEOF_INT)
check_type_size(intptr_t SIZEOF_INTPTR_T)
check_type_size(long SIZEOF_LONG)
check_type_size("long long" SIZEOF_LONG_LONG)
check_type_size("void*" SIZEOF_VOIDP)
if(HAVE_TIME_H)
    check_type_size(time_t SIZEOF_TIME_T)
    check_symbol_exists(clock_gettime time.h HAVE_CLOCK_GETTIME)
endif()
if(HAVE_ALLOCA_H)
    check_symbol_exists(alloca "alloca.h" HAVE_ALLOCA)
else()
    check_symbol_exists(alloca "stdlib.h;malloc.h" HAVE_ALLOCA)
endif()

set(ONIG_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/oniguruma_config")
file(MAKE_DIRECTORY "${ONIG_CONFIG_DIR}")
configure_file(
    "${ONIG_SRC_DIR}/config.h.cmake.in"
    "${ONIG_CONFIG_DIR}/config.h"
)

# Search generated configuration before the vendored Oniguruma source tree.
include_directories(BEFORE "${ONIG_CONFIG_DIR}")
include_directories("${ONIG_SRC_DIR}")

# 引入 Windbell-TSH 自身头文件
include_directories("${CMAKE_CURRENT_SOURCE_DIR}")
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/tsh")

# 编译参数调优：保证 C++20，隐藏缺省符号，开启 -O3 优化并定义 HAVE_CONFIG_H
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden -DHAVE_CONFIG_H")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden -DHAVE_CONFIG_H")

# ==============================================================================
# 构建主库 android-tree-sitter
# ==============================================================================
add_library(android-tree-sitter SHARED
    # Tree-sitter 核心内核
    "${TSH_TREE_SITTER_LIB_SOURCE}"

    # Oniguruma 完整核心与编码表实现 (完全闭合依赖符号，避免 NDK 静态断言失败)
    "${ONIG_SRC_DIR}/regerror.c"
    "${ONIG_SRC_DIR}/regparse.c"
    "${ONIG_SRC_DIR}/regext.c"
    "${ONIG_SRC_DIR}/regcomp.c"
    "${ONIG_SRC_DIR}/regexec.c"
    "${ONIG_SRC_DIR}/reggnu.c"
    "${ONIG_SRC_DIR}/regenc.c"
    "${ONIG_SRC_DIR}/regsyntax.c"
    "${ONIG_SRC_DIR}/regtrav.c"
    "${ONIG_SRC_DIR}/regversion.c"
    "${ONIG_SRC_DIR}/st.c"
    "${ONIG_SRC_DIR}/onig_init.c"
    "${ONIG_SRC_DIR}/unicode.c"
    "${ONIG_SRC_DIR}/ascii.c"
    "${ONIG_SRC_DIR}/utf8.c"
    "${ONIG_SRC_DIR}/utf16_le.c"
    "${ONIG_SRC_DIR}/utf16_be.c"
    "${ONIG_SRC_DIR}/utf32_le.c"
    "${ONIG_SRC_DIR}/utf32_be.c"
    "${ONIG_SRC_DIR}/sjis.c"
    "${ONIG_SRC_DIR}/sjis_prop.c"
    "${ONIG_SRC_DIR}/euc_jp.c"
    "${ONIG_SRC_DIR}/euc_jp_prop.c"
    "${ONIG_SRC_DIR}/unicode_unfold_key.c"
    "${ONIG_SRC_DIR}/unicode_fold1_key.c"
    "${ONIG_SRC_DIR}/unicode_fold2_key.c"
    "${ONIG_SRC_DIR}/unicode_fold3_key.c"

    # Native large-text source adapters and bounded storage
    largefile/TextSource.cpp
    largefile/FileWindowSource.cpp
    largefile/MappedFileSource.cpp
    largefile/PieceTreeSource.cpp
    largefile/TextPartitionIndex.cpp

    # 原生 JNI 适配层
    main.cc
    ts.cc
    ts_cursor.cc
    ts_language.cc
    ts_lookahead_iterator.cc
    ts_node.cc
    ts_parser.cc
    ts_query.cc
    ts_query_cursor.cc
    ts_tags.cc
    ts_tree.cc
    utf16str/JavaUTF16String.cpp
    utf16str/JavaUTF16StringFactory.cpp
    utf16str/UTF16String.cpp
    utils/jni_string.cpp
    utils/ts_exceptions.cpp
    utils/ts_preconditions.cpp
    utils/ts_obj_utils.cpp

    # Windbell-TSH 核心实现
    tsh/WindbellTshCompiler.cpp
    tsh/WindbellTshCore.cpp
    tsh/TshRegexNative.cpp
    ts_windbell_jni.cpp
)

# Drop unreferenced code/data from the monolithic JNI library.
#
# Oniguruma is built from its upstream source files, but Windbell-TSH currently
# initializes only UTF-16 LE. Section-level garbage collection allows the linker
# to discard unused encoding implementations and other unreachable routines
# without maintaining a fragile hand-picked list of Oniguruma translation units.
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU" AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(android-tree-sitter PRIVATE
        -ffunction-sections
        -fdata-sections
    )
    target_link_options(android-tree-sitter PRIVATE
        -Wl,--gc-sections
    )
endif()

if (${CMAKE_SYSTEM_NAME} STREQUAL Android)
    find_library(log log)
    target_link_libraries(android-tree-sitter PRIVATE ${log})
endif()
# Host-side tests for storage and source adapters. Android builds only compile
# the production library; these tests run in a normal desktop CMake build.
include(CTest)
if(BUILD_TESTING AND NOT ANDROID)
    add_executable(windbell_tsh_cache_tests
        tsh/tests/NativeHighlightCacheTest.cpp
    )
    target_include_directories(windbell_tsh_cache_tests PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/tsh"
        "${CMAKE_CURRENT_SOURCE_DIR}/oniguruma/src"
        "${TS_DIR}/lib/include"
        "${JAVA_INCLUDE_PATH}"
        "${JAVA_INCLUDE_PATH2}"
    )
    target_compile_features(windbell_tsh_cache_tests PRIVATE cxx_std_20)
    add_test(NAME windbell_tsh_cache_tests COMMAND windbell_tsh_cache_tests)

    add_executable(windbell_largefile_tests
        largefile/tests/TextSourceTest.cpp
        largefile/TextSource.cpp
        largefile/ChunkedTextStore.cpp
        largefile/MappedFileSource.cpp
        largefile/FileWindowSource.cpp
        largefile/PieceTreeSource.cpp
        largefile/TextPartitionIndex.cpp
    )
    target_include_directories(windbell_largefile_tests PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/largefile"
    )
    target_compile_features(windbell_largefile_tests PRIVATE cxx_std_20)
    add_test(NAME windbell_largefile_tests COMMAND windbell_largefile_tests)
endif()
