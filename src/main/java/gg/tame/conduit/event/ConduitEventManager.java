// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.event;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.event.EventManager;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.log.ConduitLog;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class ConduitEventManager implements EventManager {
  private static final Comparator<Handler> ORDER = Comparator.comparing((Handler handler) -> handler.order).thenComparingLong(handler -> handler.sequence);
  private final Map<Class<?>, CopyOnWriteArrayList<Handler>> handlers = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  /**
   * Plugins that were disabled. A task or thread of a plugin still running when it was disabled
   * could register a listener after the sweep had passed, and that listener then heard every event
   * for the life of the process, from a class loader that had been closed. Weak, as the scheduler's.
   * Guarded by {@code this}, with registration, so a registration and a retirement cannot interleave.
   */
  private final java.util.Set<Plugin> retired = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
  @Override public synchronized void register(Plugin plugin, Object listener) {
    if (retired.contains(plugin)) throw new IllegalStateException("plugin " + owner(plugin) + " is disabled");
    List<Handler> found = new ArrayList<>();
    for (Method method : listener.getClass().getMethods()) {
      Subscribe subscribe = method.getAnnotation(Subscribe.class);
      if (subscribe == null) continue;
      if (method.getParameterCount() != 1) throw new IllegalArgumentException("@Subscribe methods must take one event");
      Class<?> type = method.getParameterTypes()[0];
      // Refused here rather than accepted and never called: nothing is ever fired that is not an
      // Event, so such a listener would sit in the map looking registered and never run.
      if (!Event.class.isAssignableFrom(type)) throw new IllegalArgumentException("@Subscribe parameter must be an Event: " + type.getName());
      method.setAccessible(true);
      found.add(new Handler(plugin, listener, method, type, subscribe.order(), 0));
    }
    // All or nothing, as commands are: a bad method after a good one left half the listener live.
    for (Handler handler : found) {
      handlers.computeIfAbsent(handler.type, ignored -> new CopyOnWriteArrayList<>())
          .add(new Handler(handler.plugin, handler.listener, handler.method, handler.type, handler.order, sequence.incrementAndGet()));
    }
  }
  @Override public synchronized void unregister(Plugin plugin) {
    for (CopyOnWriteArrayList<Handler> list : handlers.values()) list.removeIf(handler -> handler.plugin == plugin);
  }
  /** Removes the plugin's listeners and refuses it any more: it has been disabled. */
  public synchronized void retire(Plugin plugin) {
    retired.add(plugin);
    unregister(plugin);
  }
  /** Whether a listener would hear an event of {@code type}: for an event only worth building when one would. */
  public boolean listening(Class<? extends Event> type) {
    for (Map.Entry<Class<?>, CopyOnWriteArrayList<Handler>> entry : handlers.entrySet()) {
      if (entry.getKey().isAssignableFrom(type) && !entry.getValue().isEmpty()) return true;
    }
    return false;
  }
  @Override public <E extends Event> E fire(E event) {
    List<Handler> matching = new ArrayList<>();
    for (Map.Entry<Class<?>, CopyOnWriteArrayList<Handler>> entry : handlers.entrySet()) {
      // isInstance, not an exact key match: a listener declaring a supertype -- a shared base class,
      // or Event itself for a logger -- was registered under a class nothing is ever fired under,
      // so it silently never ran.
      if (entry.getKey().isInstance(event)) matching.addAll(entry.getValue());
    }
    // One order across every matching type. Walked type by type, a listener on Event and one on the
    // exact class ran in whatever order the map happened to hold them.
    matching.sort(ORDER);
    for (Handler handler : matching) {
      try { handler.method.invoke(handler.listener, event); }
      catch (InvocationTargetException thrown) {
        Throwable cause = thrown.getCause();
        if (cause instanceof VirtualMachineError fatal) throw fatal;
        ConduitLog.error("plugin event listener failed: " + owner(handler) + " on " + event.getClass().getSimpleName(), cause);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
        ConduitLog.error("plugin event listener failed: " + owner(handler), failure);
      }
    }
    return event;
  }
  private static String owner(Handler handler) { return owner(handler.plugin); }
  private static String owner(Plugin plugin) {
    try { return plugin.description().id(); } catch (RuntimeException unnamed) { return String.valueOf(plugin); }
  }
  private record Handler(Plugin plugin, Object listener, Method method, Class<?> type, Subscribe.Order order, long sequence) {}
}
