// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.version;

import gg.tame.conduit.config.VersionGateSettings;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Client version gate — independent of translation support. */
public final class VersionGate {
  private volatile VersionGateSettings settings;

  public VersionGate(VersionGateSettings settings) {
    this.settings = settings;
  }

  public synchronized void applySettings(VersionGateSettings replacement) {
    this.settings = replacement;
  }

  public VersionGateSettings settings() { return settings; }

  public boolean isEnabled() {
    return settings.enabled() && settings.hasConstraints();
  }

  public boolean allows(int protocol) {
    if (!isEnabled()) return true;
    if (!settings.allowProtocols().isEmpty() && !settings.allowProtocols().contains(protocol)) return false;
    if (settings.minimumProtocol().isPresent() && protocol < settings.minimumProtocol().getAsInt()) return false;
    if (settings.maximumProtocol().isPresent() && protocol > settings.maximumProtocol().getAsInt()) return false;
    if (settings.allowProtocols().isEmpty()
        && settings.minimumProtocol().isEmpty()
        && settings.maximumProtocol().isEmpty()) {
      return true;
    }
    if (!settings.allowProtocols().isEmpty()) return settings.allowProtocols().contains(protocol);
    return true;
  }

  /** Versions the gate considers joinable when enabled. */
  public Set<Integer> allowedProtocols() {
    if (!isEnabled()) return Set.of();
    if (!settings.allowProtocols().isEmpty()) return settings.allowProtocols();
    LinkedHashSet<Integer> range = new LinkedHashSet<>();
    int min = settings.minimumProtocol().orElse(Integer.MIN_VALUE);
    int max = settings.maximumProtocol().orElse(Integer.MAX_VALUE);
    for (ProtocolVersion version : ProtocolVersion.CATALOG) {
      if (version.number() >= min && version.number() <= max) range.add(version.number());
    }
    return Set.copyOf(range);
  }

  public String describeAllowed() {
    Set<Integer> allowed = allowedProtocols();
    if (allowed.isEmpty() && settings.minimumProtocol().isPresent() && settings.maximumProtocol().isPresent()) {
      return ProtocolVersion.display(settings.minimumProtocol().getAsInt())
          + "–" + ProtocolVersion.display(settings.maximumProtocol().getAsInt());
    }
    List<Integer> sorted = new ArrayList<>(allowed);
    sorted.sort(Comparator.naturalOrder());
    List<String> names = new ArrayList<>();
    for (int protocol : sorted) names.add(ProtocolVersion.display(protocol));
    return String.join(", ", names);
  }

  public String kickMessage() {
    String versions = describeAllowed();
    boolean range = settings.minimumProtocol().isPresent() && settings.maximumProtocol().isPresent()
        && settings.allowProtocols().isEmpty();
    String template = range ? settings.kickMessageRange() : settings.kickMessage();
    return template.replace("{versions}", versions.isBlank() ? "a supported version" : versions);
  }

  /**
   * The allowed versions as a server list entry should carry them: a handful by name, and anything
   * longer as the span from the oldest to the newest. A gate that allows every version between
   * 1.20.4 and 1.21.4 listed five of them, which is a line no client shows in full.
   */
  public String describeAllowedBriefly() {
    Set<Integer> allowed = allowedProtocols();
    if (allowed.isEmpty()) return describeAllowed();
    List<Integer> sorted = new ArrayList<>(allowed);
    sorted.sort(Comparator.naturalOrder());
    if (sorted.size() <= 3) {
      List<String> names = new ArrayList<>();
      for (int protocol : sorted) names.add(ProtocolVersion.display(protocol));
      return String.join(", ", names);
    }
    return ProtocolVersion.display(sorted.getFirst()) + "-" + ProtocolVersion.display(sorted.getLast());
  }

  public String pingVersionName(int clientProtocol) {
    String versions = describeAllowedBriefly();
    return settings.pingVersionName().replace("{versions}", versions.isBlank() ? "unsupported" : versions);
  }

  public Optional<Integer> statusProtocolAdvertisement(int clientProtocol) {
    if (allows(clientProtocol)) return Optional.of(clientProtocol);
    Set<Integer> allowed = allowedProtocols();
    if (allowed.isEmpty()) return Optional.empty();
    // Prefer a codec-backed allowed protocol for status JSON.
    for (int protocol : allowed) {
      if (ProtocolDefinition.hasCodec(protocol)) return Optional.of(protocol);
    }
    return Optional.of(allowed.iterator().next());
  }
}
