// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

public enum ForwardingMode {
  NONE, MODERN, LEGACY, BUNGEEGUARD;

  public static ForwardingMode parse(String value) {
    return switch (value.toLowerCase()) {
      case "none" -> NONE;
      case "modern" -> MODERN;
      case "legacy" -> LEGACY;
      case "bungeeguard" -> BUNGEEGUARD;
      default -> throw new IllegalArgumentException("forwarding.mode must be none, modern, legacy, or bungeeguard");
    };
  }
}
