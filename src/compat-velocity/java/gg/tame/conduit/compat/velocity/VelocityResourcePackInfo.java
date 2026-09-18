// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import gg.tame.conduit.api.player.ResourcePack;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;

/**
 * A Velocity ResourcePackInfo over Conduit's {@link ResourcePack}. The prompt keeps what Conduit Text
 * carries. A pack a plugin builds without an id gets one from its URL, so offering the same URL again
 * replaces the pack rather than adding a second.
 */
final class VelocityResourcePackInfo implements ResourcePackInfo {
  private final ResourcePack pack;
  private final Component prompt;
  private final Origin origin;

  private VelocityResourcePackInfo(ResourcePack pack, Component prompt, Origin origin) {
    this.pack = pack;
    this.prompt = prompt;
    this.origin = origin;
  }

  static VelocityResourcePackInfo of(ResourcePack pack, boolean fromServer) {
    return new VelocityResourcePackInfo(pack, Texts.toAdventure(pack.prompt()), fromServer ? Origin.DOWNSTREAM_SERVER : Origin.PLUGIN_ON_PROXY);
  }

  /** Any plugin's ResourcePackInfo, as the pack Conduit sends. */
  static ResourcePack toConduit(ResourcePackInfo info) {
    if (info instanceof VelocityResourcePackInfo ours) return ours.pack;
    byte[] hash = info.getHash();
    return new ResourcePack(info.getId(), info.getUrl(), hash == null ? "" : HexFormat.of().formatHex(hash),
        info.getShouldForce(), Texts.toConduit(info.getPrompt()));
  }

  @Override public UUID getId() { return pack.id(); }
  @Override public String getUrl() { return pack.url(); }
  @Override public Component getPrompt() { return prompt; }
  @Override public boolean getShouldForce() { return pack.required(); }
  @Override public byte[] getHash() { return pack.hash().isEmpty() ? null : HexFormat.of().parseHex(pack.hash()); }
  @Override public Origin getOrigin() { return origin; }
  /** The origin is where a pack's answers go, which a plugin putting another pack in place of a server's does not change. */
  @Override public Origin getOriginalOrigin() { return origin; }
  @Override public ResourcePackInfo.Builder asBuilder() { return new Builder(pack.url()).from(this); }
  @Override public ResourcePackInfo.Builder asBuilder(String url) { return new Builder(url).from(this); }
  @Override public ResourcePackRequest asResourcePackRequest() {
    return ResourcePackRequest.resourcePackRequest()
        .packs(net.kyori.adventure.resource.ResourcePackInfo.resourcePackInfo(pack.id(), URI.create(pack.url()), pack.hash()))
        .required(pack.required())
        .prompt(prompt)
        .build();
  }
  @Override public boolean equals(Object other) {
    return other instanceof VelocityResourcePackInfo that && that.pack.equals(pack) && that.origin == origin;
  }
  @Override public int hashCode() { return Objects.hash(pack, origin); }
  @Override public String toString() { return "ResourcePackInfo[" + pack.id() + " " + pack.url() + "]"; }

  /** ProxyServer.createResourcePackBuilder's builder: a pack the proxy itself offers. */
  static final class Builder implements ResourcePackInfo.Builder {
    private final String url;
    private UUID id;
    private boolean force;
    private byte[] hash;
    private Component prompt = Component.empty();

    Builder(String url) { this.url = Objects.requireNonNull(url, "url"); }

    private Builder from(VelocityResourcePackInfo info) {
      id = info.getId();
      force = info.getShouldForce();
      hash = info.getHash();
      prompt = info.getPrompt();
      return this;
    }

    @Override public Builder setId(UUID id) { this.id = id; return this; }
    @Override public Builder setShouldForce(boolean force) { this.force = force; return this; }
    /** A SHA-1: 20 bytes, or null for none. */
    @Override public Builder setHash(byte[] hash) {
      if (hash != null && hash.length != 20) throw new IllegalArgumentException("a resource pack hash is a SHA-1 of 20 bytes, got " + hash.length);
      this.hash = hash == null ? null : hash.clone();
      return this;
    }
    @Override public Builder setPrompt(Component prompt) { this.prompt = prompt == null ? Component.empty() : prompt; return this; }
    @Override public ResourcePackInfo build() {
      UUID named = id != null ? id : UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
      ResourcePack pack = new ResourcePack(named, url, hash == null ? "" : HexFormat.of().formatHex(hash), force, Texts.toConduit(prompt));
      return new VelocityResourcePackInfo(pack, prompt, Origin.PLUGIN_ON_PROXY);
    }
  }
}
