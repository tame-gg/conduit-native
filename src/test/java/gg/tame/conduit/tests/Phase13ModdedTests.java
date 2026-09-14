package gg.tame.conduit.tests;

import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.modded.ChannelDetector;
import gg.tame.conduit.modded.CompatibilityDecision;
import gg.tame.conduit.modded.FmlAddressMarkers;
import gg.tame.conduit.modded.HandshakeClassifier;
import gg.tame.conduit.modded.KnownPacksValidator;
import gg.tame.conduit.modded.ModCompatibility;
import gg.tame.conduit.modded.ModLoaderFamily;
import gg.tame.conduit.modded.ModdedHandshakeCache;
import gg.tame.conduit.modded.PluginPayloadValidator;
import gg.tame.conduit.modded.SwitchPacketQueue;
import gg.tame.conduit.modded.UnknownModdedPolicy;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Phase 13 — Modded / Forge / NeoForge compatibility. */
public final class Phase13ModdedTests {
  private Phase13ModdedTests() {}

  public static void run() throws Exception {
    knownPacks();
    addressMarkers();
    detectionAndChannels();
    handshakeCache();
    payloadValidation();
    compatibilityRouting();
    packetQueue();
    doctorShowsModded();
    System.out.println("Phase13ModdedTests passed.");
  }

