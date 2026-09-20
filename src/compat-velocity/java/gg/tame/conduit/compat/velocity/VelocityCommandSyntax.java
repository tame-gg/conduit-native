// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandSource;
import gg.tame.conduit.api.command.CommandSyntax;
import java.util.ArrayList;
import java.util.List;

/**
 * A BrigadierCommand's own node tree as the shape Conduit declares to clients.
 *
 * <p>A plugin that went to the trouble of building a Brigadier tree has already said exactly what
 * its command takes, and Velocity sends that tree to the client. Conduit used to send one greedy
 * argument instead: never red, but the client could not complete a subcommand without asking, and
 * could not tell a wrong argument from a right one.
 *
 * <p>Brigadier's six argument types are carried across with their bounds. Anything else is a type
 * the plugin brought itself, with a parser no client has, so it becomes a string -- greedy at the
 * end of a branch, one word where more nodes follow, which keeps the nodes after it reachable.
 * Permissive rather than wrong: the client accepts what the command may refuse, and never reddens
 * a line the command would have run.
 */
final class VelocityCommandSyntax {
  private VelocityCommandSyntax() {}

  /**
   * The children of {@code node}, which are what follows the command's name -- the name itself is
   * the literal Conduit declares, under whichever alias was registered.
   */
  static List<CommandSyntax> of(CommandNode<CommandSource> node) {
    return children(node);
  }

  private static List<CommandSyntax> children(CommandNode<CommandSource> parent) {
    List<CommandSyntax> converted = new ArrayList<>();
    for (CommandNode<CommandSource> child : parent.getChildren()) {
      CommandSyntax one = one(child);
      if (one != null) converted.add(one);
    }
    return converted;
  }

  private static CommandSyntax one(CommandNode<CommandSource> node) {
    String name = node.getName();
    if (name == null || name.isBlank()) return null;
    // A redirect is a jump to a node somewhere else in the dispatcher, which may be outside this
    // command and may point back at itself. Rather than follow it -- and risk an unbounded tree --
    // the node takes free text, so everything that may follow the jump still parses.
    if (node.getRedirect() != null) {
      return new CommandSyntax.Literal(name, List.of(new CommandSyntax.Argument("arguments", CommandSyntax.Parser.greedy(), true)));
    }
    List<CommandSyntax> children = children(node);
    if (!(node instanceof ArgumentCommandNode<CommandSource, ?> argument)) return new CommandSyntax.Literal(name, children);
    // ask_server exactly where the plugin gave the node a suggestion provider of its own: that is
    // when the client has to ask, and when it does not, Brigadier's own suggestions -- a bool's
    // true/false, a literal's word -- are ones the client already works out for itself.
    boolean askServer = argument.getCustomSuggestions() != null;
    return new CommandSyntax.Argument(name, parser(argument.getType(), children.isEmpty()), askServer, children);
  }

  private static CommandSyntax.Parser parser(ArgumentType<?> type, boolean leaf) {
    return switch (type) {
      case BoolArgumentType ignored -> CommandSyntax.Parser.bool();
      case StringArgumentType string -> switch (string.getType()) {
        case SINGLE_WORD -> CommandSyntax.Parser.word();
        case QUOTABLE_PHRASE -> CommandSyntax.Parser.quotable();
        case GREEDY_PHRASE -> CommandSyntax.Parser.greedy();
      };
      // Brigadier fills an unbounded argument with its type's own extremes. Written out they are a
      // bound the client then enforces, and eight wasted bytes, so they are left off.
      case FloatArgumentType number -> CommandSyntax.Parser.range(CommandSyntax.Parser.Range.Kind.FLOAT,
          number.getMinimum() == -Float.MAX_VALUE ? null : number.getMinimum(),
          number.getMaximum() == Float.MAX_VALUE ? null : number.getMaximum());
      case DoubleArgumentType number -> CommandSyntax.Parser.range(CommandSyntax.Parser.Range.Kind.DOUBLE,
          number.getMinimum() == -Double.MAX_VALUE ? null : number.getMinimum(),
          number.getMaximum() == Double.MAX_VALUE ? null : number.getMaximum());
      case IntegerArgumentType number -> CommandSyntax.Parser.range(CommandSyntax.Parser.Range.Kind.INTEGER,
          number.getMinimum() == Integer.MIN_VALUE ? null : number.getMinimum(),
          number.getMaximum() == Integer.MAX_VALUE ? null : number.getMaximum());
      case LongArgumentType number -> CommandSyntax.Parser.range(CommandSyntax.Parser.Range.Kind.LONG,
          number.getMinimum() == Long.MIN_VALUE ? null : number.getMinimum(),
          number.getMaximum() == Long.MAX_VALUE ? null : number.getMaximum());
      // A type of the plugin's own. Greedy at the end of a branch so a value with spaces in it still
      // parses; one word where children follow, or they could never be reached.
      default -> leaf ? CommandSyntax.Parser.greedy() : CommandSyntax.Parser.word();
    };
  }
}
