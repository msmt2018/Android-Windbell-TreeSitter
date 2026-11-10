/*
 *  This file is part of android-tree-sitter.
 *
 *  android-tree-sitter library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

package com.zerostudio.idea.treesitter;

import java.util.Objects;

/**
 * A language-neutral symbol tag produced from Tree-sitter's conventional
 * tags-query captures.
 *
 * <p>The node objects refer to the originating syntax tree. Their lifetime
 * therefore follows that tree; this value does not copy source text.
 */
public final class TSTag {

  public enum Kind {
    DEFINITION,
    REFERENCE,
    UNKNOWN
  }

  private final Kind kind;
  private final TSNode subject;
  private final TSNode name;
  private final TSNode scope;
  private final TSNode documentation;
  private final int patternIndex;
  private final int matchId;

  TSTag(
      Kind kind,
      TSNode subject,
      TSNode name,
      TSNode scope,
      TSNode documentation,
      int patternIndex,
      int matchId) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.subject = Objects.requireNonNull(subject, "subject");
    this.name = name;
    this.scope = scope;
    this.documentation = documentation;
    this.patternIndex = patternIndex;
    this.matchId = matchId;
  }

  public Kind getKind() {
    return kind;
  }

  /** The definition or reference node captured by the tags query. */
  public TSNode getSubject() {
    return subject;
  }

  /** The identifier/name node captured as {@code @name}, if present. */
  public TSNode getName() {
    return name;
  }

  /** The enclosing scope captured as {@code @scope}, if present. */
  public TSNode getScope() {
    return scope;
  }

  /** Documentation node captured as {@code @doc}, if present. */
  public TSNode getDocumentation() {
    return documentation;
  }

  public int getPatternIndex() {
    return patternIndex;
  }

  public int getMatchId() {
    return matchId;
  }

  public int getStartByte() {
    return subject.getStartByte();
  }

  public int getEndByte() {
    return subject.getEndByte();
  }
}
