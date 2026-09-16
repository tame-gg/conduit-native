package gg.tame.conduit.config;

/** Bundled operational settings. Absent config sections resolve to defaults. */
public record OpsSettings(
    int schemaVersion,
    MaintenanceSettings maintenance,
    HealthSettings health,
    VersionGateSettings versions,
    ShutdownSettings shutdown,
    SecuritySettings security,
    ModdedSettings modded,
    TranslationSettings translation
) {
  public static final int CURRENT_SCHEMA = 4;

  public OpsSettings {
    if (schemaVersion < 0) throw new IllegalArgumentException("ops.schema-version must be >= 0");
    if (maintenance == null) maintenance = MaintenanceSettings.defaults();
    if (health == null) health = HealthSettings.defaults();
    if (versions == null) versions = VersionGateSettings.defaults();
    if (shutdown == null) shutdown = ShutdownSettings.defaults();
    if (security == null) security = SecuritySettings.defaults();
    if (modded == null) modded = ModdedSettings.defaults();
    if (translation == null) translation = TranslationSettings.defaults();
  }

  public static OpsSettings defaults() {
    return new OpsSettings(CURRENT_SCHEMA,
        MaintenanceSettings.defaults(),
        HealthSettings.defaults(),
        VersionGateSettings.defaults(),
        ShutdownSettings.defaults(),
        SecuritySettings.defaults(),
        ModdedSettings.defaults(),
        TranslationSettings.defaults());
  }
}
