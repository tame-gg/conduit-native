// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.messaging;

import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.session.TrackedPlayer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The channel a backend plugin talks to its proxy on.
 *
 * <p>A plugin running on a Paper or Spigot backend has no API to reach the proxy with. What it has
 * instead is a plugin message on the channel {@code BungeeCord}, renamed {@code bungeecord:main}
 * when 1.13 made channel names namespaced, carrying a subchannel name and its arguments. Every hub
 * plugin's {@code /hub}, and every queue plugin that runs on a backend rather than on the proxy, is
 * that message and nothing else: they do not know what proxy they are talking to, only that this
 * channel answers. A proxy that ignores it is a proxy where those plugins silently do nothing, with
 * no error anywhere, because the message was delivered and simply never acted on.
 *
 * <p>This is a clean-room implementation of that protocol, written from its public documentation and
 * from what the messages themselves have to contain for a backend plugin to be able to read them. No
 * BungeeCord, Waterfall or Velocity code is used.
 *
 * <p>The payload is what {@code DataOutputStream} writes: a UTF-8 length-prefixed subchannel name,
 * then the arguments that subchannel takes. A reply goes back on the same channel, to the backend
 * that asked, and begins with the subchannel name so the plugin can tell its answers apart. A reply
 * is only ever sent to the server that asked for it -- a backend must not be told about a request it
 * did not make.
 *
 * <p>Two rules make this safe to have on by default. A message on this channel is consumed and never
 * reaches the client, since it is addressed to the proxy and a client has no business seeing who is
 * online elsewhere. And {@code Forward} is the one subchannel that moves data between backends, so
 * what it carries is passed through untouched and never interpreted.
 */
public final class BungeeCordMessages {
  /** What the channel is called on 1.13 and later, and before it. */
  public static final String MODERN_CHANNEL = "bungeecord:main";
  public static final String LEGACY_CHANNEL = "BungeeCord";
  /** Every server, for the subchannels that take a server name. */
  private static final String ALL = "ALL";
  /** Every server that has someone on it, which {@code Forward} treats differently from ALL. */
  private static final String ONLINE = "ONLINE";

  private final ConduitRuntime runtime;
  /**
   * Whether anything has ever come in on this channel. The first one is worth a line at INFO: the
   * failure this channel is prone to is invisible -- a plugin sends, nothing happens, nothing is
   * logged at either end -- and an operator wondering why their hub button does nothing needs to be
   * able to tell "the message never arrived" from "it arrived and I got it wrong". After the first,
   * they are counted and left at debug.
   */
  private final java.util.concurrent.atomic.AtomicLong handled = new java.util.concurrent.atomic.AtomicLong();

  /** The subchannels that act on players other than the one whose connection carried the message. */
  private static final java.util.Set<String> REACHES_OTHERS =
      java.util.Set.of("ConnectOther", "IPOther", "KickPlayer", "KickPlayerRaw", "UUIDOther");

  public BungeeCordMessages(ConduitRuntime runtime) { this.runtime = runtime; }

  /** How many messages have been answered on this channel, for {@code /conduit doctor}. */
  public long handledCount() { return handled.get(); }

  /** Whether this is the channel, under either of its two names. */
  public static boolean isChannel(String channel) {
    return MODERN_CHANNEL.equalsIgnoreCase(channel) || LEGACY_CHANNEL.equals(channel);
  }

