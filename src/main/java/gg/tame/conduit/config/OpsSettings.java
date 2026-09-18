// SPDX-License-Identifier: GPL-3.0-or-later
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
    TranslationSettings translation,
    StatusSettings status,
    MetricsSettings metrics
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
    if (status == null) status = StatusSettings.defaults();
    if (metrics == null) metrics = MetricsSettings.defaults();
  }

  /** Every setting but the metrics ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, null);
  }

  /** Every setting but the server-list ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, null, null);
  }

  public static OpsSettings defaults() {
    return new OpsSettings(CURRENT_SCHEMA,
        MaintenanceSettings.defaults(),
        HealthSettings.defaults(),
        VersionGateSettings.defaults(),
        ShutdownSettings.defaults(),
        SecuritySettings.defaults(),
        ModdedSettings.defaults(),
        TranslationSettings.defaults(),
        StatusSettings.defaults(),
        MetricsSettings.defaults());
  }
}
