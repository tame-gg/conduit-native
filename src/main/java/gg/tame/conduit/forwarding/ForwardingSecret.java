// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Secret material; its value is intentionally never exposed through toString. */
public final class ForwardingSecret {
  private final byte[] value;
  private ForwardingSecret(byte[] value) { this.value = value; }
  public static ForwardingSecret load(Path path) throws IOException {
    byte[] bytes = Files.readString(path, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0) throw new IllegalArgumentException("forwarding secret file is empty");
    return new ForwardingSecret(bytes);
  }
  /**
   * Creates the secret file if nothing is there yet, and says whether it did.
   *
   * <p>Modern forwarding cannot work without one, and there is nothing an
   * operator could usefully type here: it is a shared random value, and its only
   * requirement is that the backend has the same one. Leaving them to invent it
   * means the first start fails on a file they have to go and make, so it is
   * made for them, the way the proxy this replaces does it.
   *
   * <p>Created only when the proxy is actually starting. Validating a
   * configuration writes nothing.
   *
   * @return true when this call created the file, false when one was already there
   */
  public static boolean createIfAbsent(Path path) throws IOException {
    if (Files.exists(path)) return false;
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) Files.createDirectories(parent);
    byte[] material = new byte[32];
    new java.security.SecureRandom().nextBytes(material);
    String secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    try {
      // CREATE_NEW rather than a write after the check above: two proxies started
      // together would otherwise each write, and the one that wrote second would
      // leave the first running on a secret no longer in the file.
      Files.writeString(path, secret + System.lineSeparator(), StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    } catch (java.nio.file.FileAlreadyExistsException raced) {
      return false;
    }
    restrictToOwner(path);
    return true;
  }

  /**
   * Narrows the file to its owner where the filesystem can express that.
   *
   * <p>Best effort on purpose: a secret readable by other local users is worse
   * than one that is not, but a filesystem with no permissions to set is not a
   * reason to refuse to start.
   */
  private static void restrictToOwner(Path path) {
    try {
      java.nio.file.attribute.PosixFileAttributeView posix =
          Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class);
      if (posix != null) {
        posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      }
    } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
      // Windows and some network filesystems: nothing to narrow, and not fatal.
    }
  }

  public String fingerprint() {
    try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value), 0, 6); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }
  byte[] bytes() { return value.clone(); }
  @Override public String toString() { return "ForwardingSecret[redacted]"; }
}
