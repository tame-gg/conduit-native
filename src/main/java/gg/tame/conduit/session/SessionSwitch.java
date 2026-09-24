// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.IdentityTranslator;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.metrics.ConduitMetrics;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * The server-switch machinery of a {@link PlayerSession}: opening and logging in to the target
 * while the old backend keeps flowing, committing under the session's own {@code lock}, and
 * walking the client through the reconfiguration. Split out so the session is safer to change;
 * every lock taken here is the session's, and no ordering or thread handoff moved with it.
 */
final class SessionSwitch {
  private final PlayerSession session;
  final java.util.concurrent.atomic.AtomicBoolean switchInFlight = new java.util.concurrent.atomic.AtomicBoolean();
  volatile BackendConnection switchingTarget;
  SessionSwitch(PlayerSession session) { this.session = session; }

  /**
   * One backend's share of the session: which protocol it speaks, the packet table for it, and the
   * translator that reaches it.
   *
   * <p>These four travel together because they describe one connection, not the session. A switch
   * has two backends alive at once — the old one still carrying the player while the new one logs
   * in — and each needs its own. Holding them as session fields instead meant the moment a switch
   * started preparing, every packet the client sent was encoded for a backend it was not being
   * written to: a real 1.20.4 client's Set Player Position reached the still-live 1.13 backend as
   * raw 1.20.4, and that backend closed the connection.
   */
  record Translation(TranslationSupport support, int backendProtocol,
                     ProtocolDefinition definition, ProtocolTranslator translator) {
    void close() {
      if (translator instanceof AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { }
      }
    }
  }

