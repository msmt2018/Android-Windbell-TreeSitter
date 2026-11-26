#pragma once

#include <stdint.h>
#include <tree_sitter/api.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Private TSH bridge into the pinned Tree-sitter Query VM. */
void ts_query_cursor_set_step_limit(TSQueryCursor *cursor, uint64_t max_steps);
uint64_t ts_query_cursor_steps_used(const TSQueryCursor *cursor);
bool ts_query_cursor_did_exceed_step_limit(const TSQueryCursor *cursor);

#ifdef __cplusplus
}
#endif
