package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.platform.ViaInjector;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.gson.JsonObject;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Tells Via where Conduit's per-session channel ends are, and what the platform can speak.
 *
 * <p>There is nothing to inject. Conduit does not run a Netty pipeline for proxy I/O — it reads and
 * writes blocking sockets on virtual threads — so Via is never spliced into anything. What Via
 * still needs from an injector is the pair of handler names it addresses a connection by when a
 * translation emits an extra packet of its own, and a view of which protocol versions the platform
 * is willing to be. Conduit answers with the names {@link ConduitViaSession} puts on its channel,
 * and with every version Via itself has registered: which of them a given connection actually uses
 * is decided per session from the client's handshake and the backend's advertisement, not here.
 */
public final class ConduitViaInjector implements ViaInjector {
  static final String DECODER_NAME = "via-decoder";
  static final String ENCODER_NAME = "via-encoder";

  @Override
  public void inject() {
  }

  @Override
  public void uninject() {
  }

  @Override
  public String getDecoderName() {
    return DECODER_NAME;
  }

  @Override
  public String getEncoderName() {
    return ENCODER_NAME;
  }

  @Override
  public ProtocolVersion getServerProtocolVersion() {
    return getServerProtocolVersions().first();
  }

  @Override
  public SortedSet<ProtocolVersion> getServerProtocolVersions() {
    // A proxy has no single server version. Conduit reports the whole registered range so Via does
    // not narrow the graph before the per-connection backend target has been chosen.
    SortedSet<ProtocolVersion> versions = new TreeSet<>(ProtocolVersion.getProtocols());
    if (versions.isEmpty()) {
      versions.add(ProtocolVersion.unknown);
    }
    return versions;
  }

  @Override
  public JsonObject getDump() {
    JsonObject dump = new JsonObject();
    dump.addProperty("platform", "Conduit");
    dump.addProperty("io", "blocking sockets on virtual threads");
    dump.addProperty("netty-pipeline-injection", false);
    dump.addProperty("decoder", DECODER_NAME);
    dump.addProperty("encoder", ENCODER_NAME);
    return dump;
  }
}
