package gg.tame.conduit.api.permission;

public interface PermissionSubject {
  boolean hasPermission(String permission);
}
