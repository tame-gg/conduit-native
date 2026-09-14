package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Wire encode/decode for Game Profile and profile properties. Protocol-independent layout. */
public final class GameProfiles {
  private GameProfiles() {}
  public static void write(DataOutputStream output, PlayerProfile profile) throws IOException {
    writeUuid(output, profile.uniqueId());
    MinecraftOutput.string(output, profile.username());
    writeProperties(output, profile.properties());
  }
  public static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
    output.writeLong(uuid.getMostSignificantBits());
    output.writeLong(uuid.getLeastSignificantBits());
  }
  public static UUID readUuid(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }
  public static void writeProperties(DataOutputStream output, List<ProfileProperty> properties) throws IOException {
    MinecraftOutput.varInt(output, properties.size());
    for (ProfileProperty property : properties) {
      MinecraftOutput.string(output, property.name());
      MinecraftOutput.string(output, property.value());
      output.writeBoolean(property.signature().isPresent());
      if (property.signature().isPresent()) MinecraftOutput.string(output, property.signature().get());
    }
  }
  public static List<ProfileProperty> readProperties(DataInputStream input) throws IOException {
    int count = MinecraftInput.varInt(input);
    if (count < 0 || count > 16) throw new IOException("invalid profile property count");
    List<ProfileProperty> properties = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      String name = MinecraftInput.string(input, 64);
      String value = MinecraftInput.string(input, 32767);
      Optional<String> signature = Optional.empty();
      if (input.readBoolean()) signature = Optional.of(MinecraftInput.string(input, 8192));
      if (name.isBlank()) throw new IOException("malformed profile property");
      properties.add(new ProfileProperty(name, value, signature));
    }
    return List.copyOf(properties);
  }
  public static void skip(DataInputStream input) throws IOException {
    readUuid(input);
    MinecraftInput.string(input, 16);
    readProperties(input);
  }
  public static boolean hasTextures(List<ProfileProperty> properties) {
    return properties.stream().anyMatch(property -> property.name().equals("textures"));
  }
}
