// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.zip.GZIPOutputStream;

/**
 * Everything the console shows, kept in {@code logs/latest.log}, the way Velocity keeps it.
 *
 * <p>Conduit wrote no log file, so a session was gone the moment its window closed -- which is when
 * somebody asks for it in a bug report. What is kept is the console itself: standard output and
 * standard error are copied into the file as they are written, so Conduit's own lines, ViaVersion's,
 * plugins', command replies and stack traces are all there, in the order they appeared, without the
 * colour codes. On start, the last session's {@code latest.log} is compressed to
 * {@code logs/<date>-<n>.log.gz}, dated the day it was last written, numbered from 1 within that day.
 *
 * <p>A log that cannot be written never stops the proxy: the console goes on as before and one line
 * says why there is no file.
 */
public final class ConduitFileLog {
  /**
   * Set once the console is being copied. A system property rather than a field, because the jar's
   * bootstrap installs this and may then start the proxy from a class loader of its own, whose copy of
   * this class would otherwise copy the console a second time.
   */
  private static final String INSTALLED = "conduit.log.installed";

  private ConduitFileLog() {}

  /** Rotates the previous log and starts copying the console into {@code directory/latest.log}. */
  public static synchronized void install(Path directory) {
    if (System.getProperty(INSTALLED) != null) return;
    System.setProperty(INSTALLED, directory.toString());
    try {
      Files.createDirectories(directory);
      Path latest = directory.resolve("latest.log");
      if (Files.isRegularFile(latest)) archive(latest, directory);
      OutputStream file = Files.newOutputStream(latest, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      Plain plain = new Plain(file);
      System.setOut(new PrintStream(new Tee(System.out, plain), true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(new Tee(System.err, plain), true, StandardCharsets.UTF_8));
      Runtime.getRuntime().addShutdownHook(new Thread(plain::closeQuietly, "conduit-log-close"));
    } catch (IOException | RuntimeException unwritable) {
      System.err.println("Could not keep a log in " + directory + ", so this session is on the console only: "
          + unwritable.getMessage());
    }
  }

  /** Compresses the last session's log to the first free {@code <date>-<n>.log.gz}. */
  private static void archive(Path latest, Path directory) throws IOException {
    LocalDate day = LocalDate.ofInstant(Files.getLastModifiedTime(latest).toInstant(), ZoneId.systemDefault());
    int number = 1;
    Path target;
    do {
      target = directory.resolve(day + "-" + number++ + ".log.gz");
    } while (Files.exists(target));
    try (InputStream in = Files.newInputStream(latest);
         OutputStream out = new GZIPOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
      in.transferTo(out);
    }
    Files.delete(latest);
  }

  /** Writes to the console as before, and a copy to the file. */
  private static final class Tee extends OutputStream {
    private final PrintStream console;
    private final Plain file;

    Tee(PrintStream console, Plain file) {
      this.console = console;
      this.file = file;
    }

    @Override public void write(int b) {
      console.write(b);
      file.write(b);
    }

    @Override public void write(byte[] bytes, int offset, int length) {
      console.write(bytes, offset, length);
      for (int index = offset; index < offset + length; index++) file.write(bytes[index]);
    }

    @Override public void flush() {
      console.flush();
      file.flush();
    }
  }

  /**
   * The file side: the console's ANSI colour sequences taken out, since a log pasted into a bug report
   * full of escape codes is unreadable, and flushed at the end of every line so a crash loses nothing
   * already said. Both streams write here, so it is one lock for both.
   */
  private static final class Plain {
    private final OutputStream out;
    /** 0 outside an escape, 1 just after ESC, 2 inside a CSI sequence until its final letter. */
    private int escape;
    private boolean broken;

    Plain(OutputStream out) { this.out = out; }

    synchronized void write(int b) {
      if (broken) return;
      int value = b & 0xFF;
      if (escape == 1) {
        escape = value == '[' ? 2 : 0;
        return;
      }
      if (escape == 2) {
        if (value >= 0x40 && value <= 0x7E) escape = 0;
        return;
      }
      if (value == 0x1B) {
        escape = 1;
        return;
      }
      try {
        out.write(value);
        if (value == '\n') out.flush();
      } catch (IOException failed) {
        // A full disk ends the file, not the proxy; the console carries on.
        broken = true;
      }
    }

    synchronized void flush() {
      if (broken) return;
      try { out.flush(); } catch (IOException failed) { broken = true; }
    }

    synchronized void closeQuietly() {
      try { out.flush(); out.close(); } catch (IOException ignored) { }
      broken = true;
    }
  }
}
