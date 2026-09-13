package gg.tame.conduit.config;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

public record ConduitConfiguration(InetSocketAddress listener, int maxFrameBytes,
                                  ForwardingMode forwardingMode, Optional<Path> forwardingSecretFile,
                                  List<BackendServer> backends, List<String> initialBackends,
                                  List<String> fallbackBackends) {
  public ConduitConfiguration {
    if (listener.getPort() < 1 || listener.getPort() > 65535) throw new IllegalArgumentException("listener.port must be 1..65535");
    if (maxFrameBytes < 1 || maxFrameBytes > 8 * 1024 * 1024) throw new IllegalArgumentException("listener.max-frame-bytes must be 1..8388608");
    if (forwardingMode == ForwardingMode.MODERN && forwardingSecretFile.isEmpty()) throw new IllegalArgumentException("forwarding.secret-file is required for modern forwarding");
    if (forwardingMode != ForwardingMode.MODERN && forwardingSecretFile.isPresent()) throw new IllegalArgumentException("forwarding.secret-file is only valid for modern forwarding");
    backends = List.copyOf(backends);
    initialBackends = List.copyOf(initialBackends);
    fallbackBackends = List.copyOf(fallbackBackends);
    if (backends.isEmpty()) throw new IllegalArgumentException("at least one [servers.<name>] backend is required");
    if (initialBackends.isEmpty()) throw new IllegalArgumentException("routing.initial must name at least one backend");
    for (String name : initialBackends) requireBackend(backends, name);
    for (String name : fallbackBackends) requireBackend(backends, name);
  }
  private static void requireBackend(List<BackendServer> backends, String name) {
    if (backends.stream().noneMatch(backend -> backend.name().equals(name))) throw new IllegalArgumentException("routing references unknown backend: " + name);
  }
}
