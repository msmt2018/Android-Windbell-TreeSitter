package com.zerostudio.idea.treesitter.tsh.api;

import com.zerostudio.idea.treesitter.predicate.TSPredicateHandler;

/**
 * Java interop bridge for TSPredicateHandler's JavaBean getter.
 *
 * Kotlin synthetic-property resolution differs across the project's compiler
 * and source-set configuration; keeping the invocation in Java makes the
 * interface contract explicit without reflection.
 */
final class TSPredicateHandlerCompat {
    private TSPredicateHandlerCompat() {}

    static String[] supportedPredicates(TSPredicateHandler handler) {
        return handler.getSupportedPredicates();
    }
}
