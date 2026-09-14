package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;

final class VelocityConnectionRequest implements ConnectionRequestBuilder {
  private final VelocityPlayer player;
  private final RegisteredServer server;
  VelocityConnectionRequest(VelocityPlayer player, RegisteredServer server) {
    this.player = player;
    this.server = server;
  }
  @Override public RegisteredServer getServer() { return server; }
  @Override public CompletableFuture<Result> connect() {
    return player.nativePlayer().connect(((VelocityRegisteredServer) server).nativeServer()).thenApply(ok -> new Result() {
      @Override public Status getStatus() { return ok ? Status.SUCCESS : Status.CONNECTION_CANCELLED; }
      @Override public Optional<Component> getReasonComponent() { return Optional.empty(); }
      @Override public RegisteredServer getAttemptedConnection() { return server; }
    });
  }
  @Override public CompletableFuture<Boolean> connectWithIndication() {
    return player.nativePlayer().connect(((VelocityRegisteredServer) server).nativeServer());
  }
  @Override public void fireAndForget() { connect(); }
}
