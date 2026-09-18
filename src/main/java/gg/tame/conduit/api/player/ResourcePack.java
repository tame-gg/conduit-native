// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import gg.tame.conduit.api.text.Text;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A resource pack for a client to download and use.
 *
 * <p>{@code hash} is the pack's SHA-1 as 40 hex digits, or empty, and then the client cannot check
 * what it downloaded and fetches it again every time. {@code required} and {@code prompt} reach
 * clients from 1.17: such a client shows the prompt on the offer screen, and disconnects itself when
 * the player declines a required pack. An older client gets neither, and the proxy does not enforce
 * {@code required} for it. {@code id} names the pack to a 1.20.3+ client, which holds several packs
 * at once and replaces one only when offered another with the same id; an older client holds one, and
 * each pack offered replaces the last.
 */
public record ResourcePack(UUID id, String url, String hash, boolean required, Text prompt) {
  /** The longest URL the protocol carries. */
  public static final int MAX_URL = 32767;

  public ResourcePack {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(url, "url");
    if (url.isBlank() || url.length() > MAX_URL) throw new IllegalArgumentException("url must be 1 to " + MAX_URL + " characters");
    hash = hash == null ? "" : hash.toLowerCase(Locale.ROOT);
    if (!hash.isEmpty() && !hash.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("hash must be a SHA-1 as 40 hex digits, or empty");
    prompt = prompt == null ? Text.empty() : prompt;
  }

  /** A pack named by its URL: offering the same URL again replaces it rather than adding a second. */
  public static ResourcePack of(String url, String hash) {
    return new ResourcePack(UUID.nameUUIDFromBytes(Objects.requireNonNull(url, "url").getBytes(StandardCharsets.UTF_8)),
        url, hash, false, Text.empty());
  }

  /**
   * What the client says of a pack. A client before 1.20.3 answers only {@link #ACCEPTED}, then
   * {@link #LOADED}, {@link #DECLINED} or {@link #FAILED_DOWNLOAD}; the others are 1.20.3's.
   */
  public enum Status {
    LOADED, DECLINED, FAILED_DOWNLOAD, ACCEPTED, DOWNLOADED, INVALID_URL, FAILED_RELOAD, DISCARDED;

    /** More answers about the same pack follow this one. */
    public boolean intermediate() { return this == ACCEPTED || this == DOWNLOADED; }
  }

  /**
   * A pack a client was offered through the proxy, by the proxy or by the server it was on, and has
   * neither declined nor dropped: {@code loaded} once the client said so, pending until then. A
   * loaded pack the proxy offers again unchanged stays loaded while the client answers once more.
   */
  public record Offered(ResourcePack pack, boolean fromServer, boolean loaded) {
    public Offered { Objects.requireNonNull(pack, "pack"); }
  }
}
