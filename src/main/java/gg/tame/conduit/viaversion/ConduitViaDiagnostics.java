package gg.tame.conduit.viaversion;

/**
 * Observability snapshot for a client/backend protocol pairing.
 */
public record ConduitViaDiagnostics(
    int clientProtocol,
    int backendProtocol,
    String mode,
    String translator,
    boolean translationSupported,
    String path
) {
  public static ConduitViaDiagnostics of(int clientProtocol, int backendProtocol, String mode, String translator) {
    boolean supported = clientProtocol == backendProtocol
        || ConduitViaSupport.supportsTranslation(clientProtocol, backendProtocol);
    String path = clientProtocol == backendProtocol
        ? "identity"
        : ConduitViaSupport.describePath(clientProtocol, backendProtocol);
    return new ConduitViaDiagnostics(clientProtocol, backendProtocol, mode, translator, supported, path);
  }

  public String render() {
    return "Client Protocol: " + clientProtocol
        + "\nBackend Protocol: " + backendProtocol
        + "\nMode: " + mode
        + "\nTranslator: " + translator
        + "\nTranslation Supported: " + (translationSupported ? "YES" : "NO")
        + (path == null || path.isBlank() || "none".equals(path) || "identity".equals(path)
            ? ""
            : "\nVia Path: " + path);
  }
}
