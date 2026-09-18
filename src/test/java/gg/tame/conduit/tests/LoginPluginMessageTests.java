// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.BackendLoginPluginMessageEvent;
import gg.tame.conduit.api.event.player.GameProfileRequestEvent;
import gg.tame.conduit.api.event.player.PlayerLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerPreLoginEvent;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.tests.LoginFlowTests.Proxy;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Login plugin messages in both directions: a plugin asking the client while it logs in to the proxy
 * (PlayerPreLoginEvent.sendLoginPluginMessage, Velocity's LoginPhaseConnection), and a backend's own
 * queries put to plugins first (BackendLoginPluginMessageEvent, Velocity's ServerLoginPluginMessageEvent).
 * A scripted 1.20.4 client and backend, a 1.8 client, and a compiled Velocity plugin.
 */
public final class LoginPluginMessageTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    pluginsAskTheClientAndAnswerTheBackend();
    aClientBefore113CannotBeAsked();
    modernForwardingIsUntouchedBesideAListener();
    velocityPluginsDoBoth();
    System.out.println("LoginPluginMessageTests OK");
  }

  private static final Map<String, String> CLIENT_KNOWS = Map.of("conduit:hello", "there", "conduit:later", "late",
      "mod:relay", "from-client", "vplug:ask", "yes");

  private static void pluginsAskTheClientAndAnswerTheBackend() throws Exception {
    try (QueryBackend lobby = new QueryBackend(false, List.of("mod:relay", "mod:plugin"));
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty())) {
      AtomicReference<PlayerPreLoginEvent> preLogin = new AtomicReference<>();
      List<CompletableFuture<byte[]>> answers = Collections.synchronizedList(new ArrayList<>());
      AtomicReference<String> badChannel = new AtomicReference<>();
      AtomicReference<Boolean> answeredBeforeLogin = new AtomicReference<>();
      List<String> backendQueries = Collections.synchronizedList(new ArrayList<>());
      proxy.recorder.hook = event -> {
        if (event instanceof PlayerPreLoginEvent pre) {
          preLogin.set(pre);
          answers.add(pre.sendLoginPluginMessage("conduit:hello", bytes("hi")));
          answers.add(pre.sendLoginPluginMessage("conduit:unknown", bytes("?")));
          try { pre.sendLoginPluginMessage("Not A Channel", bytes("x")); } catch (IllegalArgumentException refused) { badChannel.set(refused.getMessage()); }
        }
        if (event instanceof GameProfileRequestEvent profile) answers.add(profile.sendLoginPluginMessage("conduit:later", bytes("l")));
        if (event instanceof PlayerLoginEvent) answeredBeforeLogin.set(answers.get(0).isDone() && answers.get(1).isDone() && !answers.get(2).isDone());
        if (event instanceof BackendLoginPluginMessageEvent query) {
          backendQueries.add(query.channel() + "#" + query.messageId() + "@" + query.server().getName() + ":" + text(query.data()));
          if (query.channel().equals("mod:plugin")) query.reply(bytes("from-plugin"));
        }
      };
      try (LoginClient client = LoginClient.open(proxy.port(), "Asked", 765)) {
        client.awaitPlay();
        require(answers.size() == 3, "three requests");
        require(text(answers.get(0).get(5, TimeUnit.SECONDS)).equals("there"), "an understood channel's answer");
        require(answers.get(1).get(5, TimeUnit.SECONDS) == null, "null for a channel the client did not understand");
        require(text(answers.get(2).get(5, TimeUnit.SECONDS)).equals("late"), "a request made later in the login");
        require(Boolean.TRUE.equals(answeredBeforeLogin.get()), "PlayerPreLoginEvent's requests were answered before PlayerLoginEvent, the later one after it");
        require(badChannel.get() != null, "a channel that is not a namespaced key is refused");

        List<String> seen = client.requests();
        require(seen.size() == 4 && seen.get(0).startsWith("conduit:hello#") && seen.get(1).startsWith("conduit:unknown#")
            && seen.get(2).startsWith("conduit:later#") && seen.get(3).equals("mod:relay#1:mod:relay"), "the client was asked, in order: " + seen);
        Set<Integer> ids = new java.util.HashSet<>();
        for (String request : seen.subList(0, 3)) ids.add(Integer.parseInt(request.substring(request.indexOf('#') + 1, request.indexOf(':', request.indexOf('#')))));
        require(ids.size() == 3 && ids.stream().allMatch(id -> id > 1_000_000), "distinct ids well clear of a backend's: " + ids);

        require(backendQueries.equals(List.of("mod:relay#1@lobby:mod:relay", "mod:plugin#2@lobby:mod:plugin")), "both backend queries asked plugins: " + backendQueries);
        require(waitFor(() -> lobby.answers.size() == 2, 10_000)
            && Set.copyOf(lobby.answers).equals(Set.of("1:true:from-client", "2:true:from-plugin")),
            "the backend got the client's answer to one and the plugin's to the other: " + lobby.answers);
        boolean decided = false;
        try { preLogin.get().sendLoginPluginMessage("conduit:late", bytes("x")); } catch (IllegalStateException expected) { decided = true; }
        require(decided, "once the login is decided nothing more can be asked");
      }
    }
  }

  private static void aClientBefore113CannotBeAsked() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, AuthenticationSettings.offline(), null, null)) {
      AtomicReference<String> refused = new AtomicReference<>();
      proxy.recorder.hook = event -> {
        if (!(event instanceof PlayerPreLoginEvent pre)) return;
        try { pre.sendLoginPluginMessage("conduit:hello", bytes("hi")); } catch (IllegalStateException expected) { refused.set(expected.getMessage()); }
      };
      try (Client client = Client.join(proxy.port(), "Oldie")) {
        require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "the 1.8 client logged in as ever");
        require(refused.get() != null && refused.get().contains("1.13"), "and the request was refused, saying why: " + refused.get());
      }
    }
  }

  private static void modernForwardingIsUntouchedBesideAListener() throws Exception {
    Path secret = TempFiles.file("login-plugin-forwarding", ".secret");
    Files.writeString(secret, "plugin-message-secret");
    try (QueryBackend lobby = new QueryBackend(true, List.of("mod:relay"));
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.MODERN, Optional.of(secret))) {
      List<String> heard = Collections.synchronizedList(new ArrayList<>());
      proxy.recorder.hook = event -> {
        if (event instanceof BackendLoginPluginMessageEvent query) heard.add(query.channel());
      };
      try (LoginClient client = LoginClient.open(proxy.port(), "Forwarded", 765)) {
        client.awaitPlay();
        require(lobby.forwarded.equals(List.of("Forwarded")), "modern forwarding still answered the backend: " + lobby.forwarded);
        require(heard.equals(List.of("mod:relay")), "and the listener heard only the other query: " + heard);
        require(waitFor(() -> lobby.answers.equals(List.of("1:true:from-client")), 10_000), "which the client answered: " + lobby.answers);
      }
    }
  }

  private static final String LOGINV = """
      package loginv;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.PreLoginEvent;
      import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.LoginPhaseConnection;
      import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
      import java.nio.charset.StandardCharsets;

      @Plugin(id = "loginv", name = "LoginV", version = "1")
      public final class LoginV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("login.test.signals")).add(value); }
        @Subscribe public void preLogin(PreLoginEvent event) {
          LoginPhaseConnection connection = (LoginPhaseConnection) event.getConnection();
          try {
            connection.sendLoginPluginMessage(MinecraftChannelIdentifier.from("vplug:ask"), "q".getBytes(StandardCharsets.UTF_8),
                answer -> signal("answer:ask:" + (answer == null ? "null" : new String(answer, StandardCharsets.UTF_8))));
            connection.sendLoginPluginMessage(MinecraftChannelIdentifier.from("vplug:nope"), new byte[0],
                answer -> signal("answer:nope:" + (answer == null ? "null" : new String(answer, StandardCharsets.UTF_8))));
          } catch (IllegalStateException refused) {
            signal("refused:" + event.getUsername());
          }
        }
        @Subscribe public void serverQuery(ServerLoginPluginMessageEvent event) {
          signal("server:" + event.getIdentifier().getId() + ":" + event.getSequenceId() + ":" + new String(event.getContents(), StandardCharsets.UTF_8)
              + ":" + event.getConnection().getPlayer().getUsername() + ":" + event.getConnection().getServerInfo().getName());
          if (event.getIdentifier().getId().equals("mod:plugin")) {
            event.setResult(ServerLoginPluginMessageEvent.ResponseResult.reply("velocity-reply".getBytes(StandardCharsets.UTF_8)));
          }
        }
      }
      """;

  private static void velocityPluginsDoBoth() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("login.test.signals", signals);
    try {
      Path plugins = LoginFlowTests.compiledPlugin("loginv.LoginV", LOGINV);
      try (QueryBackend lobby = new QueryBackend(false, List.of("mod:relay", "mod:plugin"));
           Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, plugins, ForwardingMode.NONE, Optional.empty())) {
        require(waitFor(() -> proxy.runtime.plugins().plugin("loginv").isPresent(), 10_000), "loginv enabled");
        try (LoginClient client = LoginClient.open(proxy.port(), "VAsked", 765)) {
          client.awaitPlay();
          require(waitFor(() -> signals.containsAll(List.of("answer:ask:yes", "answer:nope:null")), 10_000),
              "each consumer got the client's answer, null where it did not understand: " + signals);
          require(signals.containsAll(List.of("server:mod:relay:1:mod:relay:VAsked:lobby", "server:mod:plugin:2:mod:plugin:VAsked:lobby")),
              "ServerLoginPluginMessageEvent for each backend query: " + signals);
          require(waitFor(() -> Set.copyOf(lobby.answers).equals(Set.of("1:true:from-client", "2:true:velocity-reply")), 10_000),
              "the plugin's reply reached the backend, and unknown() left the other to the client: " + lobby.answers);
          require(client.requests().stream().noneMatch(request -> request.startsWith("mod:plugin")), "the client never saw the one the plugin answered");
        }
      }
      try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, AuthenticationSettings.offline(), null, plugins)) {
        require(waitFor(() -> proxy.runtime.plugins().plugin("loginv").isPresent(), 10_000), "loginv enabled");
        try (Client client = Client.join(proxy.port(), "VOldie")) {
          require(proxy.recorder.await(PlayerPostLoginEvent.class, 1), "the 1.8 client logged in");
          require(signals.contains("refused:VOldie"), "sendLoginPluginMessage threw IllegalStateException for it: " + signals);
        }
      }
    } finally {
      System.getProperties().remove("login.test.signals");
    }
  }

  // --- harness ------------------------------------------------------------------------------------

  /**
   * A 1.20.4 server that, before Login Success, asks modern forwarding's query when told to, then its
   * own queries (ids 1, 2, ...) back to back, and keeps each answer as id:understood:data. It then
   * configures the player at once and sends a Join Game.
   */
  static final class QueryBackend implements AutoCloseable {
    private final boolean modern;
    private final List<String> queries;
    private final ServerSocket listener = new ServerSocket(0);
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    final List<String> answers = Collections.synchronizedList(new ArrayList<>());
    final List<String> forwarded = Collections.synchronizedList(new ArrayList<>());
    QueryBackend(boolean modern, List<String> queries) throws IOException {
      this.modern = modern; this.queries = queries;
      Thread.ofPlatform().daemon().name("login-query-backend").start(() -> {
        try {
          while (true) {
            Socket socket = listener.accept();
            sockets.add(socket);
            Thread.ofPlatform().daemon().start(() -> serve(socket));
          }
        } catch (IOException closed) { }
      });
    }
    BackendServer server() { return new BackendServer("lobby", new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }
    private void serve(Socket socket) {
      try (socket) {
        var in = socket.getInputStream();
        var out = socket.getOutputStream();
        if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() != 2) return;
        var start = new DataInputStream(new ByteArrayInputStream(MinecraftFrames.read(in, 4096)));
        MinecraftInput.varInt(start);
        String player = MinecraftInput.string(start, 16);
        UUID uuid = new UUID(start.readLong(), start.readLong());
        if (modern) {
          MinecraftFrames.write(out, packet(0x04, output -> { MinecraftOutput.varInt(output, 7); MinecraftOutput.string(output, "velocity:player_info"); }));
          var answer = new DataInputStream(new ByteArrayInputStream(MinecraftFrames.read(in, 1 << 16)));
          MinecraftInput.varInt(answer);
          require(MinecraftInput.varInt(answer) == 7 && answer.readBoolean(), "the forwarding query was answered");
          answer.readNBytes(32);
          MinecraftInput.varInt(answer);
          MinecraftInput.string(answer, 255);
          answer.readLong(); answer.readLong();
          forwarded.add(MinecraftInput.string(answer, 16));
        }
        for (int index = 0; index < queries.size(); index++) {
          int messageId = index + 1;
          String channel = queries.get(index);
          MinecraftFrames.write(out, packet(0x04, output -> {
            MinecraftOutput.varInt(output, messageId); MinecraftOutput.string(output, channel); output.write(channel.getBytes(StandardCharsets.UTF_8));
          }));
        }
        for (int index = 0; index < queries.size(); index++) {
          var answer = new DataInputStream(new ByteArrayInputStream(MinecraftFrames.read(in, 1 << 16)));
          require(MinecraftInput.varInt(answer) == 0x02, "a Login Plugin Response");
          int messageId = MinecraftInput.varInt(answer);
          boolean understood = answer.readBoolean();
          answers.add(messageId + ":" + understood + ":" + new String(answer.readAllBytes(), StandardCharsets.UTF_8));
        }
        MinecraftFrames.write(out, packet(0x02, output -> {
          output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
          MinecraftOutput.string(output, player); MinecraftOutput.varInt(output, 0);
        }));
        while (MinecraftFrames.read(in, 1 << 16)[0] != 0x03) { }
        MinecraftFrames.write(out, new byte[] {0x02});
        while (MinecraftFrames.read(in, 1 << 16)[0] != 0x02) { }
        MinecraftFrames.write(out, DirectLoginCompressionTests.joinGame765());
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (Exception ended) { }
    }
    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  /**
   * A 1.20.4 client that answers every Login Plugin Request as {@link #CLIENT_KNOWS} says (not
   * understood otherwise), keeps each as channel#id:data, and goes on to Play as the real one does.
   */
  static final class LoginClient implements AutoCloseable {
    private final Socket socket;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String state = "LOGIN";
    private LoginClient(Socket socket) { this.socket = socket; }
    static LoginClient open(int port, String name, int protocol) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(20_000);
      LoginClient client = new LoginClient(socket);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(protocol, "localhost", 25565, 2).encode());
      UUID uuid = LoginStart.offlineUuid(name);
      MinecraftFrames.write(socket.getOutputStream(), packet(0, output -> {
        MinecraftOutput.string(output, name); output.writeLong(uuid.getMostSignificantBits()); output.writeLong(uuid.getLeastSignificantBits());
      }));
      Thread.ofPlatform().daemon().name("login-query-client-" + name).start(client::read);
      return client;
    }
    private void read() {
      try {
        while (true) {
          byte[] frame = MinecraftFrames.read(socket.getInputStream(), 1 << 21);
          int id = frame[0];
          if (state.equals("LOGIN") && id == 0x04) {
            var input = new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1));
            int messageId = MinecraftInput.varInt(input);
            String channel = MinecraftInput.string(input, 32767);
            requests.add(channel + "#" + messageId + ":" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
            String answer = CLIENT_KNOWS.get(channel);
            send(packet(0x02, output -> {
              MinecraftOutput.varInt(output, messageId);
              output.writeBoolean(answer != null);
              if (answer != null) output.write(answer.getBytes(StandardCharsets.UTF_8));
            }));
          } else if (state.equals("LOGIN") && id == 0x02) {
            state = "CONFIGURATION";
            send(new byte[] {0x03});
          } else if (state.equals("CONFIGURATION") && id == 0x02) {
            state = "PLAY";
            send(new byte[] {0x02});
          }
        }
      } catch (IOException closed) { }
    }
    private synchronized void send(byte[] packet) throws IOException { MinecraftFrames.write(socket.getOutputStream(), packet); }
    void awaitPlay() throws InterruptedException {
      require(waitFor(() -> state.equals("PLAY"), 15_000), "the client never reached Play; it is in " + state + ", asked " + requests);
    }
    List<String> requests() { synchronized (requests) { return List.copyOf(requests); } }
    @Override public void close() throws IOException { socket.close(); }
  }

  private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
  private static String text(byte[] bytes) { return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8); }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
