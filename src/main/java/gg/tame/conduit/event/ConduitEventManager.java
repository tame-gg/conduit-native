package gg.tame.conduit.event;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.event.EventManager;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.log.ConduitLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
      handlers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(new Handler(plugin, listener, method));
    }
  }
  @Override public void unregister(Plugin plugin) {
    for (CopyOnWriteArrayList<Handler> list : handlers.values()) list.removeIf(handler -> handler.plugin == plugin);
  }
  @Override public <E extends Event> E fire(E event) {
    List<Handler> list = handlers.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>());
    for (Handler handler : new ArrayList<>(list)) {
      try {
        handler.method.setAccessible(true);
        handler.method.invoke(handler.listener, event);
      }
      catch (Exception exception) { ConduitLog.error("plugin event listener failed: " + handler.plugin.description().id(), exception); }
    }
    return event;
  }
  private record Handler(Plugin plugin, Object listener, Method method) {}
}