  private static void knownPacks() throws Exception {
    byte[] normal = packs(List.of(pack("minecraft", "core", "1.21")));
    require(KnownPacksValidator.validate(normal, 1024).count() == 1, "normal pack");
    List<KnownPacksValidator.KnownPack> max = new ArrayList<>();
    for (int i = 0; i < 8; i++) max.add(pack("ns", "id" + i, "1"));
    require(KnownPacksValidator.validate(packs(max), 8).count() == 8, "at limit");
    try {
      KnownPacksValidator.validate(packs(max), 7);
      throw new AssertionError("over limit");
    } catch (Exception expected) { require(expected.getMessage().contains("exceeds"), "over limit message"); }
    try {
      KnownPacksValidator.validate(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x0F}, 1024);
      throw new AssertionError("malformed varint count");
    } catch (Exception expected) { /* ok */ }
    try {
      ByteArrayOutputStream truncated = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(truncated)) {
        MinecraftOutput.varInt(out, 1);
        MinecraftOutput.string(out, "minecraft");
      }
      KnownPacksValidator.validate(truncated.toByteArray(), 1024);
      throw new AssertionError("truncated");
    } catch (Exception expected) { /* ok */ }
  }

  private static void addressMarkers() {
    require(FmlAddressMarkers.parse("play.example.com").marker() == FmlAddressMarkers.MarkerKind.NONE, "no marker");
    var fml1 = FmlAddressMarkers.parse("play.example.com" + FmlAddressMarkers.FML1);
    require(fml1.marker() == FmlAddressMarkers.MarkerKind.FML1 && fml1.cleanHost().equals("play.example.com"), "fml1");
    require(fml1.family() == ModLoaderFamily.FORGE, "fml1 family");
    var fml2 = FmlAddressMarkers.parse("host" + FmlAddressMarkers.FML2);
    require(fml2.marker() == FmlAddressMarkers.MarkerKind.FML2, "fml2");
    var fml3 = FmlAddressMarkers.parse("host" + FmlAddressMarkers.FML3);
    require(fml3.marker() == FmlAddressMarkers.MarkerKind.FML3 && fml3.family() == ModLoaderFamily.NEOFORGE, "fml3");
    try {
      FmlAddressMarkers.parse("bad\0DATA\0");
      throw new AssertionError("malformed");
    } catch (IllegalArgumentException expected) { /* ok */ }
    require(FmlAddressMarkers.append("host", FmlAddressMarkers.MarkerKind.FML2).endsWith(FmlAddressMarkers.FML2), "append");
  }

  private static void detectionAndChannels() {
    HandshakeClassifier vanilla = new HandshakeClassifier();
    vanilla.observeHandshakeHost("lobby.example");
    vanilla.observeChannel("minecraft:brand");
    vanilla.markLikelyVanilla();
    require(vanilla.family() == ModLoaderFamily.VANILLA, "vanilla");

    HandshakeClassifier fabric = new HandshakeClassifier();
    fabric.observeChannel("fabric:registry/sync");
    require(fabric.family() == ModLoaderFamily.FABRIC, "fabric");

    HandshakeClassifier forge = new HandshakeClassifier();
    forge.observeHandshakeHost("x" + FmlAddressMarkers.FML2);
    require(forge.family() == ModLoaderFamily.FORGE, "forge marker");
    forge.observeChannel("fml:handshake");
    require(forge.family() == ModLoaderFamily.FORGE, "forge channel");

    HandshakeClassifier neo = new HandshakeClassifier();
    neo.observeHandshakeHost("x" + FmlAddressMarkers.FML3);
    require(neo.family() == ModLoaderFamily.NEOFORGE, "neoforge");
    neo.observeChannel("neoforge:handshake");
    require(neo.family() == ModLoaderFamily.NEOFORGE, "neoforge channel");

    HandshakeClassifier unknown = new HandshakeClassifier();
    unknown.observeChannel("somemod:weird");
    require(unknown.family() == ModLoaderFamily.UNKNOWN, "unknown stays unknown");

    require(ChannelDetector.classify("fml:loginwrapper") == ChannelDetector.ChannelClass.FORGE, "fml loginwrapper");
    require(ChannelDetector.classify("neoforge:network") == ChannelDetector.ChannelClass.NEOFORGE, "neo channel");
    require(ChannelDetector.classify("") == ChannelDetector.ChannelClass.INVALID, "invalid channel");
  }

  private static void handshakeCache() throws Exception {
    ModdedHandshakeCache cache = new ModdedHandshakeCache(16, 60_000);
    InetAddress address = InetAddress.getByName("198.51.100.20");
    require(cache.get(address, 765).isEmpty(), "empty");
    cache.put(address, 765, ModLoaderFamily.FABRIC, FmlAddressMarkers.MarkerKind.NONE);
    require(cache.get(address, 765).orElseThrow().family() == ModLoaderFamily.FABRIC, "lookup");
    require(cache.invalidate(address), "invalidate");
    require(cache.get(address, 765).isEmpty(), "after invalidate");
    for (int i = 0; i < 40; i++) {
      cache.put(InetAddress.getByName("203.0.113." + (i % 250 + 1)), 776, ModLoaderFamily.FORGE, FmlAddressMarkers.MarkerKind.FML2);
    }
    require(cache.size() <= 16, "bounded");
  }

  private static void payloadValidation() throws Exception {
    PluginPayloadValidator.validateChannel("minecraft:brand");
    try {
      PluginPayloadValidator.validateChannel("bad\0channel");
      throw new AssertionError("nul channel");
    } catch (Exception expected) { /* ok */ }
    PluginPayloadValidator.validatePayload(new byte[8], 64);
    try {
      PluginPayloadValidator.validatePayload(new byte[128], 64);
      throw new AssertionError("oversized");
    } catch (Exception expected) { /* ok */ }
  }

  private static void compatibilityRouting() throws Exception {
    BackendServer any = new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 25566));
    BackendServer forgeOnly = new BackendServer("forge", new InetSocketAddress("127.0.0.1", 25567),
        EnumSet.of(ModLoaderFamily.FORGE, ModLoaderFamily.NEOFORGE));
    BackendServer fabricOnly = new BackendServer("fabric", new InetSocketAddress("127.0.0.1", 25568),
        EnumSet.of(ModLoaderFamily.FABRIC));
    require(ModCompatibility.decide(ModLoaderFamily.VANILLA, any.supportedModLoaders()) == CompatibilityDecision.COMPATIBLE, "unrestricted");
    require(ModCompatibility.decide(ModLoaderFamily.FORGE, forgeOnly.supportedModLoaders()) == CompatibilityDecision.COMPATIBLE, "forge ok");
    require(ModCompatibility.decide(ModLoaderFamily.FABRIC, forgeOnly.supportedModLoaders()) == CompatibilityDecision.INCOMPATIBLE, "fabric blocked");
    require(ModCompatibility.isEligible(ModLoaderFamily.UNKNOWN, fabricOnly, UnknownModdedPolicy.ALLOW), "unknown allow");
    require(!ModCompatibility.isEligible(ModLoaderFamily.UNKNOWN, fabricOnly, UnknownModdedPolicy.DENY), "unknown deny");

    Path config = Files.createTempFile("conduit-mod", ".toml");
    Files.writeString(config, """
        [listener]
        host="127.0.0.1"
        port=25565
        max-frame-bytes=64
        [forwarding]
        mode="none"
        [servers.lobby]
        host="127.0.0.1"
        port=1
        mod-loaders=["vanilla","fabric"]
        [servers.forge]
        host="127.0.0.1"
        port=2
        mod-loaders=["forge","neoforge"]
        [routing]
        initial=["lobby"]
        fallback=["forge"]
        [modded]
        enabled=true
        unknown-policy="deny"
        """);
    ConduitConfiguration loaded = ConfigurationLoader.load(config);
    require(loaded.modded().knownPacksLimit() == 1024, "default known packs");
    require(loaded.backends().getFirst().supportedModLoaders().contains(ModLoaderFamily.FABRIC), "lobby loaders");
    BackendSelector selector = new BackendSelector(loaded);
    require(selector.isEligible("lobby", 765, ModLoaderFamily.FABRIC, true), "fabric to lobby");
    require(!selector.isEligible("lobby", 765, ModLoaderFamily.FORGE, true), "forge not on lobby");
    require(selector.isEligible("forge", 765, ModLoaderFamily.NEOFORGE, true), "neo to forge backend");
    require(!selector.isEligible("forge", 765, ModLoaderFamily.UNKNOWN, true), "unknown denied");
  }

  private static void packetQueue() {
    SwitchPacketQueue queue = new SwitchPacketQueue(2);
    require(queue.isEmpty(), "empty");
    queue.enqueue(SwitchPacketQueue.Destination.NEW_BACKEND, new byte[] {1}, SwitchPacketQueue.ConnectionPhase.CONFIGURATION);
    queue.enqueue(SwitchPacketQueue.Destination.NEW_BACKEND, new byte[] {2}, SwitchPacketQueue.ConnectionPhase.CONFIGURATION);
    require(queue.size() == 2, "normal");
    try {
      queue.enqueue(SwitchPacketQueue.Destination.NEW_BACKEND, new byte[] {3}, SwitchPacketQueue.ConnectionPhase.CONFIGURATION);
      throw new AssertionError("overflow");
    } catch (SwitchPacketQueue.OverflowException expected) { /* ok */ }
    require(queue.flush(SwitchPacketQueue.Destination.NEW_BACKEND).size() == 2, "flush");
    require(queue.isEmpty(), "flushed empty");
    queue.enqueue(SwitchPacketQueue.Destination.NEW_BACKEND, new byte[] {9}, SwitchPacketQueue.ConnectionPhase.PLAY);
    queue.cancel();
    require(queue.isCancelled() && queue.isEmpty(), "cancel");
  }

  private static void doctorShowsModded() throws Exception {
    Path config = Files.createTempFile("conduit-mod-doc", ".toml");
    Files.writeString(config, """
        [listener]
        host="127.0.0.1"
        port=25565
        max-frame-bytes=64
        [forwarding]
        mode="none"
        [servers.lobby]
        host="127.0.0.1"
        port=1
        [routing]
        initial=["lobby"]
        fallback=["lobby"]
        """);
    ConduitRuntime runtime = new ConduitRuntime(ConfigurationLoader.load(config), Files.createTempDirectory("plugins-mod"), config.getParent());
    runtime.bindConfigPath(config);
    CoreCommands.register(runtime);
    AdminSource admin = new AdminSource("Op", "lobby", Set.of(
        Permissions.CONDUIT_ADMIN, Permissions.CONDUIT_INFO, Permissions.DOCTOR, Permissions.DIAGNOSTICS, Permissions.CACHE));
    runtime.commandManager().dispatch(admin, "/conduit doctor");
    require(admin.messages.stream().anyMatch(line -> line.contains("Mod compatibility")), "doctor modded");
    require(admin.messages.stream().anyMatch(line -> line.contains("Forge")), "doctor forge");
    admin.messages.clear();
    runtime.commandManager().dispatch(admin, "/conduit diagnostics");
    require(admin.messages.stream().anyMatch(line -> line.contains("Known-packs")), "diagnostics known packs");
    runtime.close();
  }

  private static KnownPacksValidator.KnownPack pack(String ns, String id, String version) {
    return new KnownPacksValidator.KnownPack(ns, id, version);
  }

  private static byte[] packs(List<KnownPacksValidator.KnownPack> packs) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, packs.size());
      for (KnownPacksValidator.KnownPack pack : packs) {
        MinecraftOutput.string(out, pack.namespace());
        MinecraftOutput.string(out, pack.id());
        MinecraftOutput.string(out, pack.version());
      }
    }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class AdminSource implements CommandSource {
    private final String username;
    private final String backend;
    private final Set<String> permissions;
    private final List<String> messages = new ArrayList<>();
    private AdminSource(String username, String backend, Set<String> permissions) {
      this.username = username; this.backend = backend; this.permissions = permissions;
    }
    @Override public String username() { return username; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public String currentBackend() { return backend; }
  }
}
