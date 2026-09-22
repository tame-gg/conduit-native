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
    MetricsSettings metrics,
    UpdateSettings updates,
    MessagingSettings messaging,
    RoutingSettings routing,
    BanSettings bans,
    PermissionSettings permissions
) {
  public static final int CURRENT_SCHEMA = 5;

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
    if (updates == null) updates = UpdateSettings.defaults();
    if (messaging == null) messaging = MessagingSettings.defaults();
    if (routing == null) routing = RoutingSettings.defaults();
    if (bans == null) bans = BanSettings.defaults();
    if (permissions == null) permissions = PermissionSettings.defaults();
  }

  /** Every setting but the operators list, which then takes its default. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status, MetricsSettings metrics, UpdateSettings updates, MessagingSettings messaging,
                     RoutingSettings routing, BanSettings bans) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, metrics, updates,
        messaging, routing, bans, null);
  }

  /** Every setting but the ban screen, which then takes its default. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status, MetricsSettings metrics, UpdateSettings updates, MessagingSettings messaging,
                     RoutingSettings routing) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, metrics, updates,
        messaging, routing, null, null);
  }

  /** Every setting but the routing ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status, MetricsSettings metrics, UpdateSettings updates, MessagingSettings messaging) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, metrics, updates,
        messaging, null);
  }

  /** Every setting but the messaging and routing ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status, MetricsSettings metrics, UpdateSettings updates) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, metrics, updates, null, null);
  }

  /** Every setting but the update and messaging ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status, MetricsSettings metrics) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, metrics, null, null, null);
  }

  /** Every setting but the metrics and update ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation,
                     StatusSettings status) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, status, null, null, null, null);
  }

  /** Every setting but the server-list ones, which then take their defaults. */
  public OpsSettings(int schemaVersion, MaintenanceSettings maintenance, HealthSettings health, VersionGateSettings versions,
                     ShutdownSettings shutdown, SecuritySettings security, ModdedSettings modded, TranslationSettings translation) {
    this(schemaVersion, maintenance, health, versions, shutdown, security, modded, translation, null, null, null, null, null);
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
        MetricsSettings.defaults(),
        UpdateSettings.defaults(),
        MessagingSettings.defaults(),
        RoutingSettings.defaults(),
        BanSettings.defaults(),
        PermissionSettings.defaults());
  }
}
