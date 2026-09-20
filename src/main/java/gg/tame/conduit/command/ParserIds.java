// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

/**
 * How a release names an argument node's parser, and how its numbering lines up with the one
 * {@link ArgumentProperties} reads property payloads by.
 *
 * <p>Three shapes have existed:
 *
 * <ul>
 *   <li>1.13 to 1.18.2 write an identifier string, such as {@code brigadier:string}.
 *   <li>1.19 to 26.1 write an index into the registry vanilla builds, where
 *       {@code brigadier:string} is 5.
 *   <li>A release removing {@code brigadier:float} from that registry would move every index
 *       above {@code brigadier:bool} down by one, making {@code brigadier:string} 4. No protocol
 *       Conduit has seen on the wire does this -- see {@link #FLOAT_REMOVED}.
 * </ul>
 *
 * <p>This matters because a parser decides how many bytes of properties follow it. Read with the
 * wrong numbering, an argument node consumes the wrong length and every byte after it is
 * misinterpreted -- which is why the merge path deliberately copies a backend's nodes without
 * decoding them, and why anything Conduit does decode has to say which release wrote it.
 */
public enum ParserIds {
  /** 1.13 to 1.18.2: the parser is an identifier string. */
  IDENTIFIER,
  /** 1.19 to 26.1: a registry index, the numbering {@link ArgumentProperties} is written against. */
  INDEXED,
  /**
   * The same registry without {@code brigadier:float}, for a release that drops it. Nothing
   * selects this yet; see {@link #FLOAT_REMOVED}.
   */
  INDEXED_NO_FLOAT;

  /**
   * The protocol at which {@code brigadier:float} leaves the registry, if one ever does.
   *
   * <p>This was 776, on the belief that 26.2 removed it. A 26.2 server's own command tree says
   * otherwise: across the first 955 nodes of one, {@code brigadier:string} is id 5 (68 uses) and
   * id 4 is never used at all -- which is the numbering with float still in it, where 4 is
   * {@code brigadier:long}, a parser vanilla commands barely touch.
   *
   * <p>Getting it wrong is not a subtle mistake. Conduit wrote {@code /alert <message>} as parser
   * 4 with one properties byte meaning "greedy"; the client read parser 4 as
   * {@code brigadier:long}, took that byte as "a maximum follows", swallowed the next eight bytes
   * as that maximum, and every node after it was read from the wrong offset until it ran off the
   * end of the packet and dropped the connection.
   *
   * <p>Set beyond any protocol Conduit knows, so the removal is selected only once a tree that
   * actually does it has been seen. Raising it needs a capture, not a changelog.
   */
  private static final int FLOAT_REMOVED = Integer.MAX_VALUE;
  /** The first protocol to write a registry index rather than an identifier (Minecraft 1.19). */
  private static final int INDEXED_FROM = 759;

  public static ParserIds forProtocol(int protocolNumber) {
    if (protocolNumber >= FLOAT_REMOVED) return INDEXED_NO_FLOAT;
    if (protocolNumber >= INDEXED_FROM) return INDEXED;
    return IDENTIFIER;
  }

  /** True when a parser is written as a varint index rather than an identifier string. */
  public boolean indexed() {
    return this != IDENTIFIER;
  }

  /**
   * The id to look up properties by, given what this release wrote.
   *
   * <p>{@code brigadier:bool} is 0 in both numberings, so only ids above it shift.
   */
  public int canonical(int id) {
    return this == INDEXED_NO_FLOAT && id >= 1 ? id + 1 : id;
  }

  /** The id this release writes for {@code brigadier:string}. */
  public int stringId() {
    return wireId(5);
  }

  /**
   * The id this release writes for a parser whose canonical id is {@code canonical} -- the inverse
   * of {@link #canonical(int)}, for writing a tree rather than reading one.
   *
   * @throws IllegalArgumentException for {@code brigadier:float} under a numbering that has no such
   *     parser, which has no id to write and must not be written as its neighbour's
   */
  public int wireId(int canonical) {
    if (this != INDEXED_NO_FLOAT) return canonical;
    if (canonical == 1) throw new IllegalArgumentException("this release has no brigadier:float to write");
    return canonical >= 2 ? canonical - 1 : canonical;
  }
}
