package gg.tame.conduit.viaversion;

import io.netty.util.AttributeKey;

/** Channel attribute key linking an EmbeddedChannel back to its Conduit Via session. */
final class ConduitViaSessionHolder {
  static final AttributeKey<Object> KEY = AttributeKey.valueOf("conduit-via-session");

  private ConduitViaSessionHolder() {}
}
