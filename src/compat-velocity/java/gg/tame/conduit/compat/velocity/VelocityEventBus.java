// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Velocity's EventManager. Handlers for one event run one after another, highest priority first
 * (registration order breaks ties), on the adapter's threads. A handler that returns an EventTask,
 * or takes a Continuation, holds the next handler back until it resumes.
 */
final class VelocityEventBus implements EventManager {
  /**
   * The Velocity events Conduit raises. A handler for any other proxy event is still registered,
   * but it is logged when it is: it will never be called.
   */
  static final Set<Class<?>> FIRED = Set.of(ProxyInitializeEvent.class, ProxyShutdownEvent.class, LoginEvent.class,
      PostLoginEvent.class, DisconnectEvent.class, PlayerChooseInitialServerEvent.class, ServerPreConnectEvent.class,
      ServerConnectedEvent.class, ServerPostConnectEvent.class, CommandExecuteEvent.class, PluginMessageEvent.class,
      PlayerChatEvent.class, PermissionsSetupEvent.class, ProxyPingEvent.class, KickedFromServerEvent.class,
      com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent.class,
      com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent.class,
      com.velocitypowered.api.event.proxy.ProxyPreShutdownEvent.class, com.velocitypowered.api.event.proxy.ProxyReloadEvent.class,
      com.velocitypowered.api.event.proxy.ListenerBoundEvent.class, com.velocitypowered.api.event.proxy.ListenerCloseEvent.class,
      com.velocitypowered.api.event.player.PlayerSettingsChangedEvent.class, com.velocitypowered.api.event.player.PlayerClientBrandEvent.class,
      com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent.class,
      com.velocitypowered.api.event.connection.PreLoginEvent.class, com.velocitypowered.api.event.player.GameProfileRequestEvent.class,
      com.velocitypowered.api.event.connection.PreTransferEvent.class);

  private final VelocityEnvironment environment;
  private final CopyOnWriteArrayList<Handler> handlers = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong();
  VelocityEventBus(VelocityEnvironment environment) { this.environment = environment; }

  private interface Invoker { EventTask invoke(Object event) throws Throwable; }
  private record Handler(VelocityPluginHost.Container plugin, Object listener, Class<?> type, int priority, long order, Invoker invoker, String name) {}

