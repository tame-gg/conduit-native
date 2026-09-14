package gg.tame.conduit.ops;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.health.BackendHealth;
import gg.tame.conduit.health.BackendHealthSnapshot;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.util.ArrayList;
import java.util.List;

/** Non-destructive operator diagnostics. Never includes secrets. */
public final class OpsDoctor {
  public enum Severity { OK, WARNING, ERROR }

  public record Finding(Severity severity, String code, String message) {}

  private OpsDoctor() {}

  public static List<Finding> run(ConduitRuntime runtime) {
    List<Finding> findings = new ArrayList<>();
    ConduitConfiguration config = runtime.configuration();
    findings.add(new Finding(Severity.OK, "version", "Conduit " + Conduit.VERSION));
    if (config.listener().getPort() < 1 || config.listener().getPort() > 65535) {
      findings.add(new Finding(Severity.ERROR, "listener", "Listener port is invalid."));
    } else {
      findings.add(new Finding(Severity.OK, "listener", "Listener " + config.listener().getHostString() + ":" + config.listener().getPort()));
    }
    if (config.backends().isEmpty()) {
      findings.add(new Finding(Severity.ERROR, "backends", "No backends registered."));
    } else {
      findings.add(new Finding(Severity.OK, "backends", config.backends().size() + " backend(s) registered."));
    }
    if (config.initialBackends().isEmpty()) {
      findings.add(new Finding(Severity.ERROR, "routing", "routing.initial is empty."));
    } else {
      findings.add(new Finding(Severity.OK, "routing", "Initial: " + String.join(", ", config.initialBackends())));
    }
    if (config.fallbackBackends().isEmpty()) {
      findings.add(new Finding(Severity.WARNING, "fallback", "No fallback backends configured."));
    } else {
      findings.add(new Finding(Severity.OK, "fallback", "Fallback: " + String.join(", ", config.fallbackBackends())));
    }
    int unhealthy = 0;
    int unknown = 0;
    int draining = 0;
    for (BackendHealthSnapshot snap : runtime.health().all()) {
      if (snap.health() == BackendHealth.UNHEALTHY) unhealthy++;
      if (snap.health() == BackendHealth.UNKNOWN) unknown++;
      if (snap.draining()) draining++;
    }
    if (unhealthy > 0) findings.add(new Finding(Severity.WARNING, "health", unhealthy + " unhealthy backend(s)."));
    else findings.add(new Finding(Severity.OK, "health", "No unhealthy backends."));
    if (unknown > 0) findings.add(new Finding(Severity.WARNING, "health-unknown", unknown + " backend(s) not yet probed."));
    if (draining > 0) findings.add(new Finding(Severity.WARNING, "drain", draining + " draining backend(s)."));
    if (runtime.versionGate().isEnabled()) {
      findings.add(new Finding(Severity.OK, "versions", "Version gate enabled (" + runtime.versionGate().describeAllowed() + ")."));
    } else {
      findings.add(new Finding(Severity.OK, "versions", "Version gate disabled."));
    }
    if (runtime.maintenance().isActive()) {
      findings.add(new Finding(Severity.WARNING, "maintenance", "Maintenance mode is ACTIVE."));
    } else if (!runtime.maintenance().featureEnabled()) {
      findings.add(new Finding(Severity.OK, "maintenance", "Maintenance feature disabled in config."));
    } else {
      findings.add(new Finding(Severity.OK, "maintenance", "Maintenance mode off."));
    }
    findings.add(new Finding(Severity.OK, "plugins", runtime.pluginCatalog().size() + " proxy plugin(s)."));
    findings.add(new Finding(Severity.OK, "auth", "Authentication mode: " + config.authentication().mode().name().toLowerCase()));
    if (config.forwardingMode() == ForwardingMode.MODERN) {
      if (config.forwardingSecretFile().isEmpty()) {
        findings.add(new Finding(Severity.ERROR, "forwarding", "Modern forwarding selected but secret-file missing."));
      } else {
        findings.add(new Finding(Severity.OK, "forwarding", "Modern forwarding configured (secret not shown)."));
      }
    } else {
      findings.add(new Finding(Severity.OK, "forwarding", "Forwarding mode: " + config.forwardingMode().name().toLowerCase()));
    }
    int codecs = 0;
    for (var version : gg.tame.conduit.protocol.ProtocolVersion.CATALOG) {
      if (ProtocolDefinition.hasCodec(version.number())) codecs++;
    }
    findings.add(new Finding(Severity.OK, "protocol", codecs + " direct codec(s) available."));
    return List.copyOf(findings);
  }
}
