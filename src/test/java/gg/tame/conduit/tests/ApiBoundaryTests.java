// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The native plugin API is what plugins compile against, so it must stand on its own: every source
 * under {@code gg/tame/conduit/api} compiles with nothing but the JDK, and none imports the rest of
 * Conduit. The README's example plugin, compiled against those classes alone, loads and enables in a
 * real proxy.
 */
public final class ApiBoundaryTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    Path root = projectRoot();
    List<Path> sources = apiSources(root);
    require(sources.size() > 20, "the API sources were found under " + root + ": " + sources.size());
    noImportLeavesTheApi(root, sources);
    Path classes = apiCompilesOnItsOwn(sources);
    theReadmeExampleLoadsAgainstTheApiAlone(root, classes);
    System.out.println("ApiBoundaryTests OK");
  }

  private static final Pattern FOREIGN_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?gg\\.tame\\.conduit\\.(?!api\\.)\\S+", Pattern.MULTILINE);

  private static void noImportLeavesTheApi(Path root, List<Path> sources) throws IOException {
    List<String> found = new ArrayList<>();
    for (Path source : sources) {
      Matcher matcher = FOREIGN_IMPORT.matcher(Files.readString(source));
      while (matcher.find()) found.add(root.relativize(source) + ": " + matcher.group().trim());
    }
    require(found.isEmpty(), "the API imports Conduit internals:\n  " + String.join("\n  ", found));
  }

  /**
   * An empty class path and source path, so anything outside the API is simply not there and every
   * reference to it is a compile error, reported with its file and line.
   */
  private static Path apiCompilesOnItsOwn(List<Path> sources) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    require(compiler != null, "a JDK compiler is available to the tests");
    Path work = TempFiles.dir("conduit-api-boundary");
    Path empty = Files.createDirectories(work.resolve("empty"));
    Path classes = Files.createDirectories(work.resolve("classes"));
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      List<String> options = List.of("--release", "21", "-proc:none", "-implicit:none",
          "-classpath", empty.toString(), "-sourcepath", empty.toString(), "-d", classes.toString());
      boolean compiled = compiler.getTask(null, files, diagnostics, options, null, files.getJavaFileObjectsFromPaths(sources)).call();
      List<String> errors = diagnostics.getDiagnostics().stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
          .map(diagnostic -> (diagnostic.getSource() == null ? "?" : diagnostic.getSource().getName()) + ":" + diagnostic.getLineNumber()
              + ": " + diagnostic.getMessage(null))
          .toList();
      require(compiled && errors.isEmpty(), "the API does not compile on its own, so it reaches outside gg.tame.conduit.api:\n  "
          + String.join("\n  ", errors));
    }
    return classes;
  }

  /**
   * The Java example and the conduit-plugin.yml in the README's "Native plugin API" section, compiled
   * with only the API on the class path, jarred as a plugin author would, and loaded by a proxy.
   */
  private static void theReadmeExampleLoadsAgainstTheApiAlone(Path root, Path apiClasses) throws Exception {
    String readme = Files.readString(root.resolve("README.md")).replace("\r\n", "\n");
    int section = readme.indexOf("\n## Native plugin API");
    require(section >= 0, "the README has a Native plugin API section");
    String descriptor = block(readme, section, "yaml");
    String source = block(readme, section, "java");
    String main = value(descriptor, "main");
    String id = value(descriptor, "id");

    Path work = TempFiles.dir("conduit-api-example");
    Path file = Files.createDirectories(work.resolve("src").resolve(main.substring(0, main.lastIndexOf('.')).replace('.', '/')))
        .resolve(main.substring(main.lastIndexOf('.') + 1) + ".java");
    Files.writeString(file, source);
    Path classes = Files.createDirectories(work.resolve("classes"));
    java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
    int status = ToolProvider.getSystemJavaCompiler().run(null, errors, errors, "--release", "21", "-proc:none",
        "-classpath", apiClasses.toString(), "-sourcepath", work.resolve("src").toString(), "-d", classes.toString(), file.toString());
    require(status == 0, "the README example compiles against the API alone, as its main " + main + ":\n"
        + errors.toString(StandardCharsets.UTF_8));

    Path plugins = Files.createDirectories(work.resolve("plugins"));
    try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve(id + ".jar"))); var compiled = Files.walk(classes)) {
      jar.putNextEntry(new JarEntry("conduit-plugin.yml"));
      jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
      for (Path entry : compiled.filter(Files::isRegularFile).toList()) {
        jar.putNextEntry(new JarEntry(classes.relativize(entry).toString().replace('\\', '/')));
        jar.write(Files.readAllBytes(entry));
        jar.closeEntry();
      }
    }
    try (Backend lobby = new Backend("lobby");
         Fixture fixture = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), plugins, AuthenticationSettings.offline(), null)) {
      require(NativeApiTests.waitFor(() -> fixture.runtime.plugins().plugin(id).isPresent(), 10_000), "the README example plugin enabled");
      require(fixture.runtime.commandManager().get("example").isPresent() && fixture.runtime.commandManager().get("ex").isPresent(),
          "and registered its command and alias");
    }
  }

  /** The first fenced block of {@code language} after {@code from}. */
  private static String block(String text, int from, String language) {
    int start = text.indexOf("```" + language + "\n", from);
    require(start >= 0, "a ```" + language + " block in the section");
    start += language.length() + 4;
    int end = text.indexOf("```", start);
    return text.substring(start, end);
  }
  private static String value(String descriptor, String key) {
    Matcher matcher = Pattern.compile("^" + key + ":\\s*(\\S+)\\s*$", Pattern.MULTILINE).matcher(descriptor);
    require(matcher.find(), "conduit-plugin.yml in the README names its " + key);
    return matcher.group(1);
  }

  /** The checkout the tests run in: the working directory or the nearest one above it holding the API. */
  private static Path projectRoot() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isDirectory(dir.resolve("src/main/java/gg/tame/conduit/api")) && Files.exists(dir.resolve("README.md"))) return dir;
    }
    throw new AssertionError("no Conduit checkout at or above " + Path.of("").toAbsolutePath());
  }
  private static List<Path> apiSources(Path root) throws IOException {
    try (var walk = Files.walk(root.resolve("src/main/java/gg/tame/conduit/api"))) {
      return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
