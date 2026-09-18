// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.api.event.player.PlayerResourcePackStatusEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.ResourcePack;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ResourcePackPackets;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The resource packs one client is offered through the proxy: the proxy's own, which it writes in the
 * client's protocol past any translator, and the ones its server offers, which pass untouched and are
 * only followed. The client's answers about the proxy's packs end here; its answers about a server's
 * packs go on to that server, which is waiting for them and never hears about the proxy's.
 *
 * <p>A 1.20.3+ client names the pack each answer is about. An older one does not, and answers one
 * offer after another, so every offer it is written, whoever made it, joins a queue in order and its
 * answers are matched to the earliest one it has not finished with (1.8's answers also carry the
 * hash, which is tried first).
 *
 * <p>The proxy's offers wait for the client to stand in a world, on the same gate as the proxy's
 * display ({@link ClientDisplay#takesWorld}): after Start Configuration a 1.20.2+ client reads Play ids
 * as Configuration ones, and a pack offer written then would be something else to it. They are held
 * and written when the Join Game ending the switch or the login arrives, except while the session
 * holds the client in Configuration for plugins ({@link #configuring}), where they go as that phase's
 * own packets. The proxy's packs are the
 * proxy's, not a server's, so they stay across server switches; a server's stay as the client keeps
 * them, and the proxy neither removes nor re-sends those.
 */
public final class ClientResourcePacks {
  /**
   * The most of a server's offers followed, and of offers waiting for an answer before 1.20.3. A server
   * offering pack after pack to a client that never answered grew both without end; past this its
   * offers still reach the client, and their answers its server, unseen by plugins.
   */
  private static final int MAX_FOLLOWED = 64;
  private final Player player;
  private final ProtocolDefinition protocol;
  private final ClientDisplay.Output output;
  private final Consumer<PlayerResourcePackStatusEvent> statuses;
  private final boolean named;
  private final Object lock = new Object();
  // Everything below is guarded by lock. The proxy writes only while holding it, which is how a write
  // passing beforeWrite is known to be the proxy's own rather than a server's.
  private boolean inWorld;
  /** The session holds the client in its Configuration phase for plugins: offers go now, as Configuration packets. */
  private boolean configuring;
  private boolean closed;
  private final Map<UUID, Offer> offers = new LinkedHashMap<>();
  /** Before 1.20.3: what the client was offered, in order, that it has not finished answering. */
  private final Deque<UUID> unanswered = new ArrayDeque<>();
  /** Removals made while the client stood in no world; an empty one removes every pack. */
  private final List<Optional<UUID>> removedWhileAway = new ArrayList<>();

  private static final class Offer {
    final ResourcePack pack;
    final boolean fromServer;
    boolean written;
    boolean loaded;
    Offer(ResourcePack pack, boolean fromServer, boolean written) {
      this.pack = pack; this.fromServer = fromServer; this.written = written;
    }
  }

  public ClientResourcePacks(Player player, ProtocolDefinition protocol, ClientDisplay.Output output,
                             Consumer<PlayerResourcePackStatusEvent> statuses) {
    this.player = player;
    this.protocol = protocol;
    this.output = output;
    this.statuses = statuses;
    this.named = ResourcePackPackets.named(protocol);
  }

  // ---- what plugins ask for -----------------------------------------------------------------

  public boolean offer(ResourcePack pack) {
    synchronized (lock) {
      if (closed || !ResourcePackPackets.supported(protocol)) return false;
      Offer previous = offers.remove(pack.id());
      Offer offer = new Offer(pack, false, false);
      // The same pack offered again (plugins re-offer a pack until they see it applied): the client
      // keeps it loaded while it answers once more.
      offer.loaded = previous != null && previous.loaded && previous.pack.url().equals(pack.url())
          && previous.pack.hash().equalsIgnoreCase(pack.hash());
      offers.put(pack.id(), offer);
      if (inWorld) write(offer, ConnectionState.PLAY);
      else if (configuring) write(offer, ConnectionState.CONFIGURATION);
      return true;
    }
  }

  public boolean remove(UUID id) {
    synchronized (lock) {
      if (closed || !named) return false;
      Offer offer = offers.remove(id);
      if (offer != null && !offer.written) return true;
      if (inWorld) send(id, ConnectionState.PLAY);
      else if (configuring) send(id, ConnectionState.CONFIGURATION);
      else removedWhileAway.add(Optional.of(id));
      return true;
    }
  }

  public boolean clear() {
    synchronized (lock) {
      if (closed || !named) return false;
      offers.clear();
      if (inWorld) {
        send(null, ConnectionState.PLAY);
      } else if (configuring) {
        send(null, ConnectionState.CONFIGURATION);
      } else {
        removedWhileAway.clear();
        removedWhileAway.add(Optional.empty());
      }
      return true;
    }
  }

  public List<ResourcePack.Offered> offered() {
    synchronized (lock) {
      List<ResourcePack.Offered> result = new ArrayList<>();
      for (Offer offer : offers.values()) result.add(new ResourcePack.Offered(offer.pack, offer.fromServer, offer.loaded));
      return List.copyOf(result);
    }
  }

  // ---- every clientbound packet passes these ------------------------------------------------

  /** Before {@code packet} is written in {@code state}: follows a server's offer, and closes the gate if the world goes. */
  public void beforeWrite(ConnectionState state, byte[] packet) {
    if (!Thread.holdsLock(lock)) {
      Optional<ResourcePackPackets.Clientbound> relayed = ResourcePackPackets.read(protocol, state, packet);
      if (relayed.isPresent()) synchronized (lock) { follow(relayed.get()); }
    }
    if (ClientDisplay.takesWorld(protocol, state, packet)) synchronized (lock) { inWorld = false; }
  }

  /** After {@code packet} was written in {@code state}: reopens the gate and writes what waited for it. */
  public void afterWrite(ConnectionState state, byte[] packet) {
    if (state != ConnectionState.PLAY || !ClientDisplay.rebuildsWorld(protocol, packet)) return;
    synchronized (lock) {
      if (closed) return;
      inWorld = true;
      for (Optional<UUID> removed : removedWhileAway) send(removed.orElse(null), ConnectionState.PLAY);
      removedWhileAway.clear();
      for (Offer offer : List.copyOf(offers.values())) if (!offer.fromServer && !offer.written) write(offer, ConnectionState.PLAY);
    }
  }

  /**
   * Opens or closes the window in which the session holds the client in Configuration for plugins,
   * from the moment it is in that phase until just before it is told to finish: what is offered or
   * removed meanwhile goes at once, as Configuration packets. What was waiting from before keeps
   * waiting for the world.
   */
  public void configuring(boolean open) {
    synchronized (lock) { configuring = open; }
  }

  /** Called holding lock: a server's offer or removal on its way to the client. */
  private void follow(ResourcePackPackets.Clientbound relayed) {
    switch (relayed) {
      case ResourcePackPackets.Offer offer -> {
        offers.remove(offer.id());
        if (offers.size() < MAX_FOLLOWED) offer.pack().ifPresent(pack -> offers.put(offer.id(), new Offer(pack, true, true)));
        if (!offer.named()) awaitAnswer(offer.id());
      }
      case ResourcePackPackets.Removal removal -> {
        if (removal.id().isPresent()) offers.remove(removal.id().get());
        else offers.values().removeIf(offer -> offer.written);
      }
    }
  }

  // ---- the client's answers ------------------------------------------------------------------

  /**
   * The client sent {@code packet} in {@code state}. True when it was the client's answer about one
   * of the proxy's packs, which has then been dealt with here and must not reach the server.
   */
  public boolean fromClient(ConnectionState state, byte[] packet) {
    Optional<ResourcePackPackets.Answer> read = ResourcePackPackets.answer(protocol, state, packet);
    if (read.isEmpty()) return false;
    ResourcePackPackets.Answer answer = read.get();
    PlayerResourcePackStatusEvent event = null;
    boolean ours;
    synchronized (lock) {
      UUID id = answer.id().orElseGet(() -> legacyTarget(answer.hash()));
      Offer offer = id == null ? null : offers.get(id);
      if (!named && id != null && answer.status().map(status -> !status.intermediate()).orElse(false)) unanswered.remove(id);
      if (offer == null) return false;
      ours = !offer.fromServer;
      if (answer.status().isPresent()) {
        ResourcePack.Status status = answer.status().get();
        if (status == ResourcePack.Status.LOADED) {
          offer.loaded = true;
          // Before 1.20.3 a client holds one server pack: the one it just loaded is the only one left.
          if (!named) offers.values().removeIf(other -> other != offer && other.loaded);
        } else if (!status.intermediate()) {
          offers.remove(id);
        }
        event = new PlayerResourcePackStatusEvent(player, offer.pack, offer.fromServer, status);
      }
    }
    if (event != null) statuses.accept(event);
    return ours;
  }

  /** Called holding lock: the offer an unnamed answer is about. */
  private UUID legacyTarget(Optional<String> hash) {
    if (hash.isPresent() && !hash.get().isEmpty()) {
      for (UUID id : unanswered) {
        Offer offer = offers.get(id);
        if (offer != null && offer.pack.hash().equalsIgnoreCase(hash.get())) return id;
      }
    }
    return unanswered.peekFirst();
  }

  // ---- the end ------------------------------------------------------------------------------

  public void close() {
    synchronized (lock) {
      closed = true;
      offers.clear();
      unanswered.clear();
      removedWhileAway.clear();
    }
  }

  /** Called holding lock. */
  private void write(Offer offer, ConnectionState state) {
    try {
      Optional<byte[]> packet = ResourcePackPackets.offer(protocol, state, offer.pack);
      if (packet.isEmpty()) return;
      output.write(packet.get());
      offer.written = true;
      if (!named) awaitAnswer(offer.pack.id());
    } catch (IOException gone) {
      // The client is going away; the session notices that on its own.
    }
  }

  /** Called holding lock. The oldest goes first: a client this far behind has left it unanswered for good. */
  private void awaitAnswer(UUID id) {
    if (unanswered.size() == MAX_FOLLOWED) unanswered.removeFirst();
    unanswered.addLast(id);
  }

  /** Called holding lock: drops the pack {@code id}, or every pack when null. */
  private void send(UUID id, ConnectionState state) {
    try {
      Optional<byte[]> packet = ResourcePackPackets.remove(protocol, state, id);
      if (packet.isPresent()) output.write(packet.get());
    } catch (IOException gone) {
      // As in write.
    }
  }
}
