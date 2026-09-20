// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.command;

import java.util.List;

/**
 * A command's shape as the client parses it, below the command's own name.
 *
 * <p>A 1.13+ client is sent a Brigadier tree and parses every line against it before sending it.
 * What is not in that tree is a syntax error to the client, shown in red, however well the proxy
 * would have run it. A command that declares nothing is given one greedy argument instead, which
 * accepts anything and asks Conduit for its completions -- correct, and all a command whose shape
 * Conduit cannot know can be given. Declaring the real shape buys the rest: the client completes
 * subcommands without a round trip, and it can tell a wrong argument from a right one.
 *
 * <p>These are the parsers every client back to 1.13 has, and the only ones Brigadier itself
 * offers. A parser Conduit has no counterpart for is written as a string, which is permissive
 * rather than wrong: the client accepts what the command may refuse, and never reddens what it
 * would have run.
 */
public sealed interface CommandSyntax {
  String name();

  List<CommandSyntax> children();

  /** A fixed word: a subcommand, a mode, a configured name. */
  record Literal(String name, List<CommandSyntax> children) implements CommandSyntax {
    public Literal(String name) { this(name, List.of()); }

    public Literal {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("a literal needs a name");
      children = List.copyOf(children == null ? List.of() : children);
    }
  }

  /**
   * A value the client parses with {@code parser}.
   *
   * <p>{@code askServer} sets Brigadier's {@code minecraft:ask_server}, which is what makes the
   * client send a tab-complete request rather than completing locally. It is the only way to offer
   * something that changes while a client is connected -- player names, say -- because the tree
   * itself is sent on join and on a change, never per keystroke.
   */
  record Argument(String name, Parser parser, boolean askServer, List<CommandSyntax> children) implements CommandSyntax {
    public Argument(String name, Parser parser, boolean askServer) { this(name, parser, askServer, List.of()); }

    public Argument {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("an argument needs a name");
      if (parser == null) throw new IllegalArgumentException("an argument needs a parser");
      children = List.copyOf(children == null ? List.of() : children);
    }
  }

  /** How the client reads an argument's text. */
  sealed interface Parser {
    /** {@code true} or {@code false}, which the client also suggests by itself. */
    record Bool() implements Parser {}

    /**
     * A number. {@code min} and {@code max} are null where the command sets no bound; a bound is
     * written at {@code kind}'s own width, so an integer bound never loses its ends to a double.
     */
    record Range(Kind kind, Number min, Number max) implements Parser {
      public enum Kind { FLOAT, DOUBLE, INTEGER, LONG }

      public Range {
        if (kind == null) throw new IllegalArgumentException("a range needs a kind");
      }
    }

    /** Text: one word, a word or a quoted phrase, or the whole rest of the line. */
    record Phrase(Width width) implements Parser {
      public enum Width { WORD, QUOTABLE, GREEDY }

      public Phrase {
        if (width == null) throw new IllegalArgumentException("a phrase needs a width");
      }
    }

    static Parser bool() { return new Bool(); }
    static Parser word() { return new Phrase(Phrase.Width.WORD); }
    static Parser quotable() { return new Phrase(Phrase.Width.QUOTABLE); }
    /** Takes the rest of the line, so nothing may follow it. */
    static Parser greedy() { return new Phrase(Phrase.Width.GREEDY); }
    static Parser range(Range.Kind kind, Number min, Number max) { return new Range(kind, min, max); }
  }
}
