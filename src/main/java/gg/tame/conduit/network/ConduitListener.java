package gg.tame.conduit.network;

import gg.tame.conduit.config.ConduitConfiguration;
import java.io.IOException;
import java.nio.channels.ServerSocketChannel;

/** A deliberately small listener foundation; protocol handling is added in later milestones. */
public final class ConduitListener implements AutoCloseable {
  private final ServerSocketChannel channel;
  private ConduitListener(ServerSocketChannel channel) { this.channel = channel; }
  public static ConduitListener bind(ConduitConfiguration configuration) throws IOException {
    ServerSocketChannel channel = ServerSocketChannel.open();
    channel.bind(configuration.listener());
    return new ConduitListener(channel);
  }
  public int port() throws IOException { return ((java.net.InetSocketAddress) channel.getLocalAddress()).getPort(); }
  @Override public void close() throws IOException { channel.close(); }
}
