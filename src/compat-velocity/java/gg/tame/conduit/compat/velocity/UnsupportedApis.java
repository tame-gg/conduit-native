package gg.tame.conduit.compat.velocity;

final class UnsupportedApis {
  private UnsupportedApis() {}
  static <T> T unsupported(String api) {
    throw new UnsupportedOperationException(api + " is UNSUPPORTED on Conduit's Velocity compatibility layer");
  }
}
