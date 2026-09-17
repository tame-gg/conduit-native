package gg.tame.conduit.tests;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import gg.tame.conduit.viaversion.ConduitViaTranslator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Locks the order of packets Via sends while it is handling another one.
 *
 * <p>A real 1.21.8 client joined a 1.20.4 backend and disconnected with "Registry must be non-empty"
 * for six registries the backend has never heard of. ViaBackwards does supply them, while handling
 * the backend's Finish Configuration, and in its own pipeline they reach the wire first. Conduit
 * wrote the translated Finish Configuration and then the registries, so the client froze its
 * registries before any of them arrived.
 */
public final class ViaOrderingTests {
  private static final int CLIENT = 772;
  private static final int BACKEND = 765;

  private ViaOrderingTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    if (!ConduitViaBootstrap.available()) {
      ConduitViaBootstrap.start(Files.createTempDirectory("conduit-via-ordering"), "test",
          new TranslationSettings(true, TranslationSettings.TranslationEngine.VIA_PREFERRED, true, true, false, "via"));
    }
    try (ConduitViaTranslator via = ConduitViaTranslator.create(CLIENT, BACKEND, "127.0.0.1", 25565)) {
      via.backendToClient(ConnectionState.LOGIN, loginSuccess());
      via.drainToClient();
      via.drainToBackend();
      via.clientToBackend(ConnectionState.LOGIN, PlayPackets.loginAcknowledged(ProtocolDefinition.forVersion(CLIENT)));
      via.drainToClient();
      via.drainToBackend();
      via.backendEntered(ConnectionState.CONFIGURATION);

      byte[] finish = via.backendToClient(ConnectionState.CONFIGURATION, PlayPackets.withId(0x02, new byte[0]));
      require(finish != null && PlayPackets.packetId(finish) == 0x03, "Finish Configuration comes back as 1.21.8's 0x03");

      Set<String> ahead = registries(via.drainAheadOfResult());
      for (String needed : new String[] {"minecraft:chicken_variant", "minecraft:cow_variant", "minecraft:frog_variant",
          "minecraft:painting_variant", "minecraft:pig_variant", "minecraft:wolf_sound_variant"}) {
        require(ahead.contains(needed), needed + " is sent ahead of Finish Configuration");
      }
      require(registries(via.drainToClient()).isEmpty(), "no registry is left to follow Finish Configuration");
    }
    System.out.println("ViaOrderingTests passed.");
  }

  /** Names of the Registry Data packets (1.21.8 configuration id 0x07) in {@code packets}. */
  private static Set<String> registries(java.util.List<byte[]> packets) throws Exception {
    Set<String> names = new HashSet<>();
    for (byte[] packet : packets) {
      if (PlayPackets.packetId(packet) != 0x07) continue;
      names.add(MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet))), 32767));
    }
    return names;
  }

  /** 1.20.4 Login Success: UUID, name, no properties. */
  private static byte[] loginSuccess() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeLong(0x069a79f444e94726L);
    out.writeLong(0xa5befca90e38aaf5L);
    MinecraftOutput.string(out, "Notch");
    MinecraftOutput.varInt(out, 0);
    return PlayPackets.withId(0x02, bytes.toByteArray());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
