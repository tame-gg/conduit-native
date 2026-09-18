// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import gg.tame.conduit.viaversion.ConduitViaSupport;
import gg.tame.conduit.viaversion.ConduitViaTranslator;
import java.nio.file.Files;
import java.nio.file.Path;

/** ViaVersion platform smoke tests. Does not claim real-client verification. */
public final class ViaIntegrationTests {
  private ViaIntegrationTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    Path data = TempFiles.dir("conduit-via-test");
    ConduitViaBootstrap.start(data, "test",
        new TranslationSettings(true, TranslationSettings.TranslationEngine.VIA_PREFERRED, true, true, false, "via"));
    require(ConduitViaBootstrap.available(), "Via platform available");

    require(ConduitViaSupport.knowsProtocol(5), "Via knows 1.7.6 (5)");
    require(ConduitViaSupport.knowsProtocol(393), "Via knows 1.13");
    require(ConduitViaSupport.knowsProtocol(765), "Via knows 1.20.4");
    require(ConduitViaSupport.knowsProtocol(776), "Via knows 26.2");
    require(ProtocolVersion.getClosest("26.3") == null, "26.3 not registered in Via 5.11.0");

    require(ProtocolCompatibility.between(765, 765) == TranslationSupport.DIRECT, "same-version DIRECT");
    require(ConduitViaSupport.supportsTranslation(393, 765), "Via path 393→765");
    require(ConduitViaSupport.supportsTranslation(765, 393), "Via path 765→393");
    require(ProtocolCompatibility.between(393, 765) == TranslationSupport.TRANSLATED, "393→765 TRANSLATED");
    require(ProtocolCompatibility.between(765, 393) == TranslationSupport.TRANSLATED, "765→393 TRANSLATED");

    // With Via loaded, pairs that were native-UNSUPPORTED may become TRANSLATED.
    if (ConduitViaSupport.supportsTranslation(765, 776)) {
      require(ProtocolCompatibility.between(765, 776) == TranslationSupport.TRANSLATED,
          "Via 765→776 TRANSLATED when path exists");
    }

    // Via addresses a connection by the handler names its injector reports. If those stop matching
    // the names the session actually puts on its channel, every packet Via generates itself dies
    // in a null lookup — which is invisible until a real client asks for one.
    var injector = com.viaversion.viaversion.api.Via.getManager().getInjector();
    require("via-decoder".equals(injector.getDecoderName()), "injector decoder name");
    require("via-encoder".equals(injector.getEncoderName()), "injector encoder name");
    require(!injector.getServerProtocolVersions().isEmpty(), "injector reports a protocol range");

    try (ConduitViaTranslator translator = ConduitViaTranslator.create(393, 765, "127.0.0.1", 25565)) {
      require(translator.engineName().equals("ViaVersion"), "engine name");
      require(translator.drainToClient() != null, "drain client API");
      require(translator.drainToBackend() != null, "drain backend API");
      require(translator.pipelineHandlerNames().contains(injector.getDecoderName()),
          "session channel carries the decoder the injector names");
      require(translator.pipelineHandlerNames().contains(injector.getEncoderName()),
          "session channel carries the encoder the injector names");
      require(translator.pipelineHandlerNames().indexOf(injector.getDecoderName()) > 0,
          "decoder has a predecessor for Via to fire reads at");
    }

    require(Translators.forPair(393, 765, "127.0.0.1", 25565) instanceof ConduitViaTranslator,
        "preferred engine selects Via translator");

    System.out.println("ViaIntegrationTests passed (platform smoke only; not real-client VERIFIED)");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