  @Override public void register(Object plugin, Object listener) {
    VelocityPluginHost.Container container = environment.plugins.require(plugin);
    if (listener == null) throw new IllegalArgumentException("listener is required");
    List<Handler> found = new ArrayList<>();
    Set<String> overridden = new HashSet<>();
    for (Class<?> type = listener.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        String signature = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
        if (!overridden.add(signature)) continue;
        Subscribe subscribe = method.getAnnotation(Subscribe.class);
        if (subscribe != null) found.add(handler(container, listener, method, subscribe));
      }
    }
    for (Handler handler : found) add(handler);
  }

  private Handler handler(VelocityPluginHost.Container container, Object listener, Method method, Subscribe subscribe) {
    String name = method.getDeclaringClass().getName() + "." + method.getName();
    Class<?>[] parameters = method.getParameterTypes();
    boolean continuation = parameters.length == 2 && parameters[1] == Continuation.class;
    if (Modifier.isStatic(method.getModifiers())) throw new IllegalArgumentException("@Subscribe method " + name + " is static");
    if (parameters.length != 1 && !continuation) {
      throw new IllegalArgumentException("@Subscribe method " + name + " must take the event, optionally followed by a Continuation");
    }
    boolean returnsTask = method.getReturnType() == EventTask.class;
    if (method.getReturnType() != void.class && !returnsTask) throw new IllegalArgumentException("@Subscribe method " + name + " must return void or EventTask");
    if (continuation && returnsTask) throw new IllegalArgumentException("@Subscribe method " + name + " cannot both take a Continuation and return an EventTask");
    method.setAccessible(true);
    Invoker invoker;
    if (continuation) invoker = event -> EventTask.withContinuation(resume -> invokeQuietly(method, listener, resume, event));
    else if (returnsTask) invoker = event -> (EventTask) unwrap(method, listener, event);
    else invoker = event -> { unwrap(method, listener, event); return null; };
    return new Handler(container, listener, parameters[0], priority(subscribe), sequence.incrementAndGet(), invoker, name);
  }
  private static Object unwrap(Method method, Object listener, Object... arguments) throws Throwable {
    try { return method.invoke(listener, arguments); }
    catch (InvocationTargetException thrown) { throw thrown.getCause(); }
  }
  private static void invokeQuietly(Method method, Object listener, Continuation resume, Object event) {
    try { method.invoke(listener, event, resume); }
    catch (InvocationTargetException thrown) { resume.resumeWithException(thrown.getCause()); }
    catch (IllegalAccessException impossible) { resume.resumeWithException(impossible); }
  }

  /** Priority decides; the deprecated PostOrder still wins when a plugin sets anything but NORMAL. */
  @SuppressWarnings("deprecation")
  private static int priority(Subscribe subscribe) {
    return switch (subscribe.order()) {
      case FIRST -> Short.MAX_VALUE;
      case EARLY -> Short.MAX_VALUE / 2;
      case LATE -> Short.MIN_VALUE / 2;
      case LAST -> Short.MIN_VALUE;
      default -> subscribe.priority();
    };
  }
  @SuppressWarnings("deprecation")
  private static int priority(PostOrder order) {
    return switch (order) {
      case FIRST -> Short.MAX_VALUE;
      case EARLY -> Short.MAX_VALUE / 2;
      case LATE -> Short.MIN_VALUE / 2;
      case LAST -> Short.MIN_VALUE;
      default -> 0;
    };
  }

  @Override public <E> void register(Object plugin, Class<E> eventClass, PostOrder postOrder, EventHandler<E> handler) {
    register(plugin, eventClass, (short) priority(postOrder), handler);
  }
  @Override public <E> void register(Object plugin, Class<E> eventClass, short priority, EventHandler<E> handler) {
    VelocityPluginHost.Container container = environment.plugins.require(plugin);
    if (eventClass == null || handler == null) throw new IllegalArgumentException("event class and handler are required");
    add(new Handler(container, handler, eventClass, priority, sequence.incrementAndGet(),
        event -> handler.executeAsync(eventClass.cast(event)), handler.getClass().getName()));
  }
  private void add(Handler handler) {
    Class<?> type = handler.type;
    if (type.getName().startsWith("com.velocitypowered.api.event.") && FIRED.stream().noneMatch(type::isAssignableFrom)) {
      environment.log.warning("Velocity plugin " + handler.plugin.id() + " listens for " + type.getSimpleName()
          + ", which Conduit never fires: " + handler.name + " will not be called");
    }
    handlers.add(handler);
  }

  @Override public <E> CompletableFuture<E> fire(E event) {
    if (event == null) throw new NullPointerException("event");
    return dispatch(event, handler -> true);
  }
  /** Just one plugin's handlers, for the ProxyShutdownEvent a plugin gets when it alone is disabled. */
  <E> CompletableFuture<E> fireTo(VelocityPluginHost.Container plugin, E event) {
    return dispatch(event, handler -> handler.plugin == plugin);
  }
  boolean listening(Class<?> eventClass) {
    for (Handler handler : handlers) if (handler.type.isAssignableFrom(eventClass)) return true;
    return false;
  }
  private <E> CompletableFuture<E> dispatch(E event, Predicate<Handler> filter) {
    List<Handler> matching = new ArrayList<>();
    for (Handler handler : handlers) if (handler.type.isInstance(event) && filter.test(handler)) matching.add(handler);
    if (matching.isEmpty()) return CompletableFuture.completedFuture(event);
    matching.sort(Comparator.comparingInt((Handler handler) -> -handler.priority).thenComparingLong(Handler::order));
    CompletableFuture<E> done = new CompletableFuture<>();
    environment.work.execute(() -> run(matching, 0, event, done));
    return done;
  }
  private <E> void run(List<Handler> matching, int from, E event, CompletableFuture<E> done) {
    for (int index = from; index < matching.size(); index++) {
      Handler handler = matching.get(index);
      EventTask task;
      try { task = handler.invoker.invoke(event); }
      catch (Throwable failed) { report(handler, event, failed); continue; }
      if (task == null) continue;
      int next = index + 1;
      AtomicBoolean resumed = new AtomicBoolean();
      Continuation continuation = new Continuation() {
        @Override public void resume() {
          if (resumed.compareAndSet(false, true)) environment.work.execute(() -> run(matching, next, event, done));
        }
        @Override public void resumeWithException(Throwable failure) {
          if (!resumed.compareAndSet(false, true)) return;
          report(handler, event, failure);
          environment.work.execute(() -> run(matching, next, event, done));
        }
      };
      try { task.execute(continuation); }
      catch (Throwable failed) { continuation.resumeWithException(failed); }
      return;
    }
    done.complete(event);
  }
  private void report(Handler handler, Object event, Throwable failure) {
    environment.log.log(Level.SEVERE, "Velocity plugin " + handler.plugin.id() + " failed handling "
        + event.getClass().getSimpleName() + " in " + handler.name, failure);
  }

  @Override public void unregisterListeners(Object plugin) {
    environment.plugins.find(plugin).ifPresent(container -> handlers.removeIf(handler -> handler.plugin == container));
  }
  @Override public void unregisterListener(Object plugin, Object listener) {
    environment.plugins.find(plugin).ifPresent(container -> handlers.removeIf(handler -> handler.plugin == container && handler.listener == listener));
  }
  @Override public <E> void unregister(Object plugin, EventHandler<E> handler) { unregisterListener(plugin, handler); }
}
