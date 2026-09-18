package gg.tame.conduit.event;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.event.EventManager;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.log.ConduitLog;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ConduitEventManager implements EventManager {
  private final Map<Class<?>, CopyOnWriteArrayList<Handler>> handlers = new ConcurrentHashMap<>();
  @Override public void register(Plugin plugin, Object listener) {
    for (Method method : listener.getClass().getMethods()) {
      if (method.getAnnotation(Subscribe.class) == null) continue;
      if (method.getParameterCount() != 1) throw new IllegalArgumentException("@Subscribe methods must take one event");
      Class<?> type = method.getParameterTypes()[0];
      // Refused here rather than accepted and never called: nothing is ever fired that is not an
      // Event, so such a listener would sit in the map looking registered and never run.
      if (!Event.class.isAssignableFrom(type)) throw new IllegalArgumentException("@Subscribe parameter must be an Event: " + type.getName());
      method.setAccessible(true);
      handlers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(new Handler(plugin, listener, method));
    }
  }
  @Override public void unregister(Plugin plugin) {
    for (CopyOnWriteArrayList<Handler> list : handlers.values()) list.removeIf(handler -> handler.plugin == plugin);
  }
  @Override public <E extends Event> E fire(E event) {
    for (Map.Entry<Class<?>, CopyOnWriteArrayList<Handler>> entry : handlers.entrySet()) {
      // isInstance, not an exact key match: a listener declaring a supertype -- a shared base class,
      // or Event itself for a logger -- was registered under a class nothing is ever fired under,
      // so it silently never ran.
      if (!entry.getKey().isInstance(event)) continue;
      for (Handler handler : entry.getValue()) {
        try { handler.method.invoke(handler.listener, event); }
        catch (Exception exception) { ConduitLog.error("plugin event listener failed: " + handler.plugin.description().id(), exception); }
      }
    }
    return event;
  }
  private record Handler(Plugin plugin, Object listener, Method method) {}
}
