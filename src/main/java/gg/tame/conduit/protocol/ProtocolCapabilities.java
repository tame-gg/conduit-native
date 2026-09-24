// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.util.EnumSet;
import java.util.Set;

/**
 * Version-scoped capabilities. Prefer these over raw version comparisons.
 * Definitions declare capabilities — do not infer exclusively from version numbers.
 */
public record ProtocolCapabilities(
    boolean configurationPhase,
    boolean loginShouldAuthenticate,
    boolean knownPacks,
    boolean joinGameOnlineMode,
    boolean cookiePackets,
    boolean transferPackets,
    boolean chatSigning,
    boolean secureChat,
    boolean loginStartUuid,
    boolean loginSuccessProperties,
    boolean loginSuccessBinaryUuid,
    boolean loginPluginMessage,
    boolean compression,
    boolean encryption,
    boolean modernForwarding,
    boolean registrySync,
    boolean tags,
    boolean commandTree,
    boolean playerInfoUpdate,
    boolean bundlePackets,
    boolean resourcePacks,
    boolean pluginMessages,
    boolean legacyPlayChat
) {
  /** Pre-1.20.2 play-era defaults (no configuration). */
  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate) {
    this(configurationPhase, loginShouldAuthenticate, false, false, false, false,
        configurationPhase, configurationPhase,
        true, true, true, true, true, true, true,
        configurationPhase, configurationPhase, true, configurationPhase, false, true, true,
        !configurationPhase);
  }

  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate, boolean knownPacks) {
    this(configurationPhase, loginShouldAuthenticate, knownPacks, false, false, false, true, true,
        true, true, true, true, true, true, true, true, true, true, true, false, true, true, false);
  }

  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate, boolean knownPacks,
                              boolean joinGameOnlineMode) {
    this(configurationPhase, loginShouldAuthenticate, knownPacks, joinGameOnlineMode, false, false, true, true,
        true, true, true, true, true, true, true, true, true, true, true, false, true, true, false);
  }

  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate, boolean knownPacks,
                              boolean joinGameOnlineMode, boolean cookiePackets, boolean transferPackets,
                              boolean chatSigning, boolean secureChat) {
    this(configurationPhase, loginShouldAuthenticate, knownPacks, joinGameOnlineMode, cookiePackets, transferPackets,
        chatSigning, secureChat,
        true, true, true, true, true, true, true, true, true, true, true,
        cookiePackets, true, true, false);
  }

  public static ProtocolCapabilities modernConfig(boolean knownPacks, boolean joinGameOnlineMode,
                                                  boolean cookies, boolean transfer) {
    return new ProtocolCapabilities(true, joinGameOnlineMode, knownPacks, joinGameOnlineMode, cookies, transfer, true, true);
  }

  /**
   * 1.20.1-style: play after login, binary login success with properties, no configuration. Its
   * Player Info ids are in the table for tab-list entries, but with no Configuration phase the
   * client never loses its own entry, so none is synthesised for it (playerInfoUpdate false), as
   * for 1.19.x.
   */
  public static ProtocolCapabilities legacyPlay() {
    return new ProtocolCapabilities(
        false, false, false, false, false, false, false, false,
        true, true, true, true, true, true, true,
        false, false, true, false, false, true, true, false);
  }

  /**
   * Minecraft 1.13 (393): no configuration, Login Start is username-only,
   * Login Success is string UUID + username (no properties), chat uses legacy chat packet.
   */
  public static ProtocolCapabilities flattening113() {
    return new ProtocolCapabilities(
        false, false, false, false, false, false, false, false,
        false, false, false, true, true, true, true,
        false, true, true, false, false, true, true, true);
  }

  /**
   * Pre-flattening releases: 1.7.6&ndash;1.7.10 (5), 1.8.x (47) and 1.12.2 (340).
   *
   * <p>Conduit does not translate these itself &mdash; ViaRewind and ViaVersion do. What Conduit
   * needs from the table is the handful of client packets it reasons about on its own behalf: the
   * chat line a command arrives on, the tab-complete request, keep-alive, and the login exchange.
   * The capabilities that differ from {@link #flattening113()} are the ones that decide whether
   * Conduit tries something the release has no packet for at all: there is no login plugin message
   * before 1.13, so no modern forwarding either; no command tree before 1.13, so no merged proxy
   * command list; and no tag packet to withhold.
   *
   * @param compression   1.8 introduced Set Compression; 1.7 has no such packet
   * @param resourcePacks 1.8 introduced the resource-pack exchange
   */
  public static ProtocolCapabilities preFlattening(boolean compression, boolean resourcePacks) {
    return new ProtocolCapabilities(
        false, false, false, false, false, false, false, false,
        false, false, false, false, compression, true, false,
        false, false, false, false, false, resourcePacks, true, true);
  }

  /**
   * 1.19 to 1.19.4: the 1.13 capabilities with 1.19's chat, where a command arrives in its own Chat
   * Command packet with no leading slash and the server's own messages in System Chat. Inheriting
   * 1.13's legacy chat left Conduit waiting for a slash that never comes: a real 1.19 client's
   * /server and /conduit went to the backend, which answered "Unknown or incomplete command".
   */
  public static ProtocolCapabilities chatCommands119() {
    ProtocolCapabilities base = flattening113();
    return new ProtocolCapabilities(
        base.configurationPhase(), base.loginShouldAuthenticate(), base.knownPacks(), base.joinGameOnlineMode(),
        base.cookiePackets(), base.transferPackets(), base.chatSigning(), base.secureChat(),
        base.loginStartUuid(), base.loginSuccessProperties(), base.loginSuccessBinaryUuid(),
        base.loginPluginMessage(), base.compression(), base.encryption(), base.modernForwarding(),
        base.registrySync(), base.tags(), base.commandTree(), base.playerInfoUpdate(), base.bundlePackets(),
        base.resourcePacks(), base.pluginMessages(), false);
  }

  public Set<String> named() {
    EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
    if (configurationPhase) flags.add(Flag.CONFIGURATION);
    if (loginShouldAuthenticate) flags.add(Flag.LOGIN_SHOULD_AUTHENTICATE);
    if (knownPacks) flags.add(Flag.KNOWN_PACKS);
    if (cookiePackets) flags.add(Flag.COOKIES);
    if (transferPackets) flags.add(Flag.TRANSFER);
    if (chatSigning) flags.add(Flag.CHAT_SIGNING);
    if (secureChat) flags.add(Flag.SECURE_CHAT);
    if (loginStartUuid) flags.add(Flag.LOGIN_START_UUID);
    if (loginSuccessProperties) flags.add(Flag.LOGIN_SUCCESS_PROPERTIES);
    if (loginPluginMessage) flags.add(Flag.LOGIN_PLUGIN);
    if (compression) flags.add(Flag.COMPRESSION);
    if (encryption) flags.add(Flag.ENCRYPTION);
    if (modernForwarding) flags.add(Flag.MODERN_FORWARDING);
    if (registrySync) flags.add(Flag.REGISTRY_SYNC);
    if (tags) flags.add(Flag.TAGS);
    if (commandTree) flags.add(Flag.COMMAND_TREE);
    if (playerInfoUpdate) flags.add(Flag.PLAYER_INFO_UPDATE);
    if (bundlePackets) flags.add(Flag.BUNDLES);
    if (resourcePacks) flags.add(Flag.RESOURCE_PACKS);
    if (pluginMessages) flags.add(Flag.PLUGIN_MESSAGES);
    if (legacyPlayChat) flags.add(Flag.LEGACY_PLAY_CHAT);
    Set<String> names = new java.util.LinkedHashSet<>();
    for (Flag flag : flags) names.add(flag.name());
    return Set.copyOf(names);
  }

  private enum Flag {
    CONFIGURATION, LOGIN_SHOULD_AUTHENTICATE, KNOWN_PACKS, COOKIES, TRANSFER,
    CHAT_SIGNING, SECURE_CHAT, LOGIN_START_UUID, LOGIN_SUCCESS_PROPERTIES,
    LOGIN_PLUGIN, COMPRESSION, ENCRYPTION, MODERN_FORWARDING, REGISTRY_SYNC,
    TAGS, COMMAND_TREE, PLAYER_INFO_UPDATE, BUNDLES, RESOURCE_PACKS, PLUGIN_MESSAGES,
    LEGACY_PLAY_CHAT
  }
}
