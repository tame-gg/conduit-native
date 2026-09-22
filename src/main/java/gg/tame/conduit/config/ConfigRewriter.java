// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import gg.tame.conduit.log.ConduitLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings an operator's {@code conduit.toml} up to the layout the running version ships, keeping every
 * value they set.
 *
 * <p>{@link ConfigMigrator} can only append: a setting introduced later arrives at the bottom of the
 * file, under its own bare section header, in no relation to the rest. After a few versions the file
 * is the shipped layout followed by a scrapheap, and there is no way to reorganise it -- rewriting it
 * from the template is what loses the operator's values, which is the one thing that must not happen.
 *
 * <p>So the template is rendered rather than copied. Every setting line in it is looked up in the
 * operator's file, and where they had a value it is theirs that gets written. What that gives is the
 * shipped grouping, spacing and comments, with the operator's values in it:
 *
 * <ul>
 *   <li>A value they set stays set, with the text they wrote, in the template's place for it.
 *   <li>A setting they had commented out, or never had, keeps the template's own line -- commented,
 *       so it goes on meaning "the default".
 *   <li>Their {@code [servers.*]} blocks replace the template's example ones entirely. A server they
 *       did not configure must not appear, and one they did must not disappear.
 *   <li>A setting the template has no line for -- their own addition, or one from a version newer
 *       than this build -- is carried to the end under a header that says so, rather than dropped.
 * </ul>
 *
 * <p>The file it replaces is kept as {@code conduit.toml.bak-<schema>} whatever happens, so the worst
 * case is a file to copy back from rather than a configuration to write again.
 */
public final class ConfigRewriter {
  /** Sections whose contents are the operator's alone, copied over rather than rendered. */
  private static final String SERVERS_PREFIX = "servers.";
  private static final String FORCED_HOSTS = "forced-hosts";

  private ConfigRewriter() {}

  /** What a rewrite did, for the caller to log and for the tests to assert on. */
  public record Result(boolean rewritten, int fromSchema, int toSchema, Path backup, List<String> carried) {
    public Result {
      carried = List.copyOf(carried);
    }

    static Result unchanged(int schema) {
      return new Result(false, schema, schema, null, List.of());
    }
  }

  /**
   * Rewrites {@code file} into the shipped layout when it was written for an older schema, or when the
   * shipped file has a setting -- live or commented out -- that this one has no line for.
   *
   * <p>The second case is what makes a new setting reach existing files without anyone remembering to
   * bump the schema: {@code [forced-hosts]} and {@code favicon-policy} both shipped at schema 5, and a
   * file already at 5 would never have been shown either.
   *
   * <p>A file at a newer schema than this build knows is left alone: rendering a newer file through an
   * older template is how a setting this build has never heard of would get moved somewhere it does
   * not belong.
   */
  public static Result rewrite(Path file) throws IOException {
    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("config file missing: " + file);
    List<String> existing = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> template = ConfigTemplate.text().lines().toList();
    int from = ConfigTemplate.schemaVersionOf(existing);
    int to = ConfigTemplate.schemaVersion();
    if (from > to || (from == to && keys(existing).containsAll(keys(template)))) return Result.unchanged(from);

    Map<String, String> values = settings(existing);
    List<String> serverBlocks = blocks(existing, section -> section.startsWith(SERVERS_PREFIX));
    List<String> forcedHosts = blocks(existing, FORCED_HOSTS::equals);
    Set<String> used = new LinkedHashSet<>();
    List<String> rendered = render(template, values, serverBlocks, forcedHosts, used);

    List<String> carried = new ArrayList<>();
    for (Map.Entry<String, String> setting : values.entrySet()) {
      String key = setting.getKey();
      // Servers and forced hosts were copied wholesale, and the schema number is Conduit's own -- the
      // template always writes the current one, so the old value is spent, not lost.
      if (used.contains(key) || owned(key) || key.equals("ops.schema-version")) continue;
      carried.add(key);
    }
    if (!carried.isEmpty()) append(rendered, carried, values);

    // Written to a sibling and moved into place, so an interrupted write cannot leave a half a
    // configuration where the configuration was.
    Path backup = file.resolveSibling(file.getFileName() + ".bak-" + from);
    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
    Path pending = file.resolveSibling(file.getFileName() + ".rewriting");
    Files.write(pending, rendered, StandardCharsets.UTF_8);
    Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
    return new Result(true, from, to, backup, carried);
  }

  /** Logs what a rewrite did, in the terms an operator cares about. */
  public static void report(Path file, Result result) {
    if (!result.rewritten()) return;
    String why = result.fromSchema() == result.toSchema()
        ? " to add the settings this version ships"
        : " to the layout of configuration schema " + result.toSchema() + " (it was written for " + result.fromSchema() + ")";
    ConduitLog.info("Updated " + file.getFileName() + why + ". Every value you had set was kept; the file"
        + " you had is " + result.backup().getFileName() + ".");
    if (!result.carried().isEmpty()) {
      ConduitLog.warn("These settings are not part of schema " + result.toSchema() + ", so they were moved to"
          + " the end of " + file.getFileName() + " rather than dropped: " + String.join(", ", result.carried()));
    }
  }

