// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Where Conduit serves its metrics in Prometheus' text format, if anywhere. Off unless an address is
 * set; the endpoint has no authentication, so it belongs on loopback or a private network.
 */
public record MetricsSettings(Optional<InetSocketAddress> prometheusAddress, Optional<java.net.URI> alertWebhook) {
  public MetricsSettings {
    prometheusAddress = prometheusAddress == null ? Optional.empty() : prometheusAddress;
    alertWebhook = alertWebhook == null ? Optional.empty() : alertWebhook;
  }
  /** Metrics with no alert webhook. */
  public MetricsSettings(Optional<InetSocketAddress> prometheusAddress) { this(prometheusAddress, Optional.empty()); }
  public static MetricsSettings defaults() { return new MetricsSettings(Optional.empty(), Optional.empty()); }
}
