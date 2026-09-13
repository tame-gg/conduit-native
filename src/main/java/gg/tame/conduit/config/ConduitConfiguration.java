package gg.tame.conduit.config;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;

public record ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes,
                                  ForwardingMode forwardingMode, Optional<Path> forwardingSecretFile) {
  public ConduitConfiguration {
    if (listener.getPort() < 1 || listener.getPort() > 65535) throw new IllegalArgumentException("listener.port must be 1..65535");
    if (maxFrameBytes < 1 || maxFrameBytes > 8 * 1024 * 1024) throw new IllegalArgumentException("listener.max-frame-bytes must be 1..8388608");
    if (forwardingMode == ForwardingMode.MODERN && forwardingSecretFile.isEmpty()) throw new IllegalArgumentException("forwarding.secret-file is required for modern forwarding");
    if (forwardingMode != ForwardingMode.MODERN && forwardingSecretFile.isPresent()) throw new IllegalArgumentException("forwarding.secret-file is only valid for modern forwarding");
  }
}
