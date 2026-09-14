package gg.tame.conduit.api.permission;

/** Replaceable permission source. Default is permissive. */
public interface PermissionProvider {
  boolean hasPermission(PermissionSubject subject, String permission);
}
