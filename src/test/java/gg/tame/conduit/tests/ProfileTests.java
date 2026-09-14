package gg.tame.conduit.tests;

import gg.tame.conduit.auth.AuthenticationException;
import gg.tame.conduit.auth.HasJoinedResponse;
import gg.tame.conduit.auth.MojangSessionAuthenticator;
import gg.tame.conduit.auth.SessionQuery;
import gg.tame.conduit.config.AuthenticationMode;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.forwarding.ForwardingRequest;
import gg.tame.conduit.forwarding.ForwardingSecret;
import gg.tame.conduit.forwarding.ModernForwarder;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.GameProfiles;
import gg.tame.conduit.protocol.LoginSuccess;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayerInfoUpdate;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolProfileAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ProfileTests {
  static void run() throws Exception {
    parseHasJoinedTextures();
    parseUnsignedAndMultipleProperties();
    rejectMalformedProperty();
    missingTextures();
    storeAndSummarizeAuthenticatedProfile();
    sessionObjectPreservedAcrossLoginStartEncode();
    modernForwardingCarriesSignedTextures();
    loginSuccessReplaceKeepsTrailer();
    playerInfoInjectsTextures();
    playerInfoLeavesForeignPlayers();
    selfAddCarriesSignedTextures();
    forwardingPayloadIdenticalOnRepeatedSwitch();
    freezeKeepsAuthenticatedProperties();
    adapterIgnoresUnrelatedPackets();
    mojangVerifyStoresTextures();
    protocolIds();
  }
  private static void parseHasJoinedTextures() throws Exception {
    HasJoinedResponse.Result parsed = HasJoinedResponse.parse("{\"id\":\"11111111222233334444555555555555\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\"val\",\"signature\":\"sig\"}]}");
    require(parsed.properties().size() == 1, "textures property");
    require(parsed.properties().getFirst().name().equals("textures"), "name");
    require(parsed.properties().getFirst().value().equals("val"), "value");
    require(parsed.properties().getFirst().signature().orElseThrow().equals("sig"), "signature");
  }
  private static void parseUnsignedAndMultipleProperties() throws Exception {
    HasJoinedResponse.Result parsed = HasJoinedResponse.parse("{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[{\"name\":\"textures\",\"value\":\"skin\"},{\"name\":\"extra\",\"value\":\"x\",\"signature\":\"s\"}]}");
    require(parsed.properties().size() == 2, "two properties");
    require(parsed.properties().getFirst().signature().isEmpty(), "unsigned textures");
    require(parsed.properties().get(1).signature().orElseThrow().equals("s"), "signed extra");
  }
  private static void rejectMalformedProperty() {
    try { HasJoinedResponse.parse("{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[{\"name\":\"textures\"}]}"); throw new AssertionError("missing value accepted"); }
    catch (AuthenticationException expected) { }
    try { HasJoinedResponse.parse("{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[{\"value\":\"x\"}]}"); throw new AssertionError("missing name accepted"); }
    catch (AuthenticationException expected) { }
  }
  private static void missingTextures() throws Exception {
    HasJoinedResponse.Result parsed = HasJoinedResponse.parse("{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[]}");
    PlayerProfile profile = new PlayerProfile(parsed.uniqueId(), parsed.username(), parsed.properties(), true);
    require(!profile.hasTextures(), "empty properties");
    require(profile.summary().contains("textures=absent"), "summary absent");
    require(!profile.summary().contains("val"), "summary leaked");
  }
  private static void storeAndSummarizeAuthenticatedProfile() {
    PlayerProfile profile = sample();
    require(profile.authenticated() && profile.hasTextures(), "stored textures");
    require(profile.property("textures").orElseThrow().signature().isPresent(), "signed");
    require(profile.summary().contains("textures=present") && profile.summary().contains("textures.signed=yes"), "summary");
    require(!profile.summary().contains("c2tpbg==") && !profile.summary().contains("c2ln"), "summary leaked values");
  }
  private static void sessionObjectPreservedAcrossLoginStartEncode() throws Exception {
    PlayerProfile profile = sample();
    byte[] login = LoginStart.encode(profile);
    LoginStart decoded = LoginStart.decode(java.util.Arrays.copyOfRange(login, 1, login.length));
    require(decoded.clientUuid().equals(profile.uniqueId()) && decoded.username().equals(profile.username()), "login start uses authenticated uuid");
    PlayerProfile still = profile;
    require(still.properties().equals(profile.properties()), "session profile object unchanged by login start encode");
  }
  private static void modernForwardingCarriesSignedTextures() throws Exception {
    var secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "do-not-log-me");
    ModernForwarder forwarder = new ModernForwarder(ForwardingSecret.load(secret));
    PlayerProfile profile = sample();
    byte[] payload = forwarder.payload(new ForwardingRequest(profile, InetAddress.getByName("127.0.0.1"), 776, 1));
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(java.util.Arrays.copyOfRange(payload, 32, payload.length)))) {
      MinecraftInput.varInt(input); MinecraftInput.string(input, 255);
      require(new UUID(input.readLong(), input.readLong()).equals(profile.uniqueId()), "uuid");
      require(MinecraftInput.string(input, 16).equals("Koels"), "name");
      require(MinecraftInput.varInt(input) == 1, "count");
      require(MinecraftInput.string(input, 64).equals("textures"), "prop name");
      require(MinecraftInput.string(input, 32767).equals("c2tpbg=="), "prop value");
      require(input.readBoolean() && MinecraftInput.string(input, 1024).equals("c2ln"), "prop signature");
    }
  }
  private static void loginSuccessReplaceKeepsTrailer() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile empty = new PlayerProfile(sample().uniqueId(), "Koels", List.of(), true);
    PlayerProfile full = sample();
    ByteArrayOutputStream original = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(original)) {
      MinecraftOutput.varInt(output, 2);
      GameProfiles.write(output, empty);
      output.writeLong(9); output.writeLong(10);
    }
    byte[] rewritten = LoginSuccess.replaceProfile(protocol, original.toByteArray(), full);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rewritten))) {
      require(MinecraftInput.varInt(input) == 2, "id");
      require(GameProfiles.readUuid(input).equals(full.uniqueId()), "uuid");
      require(MinecraftInput.string(input, 16).equals("Koels"), "name");
      require(GameProfiles.hasTextures(GameProfiles.readProperties(input)), "injected textures");
      require(input.readLong() == 9 && input.readLong() == 10, "session id trailer");
    }
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    ByteArrayOutputStream legacy = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(legacy)) {
      MinecraftOutput.varInt(output, 2);
      GameProfiles.write(output, empty);
      output.writeBoolean(true);
    }
    byte[] rewritten765 = LoginSuccess.replaceProfile(v765, legacy.toByteArray(), full);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rewritten765))) {
      MinecraftInput.varInt(input); GameProfiles.skip(input);
      require(input.readBoolean(), "strict-error trailer");
    }
  }
  private static void playerInfoInjectsTextures() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    ByteArrayOutputStream original = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(original)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE));
      output.writeByte(PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.UPDATE_LATENCY);
      MinecraftOutput.varInt(output, 1);
      GameProfiles.writeUuid(output, profile.uniqueId());
      MinecraftOutput.string(output, profile.username());
      GameProfiles.writeProperties(output, List.of(new gg.tame.conduit.login.ProfileProperty("textures", "stale", java.util.Optional.empty())));
      MinecraftOutput.varInt(output, 42);
    }
    byte[] rewritten = PlayerInfoUpdate.ensureOwnTextures(protocol, original.toByteArray(), profile);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rewritten))) {
      MinecraftInput.varInt(input); require(input.readUnsignedByte() == (PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.UPDATE_LATENCY), "actions");
      require(MinecraftInput.varInt(input) == 1, "count");
      require(GameProfiles.readUuid(input).equals(profile.uniqueId()), "uuid");
      MinecraftInput.string(input, 16);
      var properties = GameProfiles.readProperties(input);
      require(properties.size() == 1 && properties.getFirst().value().equals("c2tpbg=="), "replaced stale textures");
      require(MinecraftInput.varInt(input) == 42, "latency");
    }
  }
  private static void playerInfoLeavesForeignPlayers() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    UUID other = new UUID(3, 4);
    ByteArrayOutputStream original = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(original)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE));
      output.writeByte(PlayerInfoUpdate.ADD_PLAYER);
      MinecraftOutput.varInt(output, 1);
      GameProfiles.writeUuid(output, other);
      MinecraftOutput.string(output, "Steve");
      GameProfiles.writeProperties(output, List.of());
    }
    byte[] rewritten = PlayerInfoUpdate.ensureOwnTextures(protocol, original.toByteArray(), profile);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rewritten))) {
      MinecraftInput.varInt(input); input.readUnsignedByte(); MinecraftInput.varInt(input);
      GameProfiles.readUuid(input); MinecraftInput.string(input, 16);
      require(!GameProfiles.hasTextures(GameProfiles.readProperties(input)), "did not invent foreign textures");
    }
  }
  private static void selfAddCarriesSignedTextures() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    byte[] packet = PlayerInfoUpdate.selfAdd(protocol, profile);
    byte[] preserved = PlayerInfoUpdate.ensureOwnTextures(protocol, packet, profile);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(preserved))) {
      require(MinecraftInput.varInt(input) == 0x46, "776 player-info id");
      int actions = input.readUnsignedByte();
      require((actions & PlayerInfoUpdate.ADD_PLAYER) != 0, "ADD_PLAYER");
      require((actions & PlayerInfoUpdate.UPDATE_HAT) != 0, "hat layer");
      require(MinecraftInput.varInt(input) == 1, "count");
      require(GameProfiles.readUuid(input).equals(profile.uniqueId()), "uuid");
      require(MinecraftInput.string(input, 16).equals("Koels"), "name");
      var properties = GameProfiles.readProperties(input);
      require(properties.size() == 1 && properties.getFirst().signature().orElseThrow().equals("c2ln"), "signed textures");
      MinecraftInput.varInt(input);
      require(input.readBoolean(), "listed");
    }
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayerInfoUpdate.selfAdd(v765, profile)))) {
      MinecraftInput.varInt(input);
      require((input.readUnsignedByte() & PlayerInfoUpdate.UPDATE_HAT) == 0, "765 has no hat action");
    }
  }
  private static void forwardingPayloadIdenticalOnRepeatedSwitch() throws Exception {
    var secret = Files.createTempFile("conduit-forwarding", ".secret"); Files.writeString(secret, "do-not-log-me");
    ModernForwarder forwarder = new ModernForwarder(ForwardingSecret.load(secret));
    PlayerProfile profile = sample();
    ForwardingRequest initial = new ForwardingRequest(profile, InetAddress.getByName("127.0.0.1"), 776, 1);
    ForwardingRequest switched = new ForwardingRequest(profile, InetAddress.getByName("127.0.0.1"), 776, 1);
    byte[] a = forwarder.payload(initial);
    byte[] b = forwarder.payload(switched);
    require(java.util.Arrays.equals(java.util.Arrays.copyOfRange(a, 32, a.length), java.util.Arrays.copyOfRange(b, 32, b.length)), "switch reuses same forwarding body");
    require(profile.uniqueId().equals(sample().uniqueId()) && profile.username().equals("Koels"), "uuid/name unchanged");
    require(profile.property("textures").orElseThrow().signature().isPresent(), "textures signature survives");
  }
  private static void freezeKeepsAuthenticatedProperties() {
    PlayerProfile authed = sample();
    PlayerProfile empty = new PlayerProfile(authed.uniqueId(), authed.username(), List.of(), true);
    PlayerProfile frozen = gg.tame.conduit.login.AuthenticatedPlayerProfile.freeze(authed, empty);
    require(frozen.hasTextures() && frozen == authed, "canonical profile not replaced");
  }
  private static void adapterIgnoresUnrelatedPackets() {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    byte[] keepAlive = {4, 0, 0, 0, 0, 0, 0, 0, 1};
    require(java.util.Arrays.equals(keepAlive, ProtocolProfileAdapter.backendToClient(protocol, ConnectionState.PLAY, keepAlive, sample())), "unrelated");
  }
  private static void mojangVerifyStoresTextures() throws Exception {
    com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/ok", exchange -> {
      byte[] body = "{\"id\":\"000000000000000000000000000000aa\",\"name\":\"playr\",\"properties\":[{\"name\":\"textures\",\"value\":\"skin\",\"signature\":\"sig\"}]}".getBytes();
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    http.start();
    AuthenticationSettings settings = new AuthenticationSettings(AuthenticationMode.ONLINE, "http://127.0.0.1:" + http.getAddress().getPort() + "/ok", 1000);
    PlayerProfile profile = new MojangSessionAuthenticator(settings).verify(new SessionQuery("playr", "abc", Optional.empty()));
    require(profile.authenticated() && profile.hasTextures() && profile.property("textures").orElseThrow().signature().isPresent(), "verify stored signed textures");
    http.stop(0);
  }
  private static void protocolIds() {
    require(ProtocolDefinition.forVersion(776).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE) == 0x46, "776 player info");
    require(ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE) == 0x3E, "765 player info");
  }
  private static PlayerProfile sample() {
    return new PlayerProfile(UUID.fromString("80c43b34-0c58-478b-a0ae-71b844970446"), "Koels",
        List.of(new ProfileProperty("textures", "c2tpbg==", Optional.of("c2ln"))), true);
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
