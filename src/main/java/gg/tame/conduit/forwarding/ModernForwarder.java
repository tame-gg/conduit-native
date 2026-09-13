package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.login.ProfileProperty;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import gg.tame.conduit.protocol.MinecraftOutput;

/** Builds the public modern-forwarding binary payload and authenticates it with HMAC-SHA-256. */
public final class ModernForwarder implements PlayerInfoForwarder {
  public static final int FORWARDING_FORMAT_VERSION = 1;
  private final ForwardingSecret secret;
  public ModernForwarder(ForwardingSecret secret) { this.secret = secret; }
  @Override public ForwardingMode mode() { return ForwardingMode.MODERN; }
  @Override public byte[] payload(ForwardingRequest request) {
    try {
      if (request.forwardingVersion() != FORWARDING_FORMAT_VERSION) throw new IllegalArgumentException("unsupported modern forwarding version: " + request.forwardingVersion());
      ByteArrayOutputStream raw = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(raw)) {
        MinecraftOutput.varInt(out, request.forwardingVersion());
        MinecraftOutput.string(out, request.clientAddress().getHostAddress());
        out.writeLong(request.player().uniqueId().getMostSignificantBits()); out.writeLong(request.player().uniqueId().getLeastSignificantBits());
        MinecraftOutput.string(out, request.player().username());
        MinecraftOutput.varInt(out, request.player().properties().size());
        for (ProfileProperty property : request.player().properties()) {
          MinecraftOutput.string(out, property.name()); MinecraftOutput.string(out, property.value());
          out.writeBoolean(property.signature().isPresent()); if (property.signature().isPresent()) MinecraftOutput.string(out, property.signature().get());
        }
      }
      byte[] data = raw.toByteArray();
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.bytes(), "HmacSHA256"));
      byte[] signature = mac.doFinal(data); byte[] result = new byte[signature.length + data.length];
      System.arraycopy(signature, 0, result, 0, signature.length); System.arraycopy(data, 0, result, signature.length, data.length); return result;
    } catch (GeneralSecurityException | java.io.IOException exception) { throw new IllegalStateException("cannot create forwarding payload", exception); }
  }
}
