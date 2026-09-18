// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the client &times; backend compatibility matrix from the live registries.
 *
 * <p>The matrix is computed, never hand-maintained, so it cannot drift from what
 * the proxy will actually do. Every cell is the answer {@link CompatibilityRegistry}
 * would give a real session with that pair of protocol numbers.
 *
 * <p>The matrix is deliberately asymmetric. Cell (a, b) asks "can a client
 * speaking a reach a backend speaking b", which is a different question from
 * cell (b, a); a translator is registered per ordered pair and is never mirrored.
 */
public final class CompatibilityMatrix {
  private CompatibilityMatrix() {}

  /** D = direct, T = translated, U = unsupported. Lowercase marks a PARTIAL path. */
  public static char cell(int clientProtocol, int backendProtocol) {
    CompatibilityEntry entry = CompatibilityRegistry.resolve(clientProtocol, backendProtocol);
    char symbol = switch (entry.support()) {
      case DIRECT -> 'D';
      case TRANSLATED, PARTIAL -> 'T';
      case UNSUPPORTED -> 'U';
    };
    return entry.completeness() == CompatibilityCompleteness.PARTIAL
        ? Character.toLowerCase(symbol)
        : symbol;
  }

  /** Protocol numbers that have a codec, ascending. */
  public static List<Integer> axis() {
    List<Integer> numbers = new ArrayList<>(ProtocolDefinition.all().keySet());
    numbers.sort(Integer::compareTo);
    return List.copyOf(numbers);
  }

  /** The matrix as a monospace grid, rows = client protocol, columns = backend. */
  public static String render() {
    List<Integer> axis = axis();
    StringBuilder text = new StringBuilder();
    text.append("client\\backend");
    for (int backend : axis) text.append(' ').append(backend);
    text.append('\n');
    for (int client : axis) {
      text.append(String.format("%-14d", client));
      for (int backend : axis) {
        text.append(' ').append(String.format("%3c", cell(client, backend)));
      }
      text.append('\n');
    }
    return text.toString();
  }

  /** Every pair Conduit will actually carry, as human-readable lines. */
  public static List<String> selectablePairs() {
    List<String> lines = new ArrayList<>();
    for (int client : axis()) {
      for (int backend : axis()) {
        CompatibilityEntry entry = CompatibilityRegistry.resolve(client, backend);
        if (!entry.selectable()) continue;
        lines.add(client + " → " + backend + "  " + entry.support() + "/" + entry.completeness()
            + "  " + entry.notes());
      }
    }
    return List.copyOf(lines);
  }

  /** Counts by symbol, for a quick sense of how much of the matrix is filled in. */
  public static String summary() {
    int direct = 0, translated = 0, unsupported = 0, partial = 0;
    for (int client : axis()) {
      for (int backend : axis()) {
        CompatibilityEntry entry = CompatibilityRegistry.resolve(client, backend);
        switch (entry.support()) {
          case DIRECT -> direct++;
          case TRANSLATED, PARTIAL -> translated++;
          case UNSUPPORTED -> unsupported++;
        }
        if (entry.completeness() == CompatibilityCompleteness.PARTIAL) partial++;
      }
    }
    int size = axis().size();
    return "protocols with codecs: " + size
        + "; matrix cells: " + (size * size)
        + "; direct: " + direct
        + "; translated: " + translated
        + "; unsupported: " + unsupported
        + "; of which partial: " + partial;
  }
}
