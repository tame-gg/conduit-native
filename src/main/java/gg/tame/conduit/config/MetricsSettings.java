// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where Conduit serves its metrics in Prometheus' text format, if anywhere. Off unless an address is
 * set; the endpoint has no authentication, so it belongs on loopback or a private network.
 * {@code queryPort} is the UDP port of the GameSpy 4 query ({@code [query]}), present only when it is
 * enabled; 0 there means the listener's own port.
 */
public record MetricsSettings(Optional<InetSocketAddress> prometheusAddress, Optional<java.net.URI> alertWebhook, OptionalInt queryPort) {
  public MetricsSettings {
    prometheusAddress = prometheusAddress == null ? Optional.empty() : prometheusAddress;
    alertWebhook = alertWebhook == null ? Optional.empty() : alertWebhook;
    queryPort = queryPort == null ? OptionalInt.empty() : queryPort;
  }
  /** Metrics without the query. */
  public MetricsSettings(Optional<InetSocketAddress> prometheusAddress, Optional<java.net.URI> alertWebhook) {
    this(prometheusAddress, alertWebhook, OptionalInt.empty());
  }
  /** Metrics with no alert webhook. */
  public MetricsSettings(Optional<InetSocketAddress> prometheusAddress) { this(prometheusAddress, Optional.empty()); }
  public static MetricsSettings defaults() { return new MetricsSettings(Optional.empty(), Optional.empty()); }
}
