# TSH Parser Cancellation and Timeout API

## Active parse cancellation

`TshTreeSitterParserOwner` exposes:

- `requestCancellation()`: asynchronously asks the active Tree-sitter Native parse to stop. It returns success only when the Native parser accepted the request. If there is no active parse, it returns `TSH-PARSER-OWNER-029`.
- `requestCancellationAndWait()`: requests cancellation and blocks until the Native parser exits its parsing critical section. It also returns `TSH-PARSER-OWNER-029` when no active parse accepted cancellation.
- `TSParser.requestCancellationAndWaitIfParsing()`: lower-level boolean API indicating whether a parse was active and the cancellation request was accepted. The existing void `requestCancellationAndWait()` remains available for compatibility.

The owner acquires a Native resource lease before requesting cancellation. For asynchronous cancellation, the parse operation continues to own its own Parser lease until parsing returns. For wait cancellation, the caller's lease is retained until the wait completes. Closing the owner therefore cannot free the parser while either operation is using it. When a TSH-owned parse returns no tree, is cancelled, times out, or throws while parsing, the owner resets Tree-sitter's resumable parser state under its parse lock before allowing another document/revision to use that parser. Raw `TSParser` callers retain the lower-level resume/reset contract and must call `reset()` when they intend to start a different document.

Do not call the blocking wait method from the parse callback or from the thread that must return from the parse operation; doing so can deadlock.

## Parse progress callback

The Java binding's streaming API accepts `TSParser.ProgressCallback`; TSH exposes the platform-neutral `TshParseProgressCallback` through the snapshot-backed parser-owner overload.

- The callback receives a coarse-grained current source offset and `hasError`.
- The Java binding reports the Tree-sitter input byte offset. The TSH adapter divides by two because this binding parses UTF-16LE, and exposes UTF-16 code-unit offsets.
- Return `true` to stop the parse. The callback runs synchronously on the parsing thread and must be fast, non-blocking, and thread-safe.
- `TSH-PARSER-OWNER-039` identifies callback-requested cancellation; `TSH-PARSER-OWNER-040` reports a callback exception.
- A callback exception is captured by the TSH adapter, requests Native stop, and is returned as a structured diagnostic after the parser unwinds.

The Tree-sitter core progress callback is coarse-grained (operation-count based), not a per-token or wall-clock ticker. Native cancellation and timeout checks run before the user callback.

## Native timeout

- `setTimeoutMicros(value)` configures Tree-sitter's timeout in microseconds; zero disables it.
- `timeoutMicros()` reads the current configured value.
- Timeout changes and reads use a non-blocking owner parse-lock admission check. They return `TSH-PARSER-OWNER-038` while a parse is active instead of blocking the caller or mutating Native parser state concurrently.

## Null-tree diagnostics

Tree-sitter returns a null tree when a parse stops because of cancellation or timeout. The current Native binding does not expose a precise stop-reason flag.

The owner therefore reports:
- `TSH-PARSER-OWNER-030` when the Native cancellation-request state is set;
- `TSH-PARSER-OWNER-031` when a nonzero timeout was configured and measured parse duration reached that interval. The message explicitly describes timeout as the likely reason, not a definitive Native-reported cause;
- `TSH-PARSER-OWNER-004` for other null-tree outcomes.

Callback/provider exceptions and Native linkage failures retain their own diagnostics.

## Limitations

This API cancels an active synchronous parse; it does not introduce an asynchronous parser task/future abstraction. A cancellation request made while no Native parse is active is rejected rather than queued for a future parse. Native cancellation is cooperative and may not be immediate. The TSH owner resets the resumable Native parser state under its parse lock after cancellation, timeout, callback stop, or parse failure, so the next document/revision does not accidentally continue the interrupted parse.
