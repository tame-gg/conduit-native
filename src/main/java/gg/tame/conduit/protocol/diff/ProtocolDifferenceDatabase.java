package gg.tame.conduit.protocol.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintainable protocol difference database for the modern compatibility program.
 * Knowledge lives here — not in scattered comments.
 */
public final class ProtocolDifferenceDatabase {
  private static final List<ProtocolChange> CHANGES = build();

  private ProtocolDifferenceDatabase() {}

  public static List<ProtocolChange> all() {
    return CHANGES;
  }

  public static List<ProtocolChange> between(int from, int to) {
    return CHANGES.stream().filter(c -> c.fromProtocol() == from && c.toProtocol() == to).toList();
  }

  private static List<ProtocolChange> build() {
    List<ProtocolChange> list = new ArrayList<>();
    // 1.13 → 1.20.4 architectural distance (proof-point path)
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.STATE_MODEL, "CONFIGURATION",
        "1.13 has no Configuration state; Login Success enters Play. 1.20.4 requires Login Ack → Configuration → Finish → Play."));
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.CHANGED_PACKET, "LOGIN_START",
        "1.13: username only. 1.20.4: username + UUID."));
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.CHANGED_PACKET, "LOGIN_SUCCESS",
        "1.13: string UUID + username. 1.20.4: binary UUID + username + properties (+ optional fields)."));
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.CHANGED_PACKET, "PLAY_LOGIN",
        "1.13 Join Game: entityId, gamemode, dimension int, difficulty, maxPlayers, levelType, reducedDebug. 1.20.4: registry-driven dimension codec."));
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.SEMANTIC_CHANGE, "CHAT",
        "1.13 uses Chat packet (JSON + position). 1.20.4 uses System Chat / signed player chat."));
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.CAPABILITY, "CONFIGURATION",
        "Configuration-phase packets (Finish, Known Packs, Registry) exist only on 765+."));
    list.add(ProtocolChange.of(393, 765, ProtocolChange.ChangeKind.CHANGED_PACKET, "PLAY_KEEP_ALIVE",
        "Packet ID differs: 1.13 S2C 0x21 / C2S 0x0E; 1.20.4 S2C 0x24 / C2S 0x15. Payload remains long."));
    // 765 ↔ 766
    list.add(ProtocolChange.of(765, 766, ProtocolChange.ChangeKind.CAPABILITY, "KNOWN_PACKS",
        "1.20.5 adds Known Packs negotiation in Configuration."));
    list.add(ProtocolChange.of(765, 766, ProtocolChange.ChangeKind.CHANGED_PACKET, "PLAY_LOGIN",
        "Join Game / registry layout diverges; opaque remap is unsafe."));
    list.add(ProtocolChange.of(765, 766, ProtocolChange.ChangeKind.UNCHANGED_PACKET, "CONFIGURATION_FINISH",
        "Finish Configuration remains empty; ID remapped 0x02→0x03."));
    // Legacy boundary
    list.add(ProtocolChange.of(340, 393, ProtocolChange.ChangeKind.SEMANTIC_CHANGE, "FLATTENING",
        "1.12.2→1.13 is the modern-program lower boundary. 1.12.2 remains LEGACY_UNSUPPORTED."));
    return List.copyOf(list);
  }
}
