// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import java.util.List;

@FunctionalInterface
public interface TabCompleter {
  List<String> complete(CommandSource source, List<String> arguments);
  /** With the arguments also exactly as typed, runs of spaces kept; see {@link ParsedCommand#argumentText}. */
  default List<String> complete(CommandSource source, List<String> arguments, String text) { return complete(source, arguments); }
}
