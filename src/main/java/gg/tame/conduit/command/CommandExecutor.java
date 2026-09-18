// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

import java.util.List;

@FunctionalInterface
public interface CommandExecutor {
  void execute(CommandSource source, List<String> arguments);
}