  /**
   * Renders the template, substituting the operator's values.
   *
   * <p>A commented setting line is recognised too, because that is how the template offers a default:
   * where the operator had that setting set, the line is written uncommented with their value.
   */
  private static List<String> render(List<String> template, Map<String, String> values,
      List<String> serverBlocks, List<String> forcedHosts, Set<String> used) {
    List<String> out = new ArrayList<>(template.size() + 32);
    String section = "";
    boolean serversWritten = false;
    boolean forcedHostsWritten = false;
    // Where in out a commented-out header such as "# [metrics]" was written, until a value under it
    // is. A setting the operator had there is written live, and it only belongs to that section if
    // the header is live too: left commented, the value landed in whichever section came before and
    // was ignored there as unknown -- [permissions] operators under [maintenance], for one.
    int commentedHeader = -1;
    for (String raw : template) {
      String line = raw.strip();
      String header = sectionOf(line);
      if (header != null) {
        section = header;
        commentedHeader = line.startsWith("#") ? out.size() : -1;
      }
      if (section.equals(FORCED_HOSTS) && !forcedHosts.isEmpty()) {
        // The operator's hosts where the template shows its example ones. Only the header and the
        // example entries are replaced: the banner and prose that follow belong to the next area.
        if (header != null) {
          out.addAll(forcedHosts);
          forcedHostsWritten = true;
          continue;
        }
        if (isEntry(line)) continue;
      }
      if (section.startsWith(SERVERS_PREFIX)) {
        // The operator's servers, once, in place of the template's example blocks. Everything the
        // template says about servers is example text and none of it is theirs.
        if (!serversWritten) {
          out.addAll(serverBlocks);
          serversWritten = true;
        }
        continue;
      }
      String key = keyOf(section, line);
      if (key == null || !values.containsKey(key)) {
        out.add(raw);
        continue;
      }
      used.add(key);
      if (commentedHeader >= 0) {
        out.set(commentedHeader, indentOf(out.get(commentedHeader)) + "[" + section + "]");
        commentedHeader = -1;
      }
      String name = line.startsWith("#") ? line.substring(1).strip() : line;
      name = name.substring(0, name.indexOf('=')).strip();
      out.add(indentOf(raw) + name + " = " + values.get(key));
    }
    if (!serversWritten) out.addAll(serverBlocks);
    if (!forcedHostsWritten && !forcedHosts.isEmpty()) {
      out.add("");
      out.addAll(forcedHosts);
    }
    return out;
  }

  /** The section a live or commented-out header line opens, or null when it is not a header. */
  private static String sectionOf(String line) {
    if (line.startsWith("[") && line.endsWith("]")) return line.substring(1, line.length() - 1);
    // A whole commented-out section, such as [metrics] or the example server.
    if (line.startsWith("# [") && line.endsWith("]")) return line.substring(3, line.length() - 1);
    return null;
  }

  /** Sections whose contents are the operator's alone, copied over rather than rendered. */
  private static boolean owned(String key) {
    return key.startsWith(SERVERS_PREFIX) || key.startsWith(FORCED_HOSTS + ".");
  }

  /**
   * Every {@code section.key} these lines mention, set or commented out -- what "this file already has
   * a line for" means when deciding whether the shipped file offers something it does not.
   */
  private static Set<String> keys(List<String> lines) {
    Set<String> keys = new LinkedHashSet<>();
    String section = "";
    for (String raw : lines) {
      String line = raw.strip();
      String header = sectionOf(line);
      if (header != null) {
        section = header;
        continue;
      }
      String key = keyOf(section, line);
      if (key != null && !owned(key)) keys.add(key);
    }
    return keys;
  }

  /** A {@code key = value} line, live or commented, whose key may be quoted as a hostname is. */
  private static boolean isEntry(String line) {
    String body = line.startsWith("#") ? line.substring(1).strip() : line;
    int equals = body.indexOf('=');
    if (equals < 1) return false;
    String name = body.substring(0, equals).strip();
    return name.matches("\"[^\"]+\"|[A-Za-z0-9_.-]+") && isValue(body.substring(equals + 1).strip());
  }

  private static void append(List<String> out, List<String> carried, Map<String, String> values) {
    // Grouped back under their sections, because a bare key = value with no [section] above it is not
    // loadable TOML and this file has to still start Conduit.
    Map<String, List<String>> bySection = new LinkedHashMap<>();
    for (String key : carried) {
      int dot = key.lastIndexOf('.');
      bySection.computeIfAbsent(key.substring(0, dot), ignored -> new ArrayList<>())
          .add(key.substring(dot + 1) + " = " + values.get(key));
    }
    out.add("");
    out.add("");
    out.add("# ------------------------------------------------------------------------");
    out.add("#  Settings this version of Conduit does not read.");
    out.add("#  They were in the file before it was brought up to this layout, and are kept");
    out.add("#  here so nothing you wrote is lost. A newer Conduit may claim them; if one");
    out.add("#  does not, they are safe to delete.");
    out.add("# ------------------------------------------------------------------------");
    for (Map.Entry<String, List<String>> section : bySection.entrySet()) {
      out.add("");
      out.add("[" + section.getKey() + "]");
      out.addAll(section.getValue());
    }
  }

