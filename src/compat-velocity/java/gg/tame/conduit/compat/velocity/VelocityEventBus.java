package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import gg.tame.conduit.log.ConduitLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class VelocityEventBus implements EventManager {
  private final Map<Class<?>, CopyOnWriteArrayList<Bound>> handlers = new ConcurrentHashMap<>();
  @Override public void register(Object plugin, Object listener) {
    for (Method method : listener.getClass().getMethods()) {
      Subscribe subscribe = method.getAnnotation(Subscribe.class);
      if (subscribe == null) continue;
      if (method.getParameterCount() != 1) continue;
      Class<?> type = method.getParameterTypes()[0];
      handlers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>())
          .add(new Bound(plugin, listener, method, (event) -> {
            method.setAccessible(true);
            method.invoke(listener, event);
          }));
    }
  }
  @Override public <E> void register(Object plugin, Class<E> eventClass, EventHandler<E> handler) {
    register(plugin, eventClass, PostOrder.NORMAL, handler);
  }
  @Override public <E> void register(Object plugin, Class<E> eventClass, PostOrder postOrder, EventHandler<E> handler) {
    register(plugin, eventClass, (short) 0, handler);
  }
  @Override public <E> void register(Object plugin, Class<E> eventClass, short postOrder, EventHandler<E> handler) {
    handlers.computeIfAbsent(eventClass, ignored -> new CopyOnWriteArrayList<>())
        .add(new Bound(plugin, handler, null, event -> handler.execute(eventClass.cast(event))));
  }
  @Override public <E> CompletableFuture<E> fire(E event) {
    List<Bound> list = handlers.getOrDefault(event.getClass(), new CopyOnWriteArrayList<>());
    for (Bound bound : new ArrayList<>(list)) {
      try { bound.invoke.apply(event); }
      catch (Exception exception) { ConduitLog.error("Velocity plugin event failed", exception); }
    }
    return CompletableFuture.completedFuture(event);
  }
  @Override public void unregisterListeners(Object plugin) {
    for (CopyOnWriteArrayList<Bound> list : handlers.values()) list.removeIf(bound -> bound.plugin == plugin);
  }
  @Override public void unregisterListener(Object plugin, Object listener) {
    for (CopyOnWriteArrayList<Bound> list : handlers.values()) list.removeIf(bound -> bound.plugin == plugin && bound.listener == listener);
  }
  @Override public <E> void unregister(Object plugin, EventHandler<E> handler) {
    for (CopyOnWriteArrayList<Bound> list : handlers.values()) list.removeIf(bound -> bound.plugin == plugin && bound.listener == handler);
  }
  private record Bound(Object plugin, Object listener, Method method, Invoker invoke) {}
  @FunctionalInterface private interface Invoker { void apply(Object event) throws Exception; }
}
