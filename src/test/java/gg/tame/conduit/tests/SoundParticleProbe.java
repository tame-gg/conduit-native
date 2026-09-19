// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.particle.ParticleRegistries;
import gg.tame.conduit.protocol.sound.SoundCodec;
import gg.tame.conduit.protocol.sound.SoundRegistries;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A scripted client that joins a real server through a real Conduit and reports
 * every sound and particle it is sent, by name.
 *
 * <p>The sound and particle tables are checked by unit tests against the registry
 * dumps they were generated from, which is a check that they are self-consistent.
 * It is not a check that a real server's sound arrives as that sound: the tables
 * and the tests were built from the same belief about what a 1.13 id means, and
 * an earlier sound table passed its tests while 448 of its 662 ids were wrong.
 * This probe closes that loop from the outside. The backend is told, on its own
 * console, to play a named sound or particle; the probe reports the name it
 * decodes from the packet that arrives; the harness compares the two.
 *
 * <p>Every line carries the <b>raw registry id off the wire</b> as well as the
 * name. The name alone proves nothing: the probe would decode it with the same
 * table Conduit encoded it with, so a table that is wrong in both directions
 * round-trips and looks right. That is not hypothetical -- this probe passed
 * against the broken sound table. The harness therefore compares the raw id
 * against the id in Mojang's own registry report for the receiving version,
 * which is an authority no Conduit table took part in.
 *
 * <p>It prints {@code READY} once it is in PLAY and can be driven, then a line per
 * sound and particle, then {@code RESULT=ok} with everything it saw.
 *
 * <pre>
 *   java -cp out gg.tame.conduit.tests.SoundParticleProbe 127.0.0.1 25561 393 Prober13 25000
 * </pre>
 */
public final class SoundParticleProbe {
  private static final int MAX_FRAME = 8 * 1024 * 1024;

  private SoundParticleProbe() {}

  public static void main(String[] arguments) throws Exception {
    String host = arguments.length > 0 ? arguments[0] : "127.0.0.1";
    int port = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 25561;
    int protocol = arguments.length > 2 ? Integer.parseInt(arguments[2]) : 393;
    String name = arguments.length > 3 ? arguments[3] : "Prober";
    long listenMillis = arguments.length > 4 ? Long.parseLong(arguments[4]) : 25_000L;

    ProtocolDefinition version = ProtocolDefinition.forVersion(protocol);
    Set<String> sounds = new LinkedHashSet<>();
    Set<String> particles = new LinkedHashSet<>();

    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 10_000);
      socket.setSoTimeout(30_000);
      var in = socket.getInputStream();
      var out = socket.getOutputStream();

      MinecraftFrames.write(out, handshake(protocol, host, port));
      MinecraftFrames.write(out, loginStart(protocol, name));
      if (!awaitLoginSuccess(in)) { System.out.println("RESULT=login-failed"); System.exit(2); }
      System.out.println("login success");

      if (version.hasConfiguration()) {
        // Acknowledge the login, then ride out the configuration phase.
        MinecraftFrames.write(out, PlayPackets.loginAcknowledged(version));
        if (!awaitConfigurationFinish(version, in, out)) {
          System.out.println("RESULT=no-configuration");
          System.exit(3);
        }
      }
      if (!awaitPlayLogin(version, in, out)) { System.out.println("RESULT=no-play-login"); System.exit(4); }
      System.out.println("reached PLAY");

      send(version, out, PacketKind.PLAY_CLIENT_INFORMATION, clientSettings(protocol));
      System.out.println("READY");
      System.out.flush();

