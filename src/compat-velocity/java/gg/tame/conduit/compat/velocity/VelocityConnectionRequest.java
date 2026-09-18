package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import gg.tame.conduit.api.player.ConnectResult;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** A server switch, on Conduit's connectWithResult. */
final class VelocityConnectionRequest implements ConnectionRequestBuilder {
  private final VelocityEnvironment environment;
  private final VelocityPlayer player;
  private final RegisteredServer server;
  VelocityConnectionRequest(VelocityEnvironment environment, VelocityPlayer player, RegisteredServer server) {
    if (server == null) throw new IllegalArgumentException("server is required");
    this.environment = environment; this.player = player; this.server = server;
  }
  @Override public RegisteredServer getServer() { return server; }

  @Override public CompletableFuture<Result> connect() {
    var target = environment.nativeServer(server);
    return player.nativePlayer().connectWithResult(target).thenApply(this::result);
  }
  private Result result(ConnectResult outcome) {
    Status status = switch (outcome.status()) {
      case CONNECTED -> Status.SUCCESS;
      case ALREADY_CONNECTED -> Status.ALREADY_CONNECTED;
      case IN_PROGRESS -> Status.CONNECTION_IN_PROGRESS;
      case CANCELLED -> Status.CONNECTION_CANCELLED;
      case FAILED -> Status.SERVER_DISCONNECTED;
    };
    Optional<Component> reason = outcome.reason().isEmpty() ? Optional.empty() : Optional.of(Component.text(outcome.reason()));
    return new Result() {
      @Override public Status getStatus() { return status; }
      @Override public Optional<Component> getReasonComponent() { return reason; }
      @Override public RegisteredServer getAttemptedConnection() { return server; }
    };
  }
  /** As connect(), but the player is told when it did not work. */
  @Override public CompletableFuture<Boolean> connectWithIndication() {
    return connect().thenApply(result -> {
      if (!result.isSuccessful() && result.getStatus() != Status.ALREADY_CONNECTED) {
        Component message = Component.text("Unable to connect to " + server.getServerInfo().getName(), NamedTextColor.RED);
        player.sendMessage(result.getReasonComponent().map(reason -> message.append(Component.text(": ")).append(reason)).orElse(message));
      }
      return result.isSuccessful();
    });
  }
  @Override public void fireAndForget() { connectWithIndication(); }
}
