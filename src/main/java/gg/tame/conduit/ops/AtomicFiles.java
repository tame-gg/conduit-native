// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.ops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replaces a file with new contents, so that a reader sees either the old file or the new one.
 *
 * <p>The contents go to a sibling temporary file, are forced to disk, and are then moved over the
 * original. Without the force, a power loss after the rename could leave the new name pointing at
 * an empty file, which for a ban list reads as nobody being banned.
 */
public final class AtomicFiles {
  private AtomicFiles() {}

  public static void write(Path file, String contents) throws IOException {
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) channel.write(bytes);
      channel.force(true);
    }
    try {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