      // From here the harness drives the backend's console. Everything that
      // arrives is decoded and named; nothing is sent but keepalive answers.
      socket.setSoTimeout(2_000);
      long deadline = System.nanoTime() + listenMillis * 1_000_000L;
      while (System.nanoTime() < deadline) {
        byte[] packet;
        try {
          packet = MinecraftFrames.read(in, MAX_FRAME);
        } catch (java.net.SocketTimeoutException quiet) {
          continue;
        } catch (Exception ended) {
          break;
        }
        int id = PlayPackets.packetId(packet);
        if (version.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
            PacketKind.PLAY_KEEP_ALIVE)) {
          MinecraftFrames.write(out, PlayPackets.withId(version.id(ConnectionState.PLAY,
              PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE),
              PlayPackets.body(packet)));
          continue;
        }
        if (version.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
            PacketKind.PLAY_SOUND_EFFECT)) {
          SoundCodec.Sound sound = SoundCodec.read(protocol, PlayPackets.body(packet));
          String heard = sound == null ? "UNDECODABLE" : sound.name();
          sounds.add(heard);
          System.out.println("SOUND id=" + rawSoundId(protocol, PlayPackets.body(packet))
              + " name=" + heard + (sound != null && sound.inline() ? " (inline)" : ""));
          continue;
        }
        if (version.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
                PacketKind.PLAY_NAMED_SOUND_EFFECT)
            && version.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
                PacketKind.PLAY_NAMED_SOUND_EFFECT)) {
          SoundCodec.Sound sound = SoundCodec.readNamed(PlayPackets.body(packet));
          String heard = sound == null ? "UNDECODABLE" : sound.name();
          sounds.add(heard);
          System.out.println("SOUND id=none name=" + heard + " (named)");
          continue;
        }
        if (version.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
            PacketKind.PLAY_WORLD_PARTICLES)) {
          int rawId = rawParticleId(protocol, PlayPackets.body(packet));
          String seen = particleName(protocol, PlayPackets.body(packet));
          particles.add(seen);
          System.out.println("PARTICLE id=" + rawId + " name=" + seen);
          continue;
        }
      }
    }

    System.out.println("SOUNDS=" + String.join(",", sounds));
    System.out.println("PARTICLES=" + String.join(",", particles));
    System.out.println("RESULT=ok");
  }

  /** The particle's name, as Conduit's own table reads it. Reported, never trusted. */
  private static String particleName(int protocol, byte[] body) {
    int id = rawParticleId(protocol, body);
    if (id < 0) return "UNDECODABLE";
    return ParticleRegistries.name(protocol, id).orElse("UNKNOWN(" + id + ")");
  }

  /** The particle id exactly as it arrived, before any table is consulted. */
  private static int rawParticleId(int protocol, byte[] body) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      return protocol >= 765 ? MinecraftInput.varInt(input) : input.readInt();
    } catch (Exception malformed) {
      return -1;
    }
  }

  /**
   * The sound id exactly as it arrived, before any table is consulted.
   *
   * <p>1.20.4 writes the registry index one higher so that zero can mean an
   * inline definition; the registry index is returned here, so it can be compared
   * against a registry report directly. An inline sound has no index and reports
   * {@code none}.
   */
  private static String rawSoundId(int protocol, byte[] body) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int id = MinecraftInput.varInt(input);
      if (protocol < 765) return String.valueOf(id);
      return id == 0 ? "none" : String.valueOf(id - 1);
    } catch (Exception malformed) {
      return "unreadable";
    }
  }

  private static boolean awaitLoginSuccess(java.io.InputStream in) throws Exception {
    for (int attempt = 0; attempt < 32; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (id == 2) return true;
      if (id == 0) {
        System.out.println("login disconnect: " + readString(packet));
        return false;
      }
    }
    return false;
  }

  private static boolean awaitConfigurationFinish(ProtocolDefinition version,
      java.io.InputStream in, java.io.OutputStream out) throws Exception {
    for (int attempt = 0; attempt < 512; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (version.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.CONFIGURATION_KEEP_ALIVE)) {
        MinecraftFrames.write(out, packet);
        continue;
      }
      if (version.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.CONFIGURATION_FINISH)) {
        MinecraftFrames.write(out, PlayPackets.withId(version.id(ConnectionState.CONFIGURATION,
            PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH), new byte[0]));
        return true;
      }
      if (version.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.CONFIGURATION_DISCONNECT)) {
        System.out.println("configuration disconnect: " + readString(packet));
        return false;
      }
    }
    return false;
  }

  private static boolean awaitPlayLogin(ProtocolDefinition version, java.io.InputStream in,
      java.io.OutputStream out) throws Exception {
    for (int attempt = 0; attempt < 4096; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (version.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.PLAY_KEEP_ALIVE)) {
        MinecraftFrames.write(out, PlayPackets.withId(version.id(ConnectionState.PLAY,
            PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE),
            PlayPackets.body(packet)));
        continue;
      }
      if (version.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.PLAY_LOGIN)) {
        return true;
      }
    }
    return false;
  }

  private static void send(ProtocolDefinition version, java.io.OutputStream out, PacketKind kind,
      byte[] body) throws Exception {
    MinecraftFrames.write(out, PlayPackets.withId(
        version.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, kind), body));
  }

  private static byte[] clientSettings(int protocol) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.string(out, "en_us");
    out.writeByte(10);                       // view distance
    MinecraftOutput.varInt(out, 0);          // chat mode: enabled
    out.writeBoolean(true);                  // chat colours
    out.writeByte(0x7F);                     // every skin part
    MinecraftOutput.varInt(out, 1);          // main hand: right
    if (protocol >= 765) {
      out.writeBoolean(false);               // text filtering
      out.writeBoolean(true);                // allowed in server listing
    }
    return buffer.toByteArray();
  }

  private static String readString(byte[] packet) {
    try (DataInputStream input = new DataInputStream(
        new ByteArrayInputStream(PlayPackets.body(packet)))) {
      return MinecraftInput.string(input, 32767);
    } catch (Exception unreadable) {
      return "<unreadable>";
    }
  }

  private static byte[] handshake(int protocol, String host, int port) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, protocol);
    MinecraftOutput.string(out, host);
    out.writeShort(port);
    MinecraftOutput.varInt(out, 2);
    return buffer.toByteArray();
  }

  private static byte[] loginStart(int protocol, String name) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.string(out, name);
    if (protocol >= 765) {
      UUID uuid = UUID.nameUUIDFromBytes(
          ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.writeLong(uuid.getMostSignificantBits());
      out.writeLong(uuid.getLeastSignificantBits());
    }
    return buffer.toByteArray();
  }
}