  /**
   * The {@code section.key} a line sets, whether it is live or commented out, or null when the line
   * is not a setting at all. Only a comment of the form {@code # key = value} counts, so the prose
   * above a setting is never mistaken for one.
   */
  private static String keyOf(String section, String line) {
    if (section.isEmpty()) return null;
    boolean commented = line.startsWith("#");
    String body = commented ? line.substring(1).strip() : line;
    if (body.isEmpty() || body.startsWith("#") || body.startsWith("[")) return null;
    int equals = body.indexOf('=');
    if (equals < 1) return null;
    String name = body.substring(0, equals).strip();
    // A key is one bare word; a sentence before an = is prose.
    if (!name.matches("[A-Za-z0-9_.-]+")) return null;
    // And prose can follow one too. The template explains translation with the sentence
    // "Set enabled = false for native-only translation and accept the drops", which read as a setting
    // called enabled -- so the line was rewritten with the value [maintenance] happened to have for
    // its own enabled, both duplicating that setting and destroying the sentence. A commented line is
    // only a setting when what follows the = is a whole TOML value and nothing else.
    if (commented && !isValue(body.substring(equals + 1).strip())) return null;
    // Conduit's own bookkeeping, and the one thing a rewrite must not carry over: keeping the old
    // number would leave the file claiming the schema it was just brought up from, so this would run
    // again on every start and never finish.
    if (section.equals("ops") && name.equals("schema-version")) return null;
    return section + "." + name;
  }

  /** Whether this is a complete TOML scalar or array, with nothing after it. */
  private static boolean isValue(String text) {
    if (text.isEmpty()) return false;
    if (text.equals("true") || text.equals("false")) return true;
    if (text.startsWith("\"")) return text.length() > 1 && text.endsWith("\"") && text.indexOf('"', 1) == text.length() - 1;
    if (text.startsWith("[")) return text.endsWith("]");
    return text.matches("[+-]?[0-9][0-9_]*(\\.[0-9_]+)?([eE][+-]?[0-9]+)?");
  }

  private static String indentOf(String raw) {
    int index = 0;
    while (index < raw.length() && (raw.charAt(index) == ' ' || raw.charAt(index) == '\t')) index++;
    return raw.substring(0, index);
  }

  /** Every setting the operator actually set, as {@code section.key} to the text they wrote. */
  private static Map<String, String> settings(List<String> lines) {
    Map<String, String> values = new LinkedHashMap<>();
    String section = "";
    for (String raw : lines) {
      String line = withoutComment(raw).strip();
      if (line.isEmpty()) continue;
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1);
        continue;
      }
      int equals = line.indexOf('=');
      if (equals < 1 || section.isEmpty()) continue;
      values.put(section + "." + line.substring(0, equals).strip(), line.substring(equals + 1).strip());
    }
    return values;
  }

  /**
   * The operator's blocks for the sections {@code wanted} accepts -- {@code [servers.*]},
   * {@code [forced-hosts]} -- verbatim, comments and all.
   *
   * <p>Copied rather than rendered: a backend or a hostname is the operator's own, the template has
   * nothing to say about it beyond an example, and whatever they wrote above one is theirs to keep.
   */
  private static List<String> blocks(List<String> lines, java.util.function.Predicate<String> wanted) {
    List<String> blocks = new ArrayList<>();
    List<String> pendingComments = new ArrayList<>();
    boolean inServer = false;
    for (String raw : lines) {
      String line = raw.strip();
      boolean header = line.startsWith("[") && line.endsWith("]");
      if (header) {
        inServer = wanted.test(line.substring(1, line.length() - 1));
        if (inServer) {
          if (!blocks.isEmpty()) blocks.add("");
          blocks.addAll(pendingComments);
          blocks.add(raw);
        }
        pendingComments.clear();
        continue;
      }
      if (line.startsWith("#")) {
        // Held: a comment belongs to whatever comes after it, which may be a server block.
        pendingComments.add(raw);
        continue;
      }
      if (line.isEmpty()) {
        pendingComments.clear();
        continue;
      }
      pendingComments.clear();
      if (inServer) blocks.add(raw);
    }
    return blocks;
  }

  /** The line up to a {@code #} outside a quoted string, matching {@link ConfigurationLoader}. */
  private static String withoutComment(String line) {
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char current = line.charAt(index);
      if (current == '"') quoted = !quoted;
      else if (current == '\\' && quoted) index++;
      else if (current == '#' && !quoted) return line.substring(0, index);
    }
    return line;
  }
}