  /**
   * Handles one message from a backend.
   *
   * <p>{@code sender} is the player whose connection carried it, which is how the protocol says
   * which backend is asking and, for the subchannels that take no player, who it is about.
   *
   * <p>Always returns true: the message was for the proxy, so it is consumed whether or not this
   * understood it. A subchannel Conduit does not know is one line at debug rather than a message
   * passed on to a client that cannot use it either.
   */
  public boolean handle(Player sender, String channel, byte[] data) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      String subchannel = in.readUTF();
      if (handled.getAndIncrement() == 0) {
        ConduitLog.info("A backend plugin is using the " + channel + " channel (first message: "
            + subchannel + ", from '" + sender.currentServer().name() + "'). Conduit answers it.");
      } else {
        ConduitLog.debug(channel + " " + subchannel + " from '" + sender.currentServer().name() + "'");
      }
      // A backend can always act on and ask about its own players. Reaching another server's --
      // kicking them, moving them, reading their address, messaging everyone -- is for the servers
      // the operator listed, so one minigame or test server is not a way to the whole network.
      String from = sender.currentServer().name();
      boolean trusted = runtime.configuration().ops().messaging().trusts(from);
      if (!trusted && REACHES_OTHERS.contains(subchannel)) {
        ConduitLog.warn("'" + from + "' sent " + subchannel + " on " + channel + ", which is refused: it is not in"
            + " messaging.trusted-servers. Add it there if that backend should reach players on other servers.");
        return true;
      }
      switch (subchannel) {
        case "Connect" -> connect(sender, in.readUTF());
        case "ConnectOther" -> {
          String who = in.readUTF();
          String where = in.readUTF();
          player(who).ifPresent(target -> connect(target, where));
        }
        case "IP" -> reply(sender, channel, out -> {
          out.writeUTF("IP");
          out.writeUTF(sender.remoteAddress().getHostAddress());
          out.writeInt(sender.remoteSocketAddress().getPort());
        });
        case "IPOther" -> {
          String who = in.readUTF();
          player(who).ifPresent(target -> reply(sender, channel, out -> {
            out.writeUTF("IPOther");
            out.writeUTF(target.username());
            out.writeUTF(target.remoteAddress().getHostAddress());
            out.writeInt(target.remoteSocketAddress().getPort());
          }));
        }
        case "PlayerCount" -> {
          String server = in.readUTF();
          // ALL is the network, and is reported under the name ALL so the plugin can match it up.
          int count = ALL.equalsIgnoreCase(server)
              ? runtime.players().all().size() : runtime.playerManager().byServer(server).size();
          if (!ALL.equalsIgnoreCase(server) && registered(server).isEmpty()) break;
          reply(sender, channel, out -> { out.writeUTF("PlayerCount"); out.writeUTF(server); out.writeInt(count); });
        }
        case "PlayerList" -> {
          String server = in.readUTF();
          if (!ALL.equalsIgnoreCase(server) && registered(server).isEmpty()) break;
          List<String> names = new ArrayList<>();
          if (ALL.equalsIgnoreCase(server)) for (var player : runtime.players().all()) names.add(player.username());
          else for (TrackedPlayer player : runtime.playerManager().byServer(server)) names.add(player.username());
          reply(sender, channel, out -> {
            out.writeUTF("PlayerList");
            out.writeUTF(server);
            out.writeUTF(String.join(", ", names));
          });
        }
        case "GetServers" -> reply(sender, channel, out -> {
          out.writeUTF("GetServers");
          out.writeUTF(String.join(", ", runtime.selector().registry().names()));
        });
        case "GetServer" -> reply(sender, channel, out -> {
          out.writeUTF("GetServer");
          out.writeUTF(sender.currentServer().name());
        });
        case "GetPlayerServer" -> {
          // Where someone else is: what a queue or hub plugin asks before it moves anyone.
          String who = in.readUTF();
          player(who).ifPresent(target -> reply(sender, channel, out -> {
            out.writeUTF("GetPlayerServer");
            out.writeUTF(target.username());
            out.writeUTF(target.currentServer().name());
          }));
        }
        case "UUID" -> reply(sender, channel, out -> {
          out.writeUTF("UUID");
          out.writeUTF(undashed(sender.uniqueId()));
        });
        case "UUIDOther" -> {
          String who = in.readUTF();
          player(who).ifPresent(target -> reply(sender, channel, out -> {
            out.writeUTF("UUIDOther");
            out.writeUTF(target.username());
            out.writeUTF(undashed(target.uniqueId()));
          }));
        }
        case "ServerIP" -> {
          String server = in.readUTF();
          registered(server).ifPresent(backend -> reply(sender, channel, out -> {
            out.writeUTF("ServerIP");
            out.writeUTF(backend.name());
            // The address as configured. getHostString avoids a reverse lookup on every call.
            out.writeUTF(backend.address().getHostString());
            out.writeShort(backend.address().getPort());
          }));
        }
        case "Message", "MessageRaw" -> {
          String who = in.readUTF();
          String body = in.readUTF();
          // Message is the legacy colour-coded form a backend plugin writes; MessageRaw is the JSON
          // a modern one builds. Both end up as the same component on the client.
          Text text = subchannel.equals("MessageRaw")
              ? gg.tame.conduit.text.TextCodec.fromJson(body)
              : gg.tame.conduit.config.StatusSettings.parseMotd(sectionToAmpersand(body));
          if (ALL.equalsIgnoreCase(who)) {
            if (!trusted) {
              ConduitLog.warn("'" + from + "' sent " + subchannel + " to ALL on " + channel + ", which is refused: it"
                  + " is not in messaging.trusted-servers.");
              break;
            }
            for (var player : runtime.players().all()) player.sendMessage(text);
          } else {
            player(who).ifPresent(target -> target.sendMessage(text));
          }
        }
        case "KickPlayer" -> {
          String who = in.readUTF();
          String reason = in.readUTF();
          player(who).filter(BungeeCordMessages::kickable).ifPresent(target -> target.disconnect(reason));
        }
        case "KickPlayerRaw" -> {
          // KickPlayer's JSON form, as MessageRaw is Message's.
          String who = in.readUTF();
          Text reason = gg.tame.conduit.text.TextCodec.fromJson(in.readUTF());
          player(who).filter(BungeeCordMessages::kickable).ifPresent(target -> target.disconnect(reason));
        }
        case "Forward" -> {
          String server = in.readUTF();
          forward(sender, channel, server, in);
        }
        case "ForwardToPlayer" -> {
          String who = in.readUTF();
          Optional<Player> target = player(who);
          // Read the body whether or not the player is here, so a miss cannot desynchronise nothing;
          // the stream is a fresh one per message, but reading it keeps the two paths the same shape.
          String forwardChannel = in.readUTF();
          byte[] body = readBlock(in);
          if (target.isPresent() && body != null) {
            target.get().sendPluginMessageToServer(channel, forwarded(forwardChannel, body));
          }
        }
        default -> ConduitLog.debug("Ignoring an unknown " + channel + " subchannel: " + subchannel);
      }
    } catch (IOException malformed) {
      // A backend plugin that wrote a short or wrong payload. One line, and the message is still
      // consumed: passing a half-read proxy message on to the client would be worse.
      ConduitLog.debug("Malformed " + channel + " message from " + sender.username() + ": " + malformed.getMessage());
    }
    return true;
  }

  /** {@code Connect}: move a player, which is what every hub button and most queue plugins want. */
  private void connect(Player player, String server) {
    Optional<BackendServer> backend = registered(server);
    if (backend.isEmpty()) {
      // At WARN, and naming what is registered. This is the likeliest way a hub button does nothing
      // on a correctly configured proxy: the plugin's server name and the name in conduit.toml have
      // to be the same word, and nothing else in the system ever says they are not.
      ConduitLog.warn("A backend plugin asked to connect " + player.username() + " to '" + server
          + "', which is not a server in conduit.toml. Registered servers are: "
          + String.join(", ", runtime.selector().registry().names())
          + ". The name in the plugin's configuration has to match one of those.");
      return;
    }
    // Through the same path /server takes, so health, events and the switch's own rules all apply:
    // a plugin message must not be a way around what a command has to obey. The switch itself is
    // only on the tracked player, which every real player also is.
    //
    // Said out loud, because this is the moment an operator needs to see. The switch tells the
    // player when it cannot happen -- "dev is unavailable" -- but until now it told the log nothing,
    // so a hub button that did nothing looked identical whether the message never arrived, the
    // server was not registered, or the target refused the player. The switch's own outcome is the
    // player's to be told; that it was asked for, and by whom, belongs here.
    if (!(player instanceof TrackedPlayer tracked)) return;
    ConduitLog.info("A backend plugin asked to move " + player.username() + " to '"
        + backend.get().name() + "'. If they do not arrive, check that server with /conduit health.");
    tracked.transferTo(backend.get().name());
  }

  /**
   * {@code Forward}: hand a plugin's own payload to the same plugin on other backends.
   *
   * <p>The body is passed through exactly as it arrived. Conduit does not know what is in it and has
   * no business knowing: this is two copies of somebody's plugin talking to each other.
   */
  private void forward(Player sender, String channel, String server, DataInputStream in) throws IOException {
    String forwardChannel = in.readUTF();
    byte[] body = readBlock(in);
    if (body == null) return;
    byte[] payload = forwarded(forwardChannel, body);
    String senderServer = sender.currentServer().name();
    // Neither sends back to the sender: a plugin that received its own broadcast would handle it
    // twice. ALL means every configured server and ONLINE means only those with players on them,
    // but a message reaches a server through a player already on it, so an empty server cannot be
    // reached either way and the two come to the same set here. That is the protocol's own limit --
    // a proxy holds no connection to a server nobody is on -- and not a choice made here.
    boolean everywhere = ALL.equalsIgnoreCase(server) || ONLINE.equalsIgnoreCase(server);
    for (String name : runtime.selector().registry().names()) {
      if (everywhere && name.equalsIgnoreCase(senderServer)) continue;
      if (!everywhere && !name.equalsIgnoreCase(server)) continue;
      // A message reaches a server through one player on it, because that is the only connection
      // the proxy holds to it. A server with nobody on it cannot be reached, which is the protocol's
      // own limitation rather than this one's.
      deliver(name, channel, payload);
    }
  }

  /** Sends one payload to a server, through whichever player on it can carry it. */
  private void deliver(String server, String channel, byte[] payload) {
    for (TrackedPlayer tracked : runtime.playerManager().byServer(server)) {
      if (tracked instanceof Player player && player.sendPluginMessageToServer(channel, payload)) return;
    }
  }

  /** The {@code <subchannel><length><data>} body both forwarding subchannels carry. */
  private static byte[] forwarded(String forwardChannel, byte[] body) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF(forwardChannel);
      out.writeShort(body.length);
      out.write(body);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
    return bytes.toByteArray();
  }

  /**
   * The length-prefixed block a forwarding subchannel carries, or null when the length does not
   * match what is actually there -- a plugin's bug, and not a reason to hand on a truncated body.
   */
  private static byte[] readBlock(DataInputStream in) throws IOException {
    int length = in.readShort() & 0xffff;
    byte[] body = new byte[length];
    in.readFully(body);
    return body;
  }

  private void reply(Player sender, String channel, Reply body) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      body.write(out);
    } catch (IOException impossible) {
      return;
    }
    // Back to the backend that asked, and to nobody else.
    sender.sendPluginMessageToServer(channel, bytes.toByteArray());
  }

  /**
   * Whether a backend may disconnect this player. Staff who hold {@code conduit.punish.exempt} are
   * out of reach of {@code /gkick} from another player, and a plugin on one backend is not more
   * trusted than that.
   */
  private static boolean kickable(Player target) {
    if (!gg.tame.conduit.command.Permissions.allows(target, gg.tame.conduit.command.Permissions.PUNISH_EXEMPT)) return true;
    ConduitLog.debug("A backend asked to kick " + target.username() + ", who is exempt from punishment; refused.");
    return false;
  }

  private Optional<Player> player(String username) {
    return runtime.playerManager().getByUsername(username)
        .filter(Player.class::isInstance).map(Player.class::cast);
  }

  private Optional<BackendServer> registered(String name) {
    return runtime.selector().registry().get(name);
  }

  /**
   * Legacy text arrives with the section sign a backend plugin wrote, and Conduit's reader for that
   * formatting takes an ampersand. Only a sign in front of a code character is turned, so a lone
   * section sign in somebody's message survives as itself.
   */
  private static String sectionToAmpersand(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    for (int index = 0; index < raw.length(); index++) {
      char current = raw.charAt(index);
      char next = index + 1 < raw.length() ? Character.toLowerCase(raw.charAt(index + 1)) : 0;
      if (current == '§' && "0123456789abcdefklmnor".indexOf(next) >= 0) out.append('&');
      else out.append(current);
    }
    return out.toString();
  }

  /** The protocol writes a UUID without its dashes, which is how a backend plugin expects to read it. */
  private static String undashed(java.util.UUID uniqueId) {
    return uniqueId.toString().replace("-", "").toLowerCase(Locale.ROOT);
  }

  @FunctionalInterface
  private interface Reply { void write(DataOutputStream out) throws IOException; }
}
