/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

package com.zerostudio.idea.treesitter;

import com.zerostudio.idea.treesitter.annotations.GenerateNativeHeaders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bounded native tag extraction built on Tree-sitter query matches.
 *
 * <p>This API deliberately returns ordinary query matches. Language-specific
 * tag semantics (definition/reference classification and metadata) remain
 * the responsibility of the query and its capture names; this layer owns
 * bounded execution and safe JNI transfer only.
 */
public final class TSTags {

  public static final int MAX_RESULTS = 250_000;
  public static final int MAX_PENDING_MATCHES = 1_000_000;

  private TSTags() {
  }

  /**
   * Extract query matches from an absolute byte range within {@code root}.
   *
   * <p>No partial result is returned when either result or pending-match
   * limits are exceeded. The query and root must remain valid for this call.
   * Tree-sitter's public byte offsets are unsigned 32-bit values, so Java
   * {@code long} is used to preserve the full native offset domain.
   *
   * @param query compiled query
   * @param root syntax node used as the query root
   * @param startByte absolute inclusive start byte, in document coordinates
   * @param endByte absolute exclusive end byte, in document coordinates
   * @param maxResults maximum number of completed matches to materialize
   * @param maxPendingMatches Tree-sitter in-progress match limit
   * @return query matches, including their original pattern and capture data
   */
  public static TSQueryMatch[] extract(
      TSQuery query,
      TSNode root,
      long startByte,
      long endByte,
      int maxResults,
      int maxPendingMatches) {
    if (query == null || !query.canAccess()) {
      throw new IllegalArgumentException("Cannot extract tags with an invalid query");
    }
    if (root == null || !root.canAccess() || root.getTree() == null
        || !root.getTree().canAccess() || root.hasChanges()) {
      throw new IllegalArgumentException("Cannot extract tags from an invalid or changed root");
    }
    if (startByte < 0 || endByte < startByte || endByte > 0xFFFF_FFFFL) {
      throw new IllegalArgumentException("Invalid absolute byte range");
    }
    if (maxResults < 1 || maxResults > MAX_RESULTS
        || maxPendingMatches < 1 || maxPendingMatches > MAX_PENDING_MATCHES) {
      throw new IllegalArgumentException("Tag query limits exceed the supported native budget");
    }
    return Native.extract(
        query.getNativeObject(), root, startByte, endByte,
        maxResults, maxPendingMatches);
  }

  /**
   * Convert raw query matches into language-neutral tags using the conventional
   * Tree-sitter tags capture names: {@code definition}, {@code reference},
   * {@code name}, {@code scope}, and {@code doc}.
   *
   * <p>A match becomes one tag only when it contains exactly one of
   * {@code @definition} or {@code @reference}. Matches without either role are
   * ignored; matches containing both are rejected as ambiguous. No source text
   * is copied and no language-specific naming heuristic is applied.
   */
  public static List<TSTag> toTags(TSQuery query, TSQueryMatch[] matches) {
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(matches, "matches");
    if (!query.canAccess()) {
      throw new IllegalArgumentException("Cannot map tags with an invalid query");
    }

    final String[] captureNames = query.getCaptureNames();
    final List<TSTag> tags = new ArrayList<>(matches.length);
    for (TSQueryMatch match : matches) {
      if (match == null) {
        throw new IllegalArgumentException("Tag query matches cannot contain null");
      }
      final TSNode[] roleNodes = new TSNode[2];
      TSNode nameNode = null;
      TSNode scopeNode = null;
      TSNode documentationNode = null;
      final TSQueryCapture[] captures = match.getCaptures();
      if (captures == null) {
        continue;
      }

      for (TSQueryCapture capture : captures) {
        if (capture == null || capture.getNode() == null
            || !capture.getNode().canAccess()) {
          throw new IllegalStateException("Tag query contains an invalid capture node");
        }
        final int captureId = capture.getIndex();
        if (captureId < 0 || captureId >= captureNames.length) {
          throw new IllegalStateException("Tag query capture id is outside the query table");
        }
        String captureName = captureNames[captureId];
        if (captureName == null) {
          continue;
        }
        if (captureName.startsWith("@")) {
          captureName = captureName.substring(1);
        }
        switch (captureName) {
          case "definition":
            roleNodes[0] = uniqueRole(roleNodes[0], capture.getNode(), "definition");
            break;
          case "reference":
            roleNodes[1] = uniqueRole(roleNodes[1], capture.getNode(), "reference");
            break;
          case "name":
            nameNode = firstNode(nameNode, capture.getNode());
            break;
          case "scope":
            scopeNode = firstNode(scopeNode, capture.getNode());
            break;
          case "doc":
            documentationNode = firstNode(documentationNode, capture.getNode());
            break;
          default:
            // Other query captures remain available on the original match.
            break;
        }
      }

      if (roleNodes[0] != null && roleNodes[1] != null) {
        throw new IllegalStateException(
            "A tag query match cannot be both a definition and a reference");
      }
      final TSTag.Kind kind;
      final TSNode subject;
      if (roleNodes[0] != null) {
        kind = TSTag.Kind.DEFINITION;
        subject = roleNodes[0];
      } else if (roleNodes[1] != null) {
        kind = TSTag.Kind.REFERENCE;
        subject = roleNodes[1];
      } else {
        continue;
      }
      tags.add(new TSTag(
          kind, subject, nameNode, scopeNode, documentationNode,
          match.getPatternIndex(), match.getId()));
    }
    return Collections.unmodifiableList(tags);
  }

  private static TSNode firstNode(TSNode current, TSNode candidate) {
    return current == null ? candidate : current;
  }

  private static TSNode uniqueRole(TSNode current, TSNode candidate, String role) {
    if (current != null) {
      throw new IllegalStateException("A tag match contains multiple @" + role + " captures");
    }
    return candidate;
  }

  @GenerateNativeHeaders(fileName = "tags")
  private static final class Native {
    static native TSQueryMatch[] extract(
        long query,
        TSNode root,
        long startByte,
        long endByte,
        int maxResults,
        int maxPendingMatches);
  }
}
