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
    adapterRewritesLoginSuccessOnlyInLogin();
    playerInfoRejectsTrailingBytes();
    playerInfoForwardsUntouchedWhenNothingSubstituted();
    playerInfoRoundTripsEveryAction776();
    switchHandoffNeverDropsBackendPlayPackets();
    mergeProxyCommandsIntoReal26_2Tree();
    joinGameOnlineModeFlag();
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
  /**
   * Login Success is id 2 in Login, but id 2 in Configuration is Configuration Disconnect on 776
   * and Finish Configuration on 765. Matching on the id alone rewrote those into a Game Profile.
   */
  private static void adapterRewritesLoginSuccessOnlyInLogin() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(packet)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS));
      GameProfiles.writeUuid(output, profile.uniqueId());
      MinecraftOutput.string(output, profile.username());
      GameProfiles.writeProperties(output, List.of());
      output.writeBoolean(true);
    }
    byte[] original = packet.toByteArray();
    byte[] inLogin = ProtocolProfileAdapter.backendToClient(protocol, ConnectionState.LOGIN, original, profile);
    require(!java.util.Arrays.equals(original, inLogin), "Login Success is still rewritten in Login");
    for (ConnectionState state : List.of(ConnectionState.CONFIGURATION, ConnectionState.PLAY)) {
      require(java.util.Arrays.equals(original, ProtocolProfileAdapter.backendToClient(protocol, state, original, profile)),
          "id 2 must not be parsed as Login Success in " + state);
    }
  }
  /** A decode that does not consume the whole packet must forward the backend bytes, not a splice. */
  private static void playerInfoRejectsTrailingBytes() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    byte[] valid = PlayerInfoUpdate.selfAdd(protocol, profile);
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 3);
    require(java.util.Arrays.equals(trailing, PlayerInfoUpdate.ensureOwnTextures(protocol, trailing, profile)),
        "trailing bytes must abort the rewrite");
    byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 2);
    require(java.util.Arrays.equals(truncated, PlayerInfoUpdate.ensureOwnTextures(protocol, truncated, profile)),
        "a truncated packet must abort the rewrite");
  }
  /** Other players' entries are forwarded transparently rather than re-encoded. */
  private static void playerInfoForwardsUntouchedWhenNothingSubstituted() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(packet)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE));
      output.writeByte(PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.UPDATE_LISTED);
      MinecraftOutput.varInt(output, 2);
      GameProfiles.writeUuid(output, new UUID(7, 7));
      MinecraftOutput.string(output, "Alex");
      GameProfiles.writeProperties(output, List.of(new ProfileProperty("textures", "b3RoZXI=", Optional.of("b3Rocw=="))));
      output.writeBoolean(true);
      GameProfiles.writeUuid(output, new UUID(8, 8));
      MinecraftOutput.string(output, "Steve");
      GameProfiles.writeProperties(output, List.of());
      output.writeBoolean(true);
    }
    byte[] original = packet.toByteArray();
    require(java.util.Arrays.equals(original, PlayerInfoUpdate.ensureOwnTextures(protocol, original, profile)),
        "foreign entries forwarded byte-for-byte");
    // The same packet carrying our own stale entry must be substituted.
    byte[] mine = PlayerInfoUpdate.selfAdd(protocol, new PlayerProfile(profile.uniqueId(), profile.username(),
        List.of(new ProfileProperty("textures", "c3RhbGU=", Optional.empty())), true));
    byte[] fixed = PlayerInfoUpdate.ensureOwnTextures(protocol, mine, profile);
    require(!java.util.Arrays.equals(mine, fixed), "our own stale entry is replaced");
  }
  /**
   * Exercises every 26.2 action bit at once: 1-byte action set, then name+properties, chat key,
   * game mode, listed, latency, optional display-name component, list priority and hat. Only the
   * textures change; everything else must survive the re-encode.
   */
  private static void playerInfoRoundTripsEveryAction776() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile profile = sample();
    int actions = PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.INITIALIZE_CHAT | PlayerInfoUpdate.UPDATE_GAME_MODE
        | PlayerInfoUpdate.UPDATE_LISTED | PlayerInfoUpdate.UPDATE_LATENCY | PlayerInfoUpdate.UPDATE_DISPLAY_NAME
        | PlayerInfoUpdate.UPDATE_LIST_PRIORITY | PlayerInfoUpdate.UPDATE_HAT;
    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(packet)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE));
      output.writeByte(actions);
      MinecraftOutput.varInt(output, 1);
      GameProfiles.writeUuid(output, profile.uniqueId());
      MinecraftOutput.string(output, profile.username());
      GameProfiles.writeProperties(output, List.of(new ProfileProperty("textures", "c3RhbGU=", Optional.empty())));
      output.writeBoolean(true);
      GameProfiles.writeUuid(output, new UUID(9, 9));
      output.writeLong(1234L);
      MinecraftOutput.varInt(output, 3); output.write(new byte[] {1, 2, 3});
      MinecraftOutput.varInt(output, 2); output.write(new byte[] {4, 5});
      MinecraftOutput.varInt(output, 1);
      output.writeBoolean(true);
      MinecraftOutput.varInt(output, 55);
      output.writeBoolean(true);
      gg.tame.conduit.protocol.NetworkNbt.stringComponent(output, "Koels");
      MinecraftOutput.varInt(output, 9);
      output.writeBoolean(true);
    }
    byte[] rewritten = PlayerInfoUpdate.ensureOwnTextures(protocol, packet.toByteArray(), profile);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rewritten))) {
      require(MinecraftInput.varInt(input) == 0x46, "id");
      require(input.readUnsignedByte() == actions, "all eight action bits survive one byte");
      require(MinecraftInput.varInt(input) == 1, "count");
      require(GameProfiles.readUuid(input).equals(profile.uniqueId()), "uuid");
      require(MinecraftInput.string(input, 16).equals("Koels"), "name");
      var properties = GameProfiles.readProperties(input);
      require(properties.size() == 1 && properties.getFirst().value().equals("c2tpbg==")
          && properties.getFirst().signature().orElseThrow().equals("c2ln"), "signed textures substituted");
      require(input.readBoolean(), "chat present");
      require(GameProfiles.readUuid(input).equals(new UUID(9, 9)), "chat session uuid");
      require(input.readLong() == 1234L, "chat expiry");
      require(MinecraftInput.bytes(input, 8192).length == 3, "chat key");
      require(MinecraftInput.bytes(input, 8192).length == 2, "chat signature");
      require(MinecraftInput.varInt(input) == 1, "game mode");
      require(input.readBoolean(), "listed");
      require(MinecraftInput.varInt(input) == 55, "latency");
      require(input.readBoolean(), "display name present");
      gg.tame.conduit.protocol.NetworkNbt.skip(input);
      require(MinecraftInput.varInt(input) == 9, "list priority");
      require(input.readBoolean(), "hat");
      require(input.available() == 0, "no trailing bytes");
    }
  }
  /**
   * The backend reaches Play before the client finishes reconfiguring. Its player-info ADD_PLAYER
   * lands in that window, so it has to be forwarded rather than discarded.
   */
  private static void switchHandoffNeverDropsBackendPlayPackets() {
    require(gg.tame.conduit.session.PlayerSession.handoff(ConnectionState.PLAY, ConnectionState.PLAY)
        == gg.tame.conduit.session.PlayerSession.Handoff.FORWARD_PLAY, "backend Play packets reach the client");
    require(gg.tame.conduit.session.PlayerSession.handoff(ConnectionState.CONFIGURATION, ConnectionState.CONFIGURATION)
        == gg.tame.conduit.session.PlayerSession.Handoff.FORWARD_CONFIGURATION, "configuration still forwarded");
    require(gg.tame.conduit.session.PlayerSession.handoff(ConnectionState.CONFIGURATION, ConnectionState.PLAY)
        == gg.tame.conduit.session.PlayerSession.Handoff.FORWARD_CONFIGURATION, "late configuration still forwarded");
  }
  /**
   * A real 2338-node Declare Commands tree captured from a 26.2 backend. The old decoder died on
   * it with EOFException because 26.2 shifted every brigadier parser id down by one; the merge must
   * now append without decoding argument properties, leaving every other node byte-identical.
   */
  private static void mergeProxyCommandsIntoReal26_2Tree() throws Exception {
    java.io.InputStream stream = ProfileTests.class.getResourceAsStream("/command-tree-776.bin");
    if (stream == null) { System.out.println("command-tree-776.bin fixture absent, skipping"); return; }
    byte[] original;
    try (stream) { original = stream.readAllBytes(); }
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    List<String> servers = List.of("lobby", "survival", "smp", "mctt", "hub3");
    byte[] merged = gg.tame.conduit.command.CommandGraphs.mergeProxyCommands(protocol, original, servers);

    int[] before = header(original);
    int[] after = header(merged);
    require(before[0] == 0x10 && after[0] == 0x10, "packet id preserved");
    // conduit + server(5) + send(6) = 1 + 1+5 + 1+6 = 14 new nodes.
    require(after[1] == before[1] + 14, "node count grew by the proxy nodes, got " + (after[1] - before[1]));
    require(after[3] == before[3] + 3, "root gained exactly three children");
    for (int index = 0; index < before[3]; index++) {
      require(rootChild(original, index) == rootChild(merged, index), "existing root child " + index + " unchanged");
    }
    require(rootChild(merged, before[3]) == before[1], "first proxy node appended after the original nodes");
    require(merged[merged.length - 1] == 0, "root index still 0");
    // The untouched middle must survive byte for byte.
    int originalTail = original.length - 1 - rootNodeEnd(original);
    require(java.util.Arrays.equals(
        java.util.Arrays.copyOfRange(original, rootNodeEnd(original), original.length - 1),
        java.util.Arrays.copyOfRange(merged, rootNodeEnd(merged), rootNodeEnd(merged) + originalTail)),
        "backend nodes copied verbatim");
    require(new String(merged, java.nio.charset.StandardCharsets.UTF_8).contains("survival"), "server names present");
  }
  /** {packetId, nodeCount, rootNodeOffset, rootChildCount} */
  private static int[] header(byte[] packet) throws Exception {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    int id = MinecraftInput.varInt(input);
    int count = MinecraftInput.varInt(input);
    int offset = packet.length - input.available();
    input.readByte();
    int children = MinecraftInput.varInt(input);
    return new int[] {id, count, offset, children};
  }
  private static int rootChild(byte[] packet, int index) throws Exception {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input); MinecraftInput.varInt(input); input.readByte();
    int count = MinecraftInput.varInt(input);
    int value = 0;
    for (int position = 0; position <= index && position < count; position++) value = MinecraftInput.varInt(input);
    return value;
  }
  private static int rootNodeEnd(byte[] packet) throws Exception {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input); MinecraftInput.varInt(input); input.readByte();
    int count = MinecraftInput.varInt(input);
    for (int index = 0; index < count; index++) MinecraftInput.varInt(input);
    return packet.length - input.available();
  }
  /**
   * 26.2 Join Game ends with onlineMode then enforcesSecureChat, both single-byte booleans (from
   * ClientboundLoginPacket.write in the 26.2 client). The client draws TAB faces only when
   * onlineMode is set, and a modern-forwarding backend always reports false.
   */
  private static void joinGameOnlineModeFlag() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    PlayerProfile authenticated = sample();
    PlayerProfile offline = new PlayerProfile(authenticated.uniqueId(), authenticated.username(), List.of(), false);
    byte[] backend = joinGame(protocol, false, true);

    byte[] fixed = gg.tame.conduit.protocol.JoinGame.markOnlineMode(protocol, backend, authenticated);
    require(fixed[fixed.length - 2] == 1, "onlineMode set for an authenticated session");
    require(fixed[fixed.length - 1] == 1, "enforcesSecureChat untouched");
    require(fixed.length == backend.length, "packet length unchanged");
    require(java.util.Arrays.equals(
        java.util.Arrays.copyOfRange(backend, 0, backend.length - 2),
        java.util.Arrays.copyOfRange(fixed, 0, fixed.length - 2)), "everything before the flag is untouched");

    require(java.util.Arrays.equals(backend, gg.tame.conduit.protocol.JoinGame.markOnlineMode(protocol, backend, offline)),
        "offline sessions are never claimed to be online-mode");
    byte[] already = joinGame(protocol, true, false);
    require(java.util.Arrays.equals(already, gg.tame.conduit.protocol.JoinGame.markOnlineMode(protocol, already, authenticated)),
        "a backend that already reports online-mode is left alone");
    // A trailing byte that is not a boolean means the layout is not what we think it is.
    byte[] malformed = joinGame(protocol, false, true);
    malformed[malformed.length - 1] = 7;
    require(java.util.Arrays.equals(malformed, gg.tame.conduit.protocol.JoinGame.markOnlineMode(protocol, malformed, authenticated)),
        "a non-boolean trailer aborts the rewrite");
    // 765 has no such field and must never be touched.
    ProtocolDefinition v765 = ProtocolDefinition.forVersion(765);
    byte[] old = joinGame(v765, false, false);
    require(java.util.Arrays.equals(old, gg.tame.conduit.protocol.JoinGame.markOnlineMode(v765, old, authenticated)), "765 untouched");
    // Non-Join-Game Play packets are untouched even when their last bytes look like booleans.
    byte[] other = {0x18, 0, 1, 0, 1};
    require(java.util.Arrays.equals(other, gg.tame.conduit.protocol.JoinGame.markOnlineMode(protocol, other, authenticated)),
        "only Join Game is rewritten");
  }
  private static byte[] joinGame(ProtocolDefinition protocol, boolean onlineMode, boolean secureChat) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN));
      output.writeInt(7);                       // playerId
      output.writeBoolean(false);               // hardcore
      MinecraftOutput.varInt(output, 1);        // levels
      MinecraftOutput.string(output, "minecraft:overworld");
      MinecraftOutput.varInt(output, 20);       // maxPlayers
      MinecraftOutput.varInt(output, 10);       // chunkRadius
      MinecraftOutput.varInt(output, 10);       // simulationDistance
      output.writeBoolean(false);               // reducedDebugInfo
      output.writeBoolean(true);                // showDeathScreen
      output.writeBoolean(false);               // doLimitedCrafting
      output.write(new byte[] {0, 1, 2, 3});    // CommonPlayerSpawnInfo, never decoded
      output.writeBoolean(onlineMode);
      output.writeBoolean(secureChat);
    }
    return bytes.toByteArray();
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
    // Cross-checked against ViaVersion's ClientboundPackets1_20_3 / ClientboundPackets26_1.
    require(ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE) == 0x3C, "765 player info");
    require(ProtocolDefinition.forVersion(765).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE) == 0x3B, "765 player info remove");
    require(ProtocolDefinition.forVersion(776).id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE) == 0x45, "776 player info remove");
  }
  private static PlayerProfile sample() {
    return new PlayerProfile(UUID.fromString("80c43b34-0c58-478b-a0ae-71b844970446"), "Koels",
        List.of(new ProfileProperty("textures", "c2tpbg==", Optional.of("c2ln"))), true);
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