  /** Works out the translation for {@code server} without disturbing the one already in use. */
  Translation buildTranslation(BackendServer server) {
    int advertised = session.selector.resolveProtocol(server.name()).orElse(session.clientProtocol);
    TranslationSupport support = ProtocolCompatibility.between(session.clientProtocol, advertised);
    if (support == TranslationSupport.TRANSLATED) {
      ProtocolDefinition definition = ProtocolDefinition.hasCodec(advertised)
          ? ProtocolDefinition.forVersion(advertised)
          : session.protocol;
      ProtocolTranslator engine = Translators.forPair(session.clientProtocol, advertised,
          server.address().getHostString(), server.address().getPort());
      String name = engine instanceof gg.tame.conduit.viaversion.ConduitViaTranslator ? "ViaVersion" : "native";
      ProtocolTrace.note("session translation " + session.clientProtocol + "→" + advertised + " TRANSLATED (" + name + ")");
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          session.clientProtocol, advertised, "TRANSLATED", name).render().replace("\n", " | "));
      return new Translation(support, advertised, definition, engine);
    }
    if (support == TranslationSupport.DIRECT) {
      ProtocolTrace.note("session translation " + session.clientProtocol + "→" + session.clientProtocol + " DIRECT");
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          session.clientProtocol, session.clientProtocol, "DIRECT", "identity").render().replace("\n", " | "));
      return new Translation(TranslationSupport.DIRECT, session.clientProtocol, session.protocol, IdentityTranslator.INSTANCE);
    }
    // UNSUPPORTED Via-style backends that accept the client wire protocol.
    System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
        session.clientProtocol, advertised, "UNSUPPORTED", "none").render().replace("\n", " | "));
    return new Translation(TranslationSupport.DIRECT, session.clientProtocol, session.protocol, IdentityTranslator.INSTANCE);
  }

  /** Makes {@code next} the session's translation and closes whatever it replaces. */
  void install(Translation next) {
    Translation previous = new Translation(session.translationSupport, session.backendProtocol, session.backendDefinition, session.translator);
    session.translationSupport = next.support();
    session.backendProtocol = next.backendProtocol();
    session.backendDefinition = next.definition();
    session.translator = next.translator();
    // Under the lock every translation holds, so a packet mid-translation on a relay thread is not
    // pulled out from under by the engine being closed.
    if (previous.translator() != next.translator()) {
      synchronized (session.translatorLock) { previous.close(); }
    }
  }

  void prepareTranslation(BackendServer server) {
    install(buildTranslation(server));
  }

  /**
   * One client-requested switch at a time.
   *
   * <p>The session only becomes SWITCHING at the commit, and everything before it — opening the
   * target, logging in to it, building its translator — runs while the session is still CONNECTED.
   * So a client sending {@code /server} as fast as it can type had every one of those requests pass
   * the busy check and open a backend socket of its own, holding one connection at the target per
   * packet until each found out at the commit that another had won. Two that did reach the commit
   * were worse: the second installed its backend over the first's without closing it.
   *
   * <p>The fallback path takes no part in this. It is entered from the backend reader with the
   * session already SWITCHING and is the only thing that can save a player whose server just died.
   */
  gg.tame.conduit.api.player.ConnectResult runSwitch(BackendServer server) {
    if (!switchInFlight.compareAndSet(false, true)) {
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.IN_PROGRESS, "another switch is running");
    }
    try {
      switchTo(server, false);
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.CONNECTED, "");
    } catch (SwitchCancelled cancelled) {
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.CANCELLED, cancelled.getMessage());
    } catch (RefusedSwitch refused) {
      ConduitMetrics.current().failedSwitch();
      // The player is still on the server they were switching from.
      switch (refused.refusal.result()) {
        case KickResult.Notify notify -> session.sendMessage(notify.message());
        case KickResult.Disconnect disconnect -> session.disconnect(disconnect.reason());
        case KickResult.Redirect redirect -> {
          try {
            switchTo(session.selector.registry().get(redirect.server().getName())
                .orElseThrow(() -> new IOException("unknown server " + redirect.server().getName())), false);
            redirect.message().ifPresent(session::sendMessage);
          } catch (Exception failed) {
            session.sendMessage(refused.refusal.reason());
          }
        }
      }
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.FAILED, refused.getMessage());
    } catch (Exception exception) {
      ConduitMetrics.current().failedSwitch();
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.FAILED,
          exception.getMessage() == null ? "switch failed" : exception.getMessage());
    } finally {
      switchInFlight.set(false);
    }
  }
  /** A PlayerServerConnectEvent listener said no; nothing was opened. */
  static final class SwitchCancelled extends IOException {
    private SwitchCancelled(String server) { super("connection to " + server + " was cancelled"); }
  }
  /** The target refused the player's login; the kicked event has decided what happens next. */
  static final class RefusedSwitch extends IOException {
    final PlayerSession.Refusal refusal;
    RefusedSwitch(PlayerSession.Refusal refusal, IOException cause) { super(cause.getMessage(), cause); this.refusal = refusal; }
  }
  static final int SWITCH_BUDGET_MS = 4_000;
  /**
   * Budget for each half of a translator-driven reconfiguration, measured after the switch has
   * already committed. It is separate from {@link #SWITCH_BUDGET_MS}, which covers connecting and
   * logging in to the new backend: by this point that work is done, and what is being waited on is
   * a real client loading a world it has just been handed.
   */
  private static final int RECONFIGURE_BUDGET_MS = 15_000;

  void switchTo(BackendServer requested, boolean fallback) throws Exception {
    BackendServer server = requested;
    var targetView = session.runtime.registered(server.name()).orElse(null);
    var sourceView = session.backend == null ? java.util.Optional.<gg.tame.conduit.api.server.RegisteredServer>empty() : session.runtime.registered(session.backend.server().name());
    if (targetView != null) {
      var connect = new gg.tame.conduit.api.event.player.PlayerServerConnectEvent(session, sourceView, targetView);
      session.runtime.events().fire(connect);
      if (connect.cancelled()) throw new SwitchCancelled(server.name());
      if (connect.target() != targetView) {
        String redirect = connect.target().getName();
        server = session.selector.registry().get(redirect).orElseThrow(() -> new IOException("redirected to unknown server " + redirect));
        targetView = session.runtime.registered(server.name()).orElse(connect.target());
      }
    }
    long started = System.nanoTime();
    long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SWITCH_BUDGET_MS);
    // After the listeners, which may send the player somewhere that is up; with checks off or no
    // verdict yet the switch is tried as always.
    if (session.selector.knownDown(server.name())) throw new IOException(server.name() + " is unavailable");
    // A full server takes nobody else from the proxy, whichever path asked: a command, a plugin, a
    // kick fallback. The next candidate is tried where there is one.
    if (server.full(session.players.byServer(server.name()).size())) throw new IOException(server.name() + " is full");
    session.ensureCompatible(server);
    synchronized (session.lock) {
      // closed is set the moment the client's connection ends, before the lifecycle says so; a plugin
      // moving a player from their disconnect event had a backend dialled and logged in to for nobody.
      if (session.closed || session.lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
      if (!fallback && session.lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
      // Stay CONNECTED during prepare so the current backend keeps flowing packets.
    }
    BackendConnection previous = session.backend;
    Socket socket = null;
    BackendConnection next = null;
    Translation pending = null;
    boolean clientEnteredConfiguration = false;
    boolean finishAfterCommit = false;
    try {
      // PREPARE: open + login against the target while the current backend remains active. The
      // target's translation is built but NOT installed: until commit, the session's translator
      // still belongs to the backend the player is actually on, and the client's packets have to
      // keep being encoded for that one.
      pending = buildTranslation(server);
      socket = session.selector.open(server);
      enforceDeadline(deadline, "connect");
      Handshake switchHandshake = pending.support() == TranslationSupport.TRANSLATED
          ? new Handshake(pending.backendProtocol(), session.handshake.requestedHost(), session.handshake.requestedPort(), 2)
          : session.handshake;
      BackendConnection.handshake(socket, switchHandshake, server, session.profile(), session.modClassifier.marker(), session.modClassifier.family());
      next = session.track(new BackendConnection(server, socket, pending.definition(), session.forwarder, session.profile(), session.address, session.configuration, true));
      next.setReadTimeoutMillis(remainingMillis(deadline));
      session.completeBackendLogin(next, false, pending);
      enforceDeadline(deadline, "login");
      session.replayClientInformation(next, ConnectionState.CONFIGURATION, pending);

      // COMMIT: only now pause the old backend reader and involve the client.
      synchronized (session.lock) {
        if (session.lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
        if (!fallback && session.lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
        session.lifecycle.set(SessionLifecycle.SWITCHING);
        session.switchQueue.clear();
        // The old backend stops receiving client packets here, so this is the first moment the
        // session's translation can move to the new one without misaddressing anything.
        install(pending);
        session.lock.notifyAll();
      }
      gg.tame.conduit.protocol.ProfileTrace.beginSequence("switch to " + server.name(), 60);
      switchingTarget = next;
      if (!session.protocol.hasConfiguration() && session.backendDefinition.hasConfiguration()) {
        // 393-style client: Configuration already absorbed during completeBackendLogin.
        synchronized (session.lock) { session.awaitDeferredFlush(); session.deferredPlay.clear(); session.playLoginSent = false; session.needSelfPlayerInfo = true; }
      } else if (session.protocol.hasConfiguration()) {
        session.configurationAck.clear();
        synchronized (session.lock) { session.awaitDeferredFlush(); session.deferredPlay.clear(); session.playLoginSent = false; session.needSelfPlayerInfo = true; }
        // Who reconfigures the client depends on who can. A backend with its own Configuration
        // phase supplies the packets and Conduit relays them. A backend without one supplies
        // nothing, and on the Via engine the translator builds that phase itself out of the
        // backend's Join Game — including the Start Configuration that begins it. Conduit sending
        // its own as well puts the client into a phase it is already entering, and the second
        // handshake never completes.
        finishAfterCommit = session.viaEngine() && !session.backendDefinition.hasConfiguration();
        if (finishAfterCommit) {
          clientEnteredConfiguration = true;
          // The translator reached Configuration on the client's behalf during prepare, out of the
          // login exchange replayed into it there. What that exchange cannot tell it is that the
          // real client is in Play: the connection it was shown looks like a first join, so it
          // never produces the Start Configuration a player who is already in a world needs.
          // Conduit sends that one itself, and therefore owns the acknowledgement, which is
          // answered here rather than passed on to a translator already in Configuration.
          //
          // The wait is what keeps the order right. The backend's Join Game is the packet the
          // translator turns into the whole Configuration phase, and it is read on the steady path
          // that the commit below starts. Committing first races that phase against a client still
          // in Play, which is the same disconnect by a different route.
          session.conduitOwnsReconfiguration = true;
          ProtocolTrace.note("switch reconfiguring client; via state " + session.viaStateDescription());
          session.writeClient(PlayPackets.startConfiguration(session.protocol));
          if (session.configurationAck.poll(RECONFIGURE_BUDGET_MS, TimeUnit.MILLISECONDS) == null) {
            throw new IOException("client did not acknowledge reconfiguration");
          }
          session.configurationAck.clear();
        } else {
          // Conduit asks for this reconfiguration, so the acknowledgement is Conduit's. The new
          // backend is already in Configuration and has no Play packet to receive it as.
          session.conduitOwnsReconfiguration = true;
          deadline += session.enteringConfiguration(next);
          session.writeClient(PlayPackets.startConfiguration(session.protocol));
          clientEnteredConfiguration = true;
          int waitSeconds = Math.max(1, remainingMillis(deadline) / 1000);
          if (session.configurationAck.poll(waitSeconds, TimeUnit.SECONDS) == null) throw new IOException("client did not acknowledge reconfiguration");
          session.conduitOwnsReconfiguration = false;
          session.configurationAck.clear();
          session.knownPacksAck.clear();
          session.enteredConfiguration(next);
          deadline = relayConfigurationUntilClientFinishes(next, deadline);
        }
      }
      session.commandsDeclared = false;
      session.lastBackendCommands = null;
      // A client with a Configuration phase is held in it until the phase finishes, so its Play
      // packets cannot race the new backend's Join Game. One without a phase has nothing holding
      // it, and this is what holds it instead -- whatever carries the pair, not only Via. The new
      // backend has no Configuration phase either, and it only starts decoding Play when it sends
      // Join Game: a real 1.8.9 client switched DIRECT between two 1.8.9 servers had the replayed
      // Client Settings land in that gap, and both servers dropped it with "Bad packet id 21".
      if (!session.protocol.hasConfiguration()) session.awaitingBackendJoinGame.hold();
      else session.awaitingBackendJoinGame.release();
      // Every read of the new backend so far was bounded by the switch budget. From here it is the
      // session's backend, which may be as quiet as it likes: left in place, that deadline read four
      // silent seconds on a limbo server as a lost backend and sent the player to the fallback.
      next.setReadTimeoutMillis(0);
      // Prepare the watch first, but dispatch only after publication: reading earlier drops the
      // new backend's first packets as belonging to a backend that is not current yet.
      //
      // Under the lock, all of it. Whether this backend is watched is decided by reading {@code
      // watched}, which the login sets when it starts watching, and a switch that read it outside
      // the lock could be told "not watched" by a login that was watching a moment later: the login
      // then watched the backend this switch is replacing, this switch watched nothing, and the
      // player sat on a backend nobody was reading. One lock, one decision.
      synchronized (session.lock) {
        if (session.closed) throw new IOException("session closed");
        var nextWatch = session.watched ? session.watchBackend(next) : null;
        if (nextWatch != null) session.pair(session.clientWatch, nextWatch);
        session.backend = next;
        switchingTarget = null;
        next = null;
        session.lifecycle.set(SessionLifecycle.CONNECTED);
        if (nextWatch != null) nextWatch.start();
        session.lock.notifyAll();
      }
      // An FML1 client does not run its handshake twice. Forge's HandshakeReset puts it back to
      // the start so the new server's FML|HS exchange can happen at all.
      var reset = gg.tame.conduit.modded.FmlHandshakeReset.forSwitch(session.protocol, session.modClassifier.marker());
      if (reset.isPresent()) session.writeClient(reset.get());
      session.flushSwitchQueue(session.backend);
      if (finishAfterCommit) {
        // The client is already in Configuration; the phase itself is built by the translator out
        // of the backend's Join Game, which only reaches it once the steady read path the commit
        // above started delivers it. All that is left to wait for is the client finishing.
        try {
          if (session.configurationAck.poll(RECONFIGURE_BUDGET_MS, TimeUnit.MILLISECONDS) == null) {
            throw new IOException("client did not finish configuration");
          }
        } finally {
          session.conduitOwnsReconfiguration = false;
        }
      }
      // Not replayed yet when the translator is still waiting for the new backend's Join Game; the
      // read loop does it as soon as that arrives.
      if (!session.awaitingBackendJoinGame.holding()) session.replayClientInformation(session.backend, ConnectionState.PLAY);
      session.discard(previous);
      // Held back with the Client Information replay while the new backend's Join Game is awaited.
      // It carries the channels the client registered, too.
      if (!session.awaitingBackendJoinGame.holding()) session.announceProxyChannels();
      gg.tame.conduit.metrics.ConduitMetrics.current().serverSwitch(System.nanoTime() - started);
      if (targetView != null) {
        session.loggedBackend = targetView.getName();
        gg.tame.conduit.log.ConduitLog.info(sourceView.isPresent()
            ? session.origin() + " left backend '" + sourceView.get().getName()
                + "' and joined backend '" + targetView.getName() + "'"
            : session.origin() + " joined backend '" + targetView.getName() + "'");
        session.runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerConnectedEvent(session, sourceView, targetView));
        session.runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchEvent(session, sourceView, targetView));
      }
    } catch (Exception exception) {
      session.awaitingBackendJoinGame.release();
      session.conduitOwnsReconfiguration = false;
      switchingTarget = null;
      session.switchQueue.clear();
      // Only release the prepared translation if it never became the session's.
      if (pending != null && pending.translator() != session.translator) pending.close();
      session.discard(next);
      if (next == null && socket != null) try { socket.close(); } catch (IOException ignored) { }
      gg.tame.conduit.log.ConduitLog.warn("Switch to " + server.name() + " failed: " + exception.getMessage());
      if (targetView != null) {
        session.runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent(session, sourceView, targetView,
            exception.getMessage() == null ? "switch failed" : exception.getMessage()));
      }
      if (clientEnteredConfiguration) {
        // Client left PLAY; cannot safely restore the old Play session.
        if (exception instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused) {
          session.refusedInConfiguration(refused, server);
        } else {
          try { session.writeClient(PlayPackets.configurationDisconnect(session.protocol, "Could not connect to " + server.name() + ".")); }
          catch (IOException ignored) { }
          session.close();
        }
        throw exception;
      }
      // A fallback's caller set SWITCHING itself, over a backend that is already gone, and either
      // tries the next server or closes. Handing the session back as CONNECTED here pointed it at that
      // dead backend between attempts, where a /server from the client could start a second switch.
      if (!fallback) synchronized (session.lock) {
        if (session.lifecycle.get() == SessionLifecycle.SWITCHING) {
          session.lifecycle.set(previous != null ? SessionLifecycle.CONNECTED : SessionLifecycle.CLOSED);
          session.lock.notifyAll();
        }
      }
      // Fired once the player is back where they were, so a listener sees them there. A refusal can
      // only come from the login, before the commit, so the client never left Play for it.
      if (exception instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused && targetView != null) {
        throw new RefusedSwitch(session.refused(refused, targetView), refused);
      }
      throw exception;
    }
  }
  /**
   * The Configuration phase of a switch the two ends both have: pumps the new backend's packets to
   * the client until the backend leaves Configuration and the client has acknowledged Finish
   * Configuration. Returns the deadline, pushed out by the time plugins held the phase for.
   */
  private long relayConfigurationUntilClientFinishes(BackendConnection next, long deadline) throws IOException, InterruptedException {
    // Both checks watch what this loop relays, and on the Via engine that is not everything the
    // client gets: from a 1.20.4 backend's Registry Data Via writes Known Packs and every
    // registry itself, ahead of the result, answers the client's reply itself, and hands this
    // loop nothing. Enforced there, they failed a switch whose client had received both.
    boolean registrySeen = !session.protocol.knownPacks() || session.viaEngine();
    boolean knownPacksDone = !session.protocol.knownPacks() || session.viaEngine();
    byte[] lastConfig = null;
    while (next.state() != ConnectionState.PLAY) {
      enforceDeadline(deadline, "configuration");
      next.setReadTimeoutMillis(remainingMillis(deadline));
      byte[] packet = next.readUncompressed();
      int id = PlayPackets.packetId(packet);
      next.login().onBackendPacket(packet, session.configuration.maxFrameBytes());
      byte[] translated = session.towardClient(ConnectionState.CONFIGURATION, packet);
      if (translated == null) {
        // A cancelled packet can still have an answer: Via takes a 1.21.8 backend's Known Packs
        // away from a 1.20.4 client and replies to the backend itself, which waits for it.
        session.flushTranslatorExtras(next);
        continue;
      }
      var brand = BrandRewriter.rewrite(session.protocol, ConnectionState.CONFIGURATION, translated, session.configuration.maxFrameBytes());
      byte[] outbound;
      if (brand.isPresent()) {
        next.markBrandSeen();
        outbound = brand.get();
      } else {
        outbound = translated;
      }
      if (session.protocol.knownPacks() && session.protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(translated), PacketKind.CONFIGURATION_KNOWN_PACKS)) {
        session.writeClient(outbound);
        if (session.knownPacksAck.poll(Math.max(1, remainingMillis(deadline) / 1000), TimeUnit.SECONDS) == null) {
          throw new IOException("client did not reply to known packs");
        }
        knownPacksDone = true;
        continue;
      }
      if (session.protocol.knownPacks() && session.protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(translated), PacketKind.CONFIGURATION_REGISTRY)) {
        registrySeen = true;
      }
      if (session.isFinishConfiguration(translated)) {
        if (translated.length > 2) continue;
        if (session.protocol.knownPacks() && !knownPacksDone) {
          throw new IOException("backend finished configuration before known packs");
        }
        if (!registrySeen) throw new IOException("backend finished configuration without 26.2 registry data");
        // Plugins' time is theirs, not the switch's.
        deadline += session.finishingConfiguration();
        if (!next.brandSeen()) {
          session.writeClient(BrandRewriter.synthesize(session.protocol, ConnectionState.CONFIGURATION, ""));
          next.markBrandSeen();
        }
        session.writeClient(outbound);
        break;
      }
      if (lastConfig != null && java.util.Arrays.equals(lastConfig, outbound)) continue;
      lastConfig = outbound;
      session.writeClient(outbound);
    }
    int finishWait = Math.max(1, remainingMillis(deadline) / 1000);
    // waitForClient reads the backend while it waits and relays what it reads untranslated. On
    // the Via engine that took a 1.20.4 backend's Join Game past the translator, and the client
    // disconnected on the Change Difficulty behind it; the read path after commit translates it.
    if (session.protocol.knownPacks() && !session.viaEngine()) {
      if (!waitForClient(session.configurationAck, next, finishWait)) throw new IOException("client did not finish configuration");
    } else if (session.configurationAck.poll(finishWait, TimeUnit.SECONDS) == null) {
      throw new IOException("client did not finish configuration");
    }
    return deadline;
  }
  private static void enforceDeadline(long deadlineNanos, String stage) throws IOException {
    if (System.nanoTime() > deadlineNanos) throw new IOException("switch timed out during " + stage);
  }
  private static int remainingMillis(long deadlineNanos) {
    long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    return (int) Math.max(1, Math.min(SWITCH_BUDGET_MS, remaining));
  }
  private boolean waitForClient(java.util.concurrent.BlockingQueue<Boolean> queue, BackendConnection backend, int seconds) throws IOException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    try {
      while (System.nanoTime() < deadline) {
        if (queue.poll() != null) return true;
        // Only between frames is the backend waited on in short slices: a timed read that gave up
        // inside a frame had already taken part of it, and the next one began mid-frame. Once a
        // byte is pending the whole frame is read under the switch's own budget.
        if (backend.available() == 0) {
          try {
            if (queue.poll(WAIT_FOR_CLIENT_POLL_MS, TimeUnit.MILLISECONDS) != null) return true;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
          }
          continue;
        }
        backend.setReadTimeoutMillis(remainingMillis(deadline));
        holdOrForwardDuringClientWait(backend.readUncompressed(), backend);
      }
      return queue.poll() != null;
    } finally {
      backend.setReadTimeoutMillis(0);
    }
  }
  private static final int WAIT_FOR_CLIENT_POLL_MS = 20;
  private void holdOrForwardDuringClientWait(byte[] packet, BackendConnection backend) throws IOException {
    int id = PlayPackets.packetId(packet);
    if (backend.state() == ConnectionState.CONFIGURATION
        && session.protocol.defines(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KEEP_ALIVE)
        && session.protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_KEEP_ALIVE)) {
      session.writeClient(packet);
      return;
    }
    ConnectionState backendState = backend.state();
    byte[] outbound = backend.rewriteBrand(session.brandState(backendState), packet);
    if (session.deferPlayUntilReady(packet, outbound, backendState)) {
      session.flushDeferredPlay();
      return;
    }
    switch (PlayerSession.handoff(backendState, session.clientState.state())) {
      case FORWARD_CONFIGURATION -> session.writeClient(outbound);
      case FORWARD_PLAY -> session.writeClient(session.maybeMergeCommands(outbound));
      case DROP -> System.err.println("Dropped backend " + backendState + " packet while client was in " + session.clientState.state());
    }
  }
}
