// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

public enum ForwardingMode {
  NONE, MODERN, LEGACY, BUNGEEGUARD;

  public static ForwardingMode parse(String value) {
    return switch (value.toLowerCase()) {
      case "none" -> NONE;
      case "modern" -> MODERN;
      // Accepted here, they failed only at start, as an UnsupportedOperationException stack trace.
      case "legacy", "bungeeguard" -> throw new IllegalArgumentException("forwarding.mode must be none or modern: Conduit does not implement " + value + " forwarding");
      default -> throw new IllegalArgumentException("forwarding.mode must be none or modern");
    };
  }
}
