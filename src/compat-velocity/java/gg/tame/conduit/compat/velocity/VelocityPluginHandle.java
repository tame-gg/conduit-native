// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.Scheduler;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Velocity plugin as Conduit's plugin manager sees it. Everything the Velocity plugin registers
 * natively (commands, scheduled tasks) is owned by this handle, so Conduit releases it on disable.
 *
 * <p>Injection is Conduit's own, not Guice: one constructor, then {@code @Inject} fields and methods, each
 * resolved by type from {@link #services}, or, for the plugin's own concrete classes, built the same
 * way. Anything else fails the load, naming the type, rather than being left null. A plugin that asks
 * for Guice's {@link Injector} gets a real one holding the same bindings, for its own child injectors.
 */
final class VelocityPluginHandle extends ConduitPlugin {
  private static final Set<String> INJECT = Set.of("javax.inject.Inject", "jakarta.inject.Inject", "com.google.inject.Inject");
  private final VelocityEnvironment environment;
  private final VelocityPluginHost.Description velocityDescription;
  private final VelocityClassLoader loader;
  private final Class<?> mainClass;
  private volatile VelocityPluginHost.Container container;
  /** Built the first time the plugin asks for one; it caches the plugin's classes, so it goes at release. */
  private Injector injector;
  /**
   * The main class's one instance, from the moment its constructor returns: its own {@code @Inject}
   * members, and whatever they build, may already ask for it, and only this object is the plugin that
   * {@code EventManager.register} and {@code PluginManager.fromInstance} recognise.
   */
  private volatile Object plugin;

  VelocityPluginHandle(VelocityEnvironment environment, VelocityPluginHost.Description description, VelocityClassLoader loader, Class<?> mainClass) {
    this.environment = environment;
    this.velocityDescription = description;
    this.loader = loader;
    this.mainClass = mainClass;
  }

  @Override public void onLoad() {
    VelocityPluginHost.Container created = new VelocityPluginHost.Container(velocityDescription, loader, this);
    container = created;
    environment.plugins.add(created);
    try {
      created.instance = construct(created, mainClass, 0);
      // Velocity registers a plugin's main class as a listener without being asked.
      environment.events.register(created, created.instance);
    } catch (RuntimeException | LinkageError failed) {
      release();
      throw failed;
    }
  }

  /**
   * A Velocity plugin cannot be disabled on Velocity; the proxy stopping is its only ending, so it
   * gets its ProxyShutdownEvent here if the proxy has not already sent it one.
   */
  @Override public void onDisable() {
    VelocityPluginHost.Container current = container;
    if (current == null) return;
    if (!current.shutdownDelivered) {
      current.shutdownDelivered = true;
      environment.await(environment.events.fireTo(current, new ProxyShutdownEvent()), "ProxyShutdownEvent for " + current.id());
    }
    release();
  }

  private void release() {
    VelocityPluginHost.Container current = container;
    if (current == null) return;
    container = null;
    environment.events.unregisterListeners(current);
    environment.commands.forget(current);
    environment.scheduler.forget(current);
    // Gone from the plugin list first: a player's permission setup still in flight checks that list
    // before keeping this plugin's function, and must not find it between the release and the removal.
    environment.plugins.remove(current);
    environment.permissions.release(current);
    environment.channels.release(current);
    current.shutdownExecutor();
    synchronized (this) { injector = null; }
    plugin = null;
  }

  /** Builds {@code type} as Guice would a class with an @Inject constructor: arguments, then @Inject fields and methods. */
  private Object construct(VelocityPluginHost.Container container, Class<?> type, int depth) {
    if (depth > 8) throw new IllegalStateException("injection of " + type.getName() + " nests too deeply (a cycle?)");
    Constructor<?> constructor = constructor(type);
    try {
      constructor.setAccessible(true);
      Object[] arguments = new Object[constructor.getParameterCount()];
      for (int index = 0; index < arguments.length; index++) {
        arguments[index] = resolve(container, constructor.getParameterTypes()[index], constructor.getParameterAnnotations()[index], depth);
      }
      Object instance = constructor.newInstance(arguments);
      if (depth == 0 && type == mainClass) plugin = instance;
      // As Guice does: superclass members first, fields before methods; final instance fields are
      // set too, static members are left alone.
      List<Class<?>> hierarchy = new ArrayList<>();
      for (Class<?> declaring = type; declaring != null && declaring != Object.class; declaring = declaring.getSuperclass()) hierarchy.add(0, declaring);
      for (Class<?> declaring : hierarchy) {
        for (Field field : declaring.getDeclaredFields()) {
          if (!injectable(field) || Modifier.isStatic(field.getModifiers())) continue;
          field.setAccessible(true);
          field.set(instance, resolve(container, field.getType(), field.getAnnotations(), depth));
        }
        for (Method method : declaring.getDeclaredMethods()) {
          if (!injectable(method) || Modifier.isStatic(method.getModifiers())) continue;
          Object[] values = new Object[method.getParameterCount()];
          for (int index = 0; index < values.length; index++) {
            values[index] = resolve(container, method.getParameterTypes()[index], method.getParameterAnnotations()[index], depth);
          }
          method.setAccessible(true);
          method.invoke(instance, values);
        }
      }
      return instance;
    } catch (InvocationTargetException thrown) {
      throw new IllegalStateException("Velocity plugin " + container.id() + ": " + type.getName() + " failed while being built: " + thrown.getCause(), thrown.getCause());
    } catch (ReflectiveOperationException failed) {
      throw new IllegalStateException("Velocity plugin " + container.id() + ": " + type.getName() + " could not be constructed: " + failed, failed);
    }
  }

  /** The @Inject constructor; else the only constructor; else the no-argument one. */
  private static Constructor<?> constructor(Class<?> type) {
    List<Constructor<?>> marked = new ArrayList<>();
    for (Constructor<?> candidate : type.getDeclaredConstructors()) if (injectable(candidate)) marked.add(candidate);
    if (marked.size() == 1) return marked.get(0);
    if (marked.size() > 1) throw new IllegalStateException(type.getName() + " has more than one @Inject constructor");
    Constructor<?>[] all = type.getDeclaredConstructors();
    if (all.length == 1) return all[0];
    for (Constructor<?> candidate : all) if (candidate.getParameterCount() == 0) return candidate;
    throw new IllegalStateException(type.getName() + " has several constructors and none is marked @Inject");
  }

  private Object resolve(VelocityPluginHost.Container container, Class<?> type, Annotation[] annotations, int depth) {
    Object service = services(container).get(type);
    if (service != null) return service;
    if (type == Path.class) {
      for (Annotation annotation : annotations) if (annotation instanceof DataDirectory) return dataDirectory();
      throw new IllegalStateException("a Path is injected only with @DataDirectory");
    }
    if (type == Injector.class) return injector(container);
    if (type == mainClass) return plugin();
    // The plugin's own concrete classes are built on demand, as Guice does: bStats' Metrics.Factory,
    // which almost every plugin bundles, is injected this way.
    if (type.getClassLoader() instanceof VelocityClassLoader && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
      return construct(container, type, depth + 1);
    }
    throw new IllegalStateException("cannot inject " + type.getName() + " into Velocity plugin " + container.id()
        + "; Conduit injects ProxyServer, org.slf4j.Logger, ComponentLogger, java.util.logging.Logger, @DataDirectory Path, PluginContainer,"
        + " PluginDescription, EventManager, CommandManager, PluginManager, Scheduler, Guice's Injector and the plugin's own concrete classes");
  }

  /** What a plugin can be given, by type; @DataDirectory Path and the Injector come on top. */
  private Map<Class<?>, Object> services(VelocityPluginHost.Container container) {
    // Both loggers through slf4j, which the proxy binds to java.util.logging: the plugin's own Conduit logger.
    return Map.of(ProxyServer.class, environment.proxy,
        org.slf4j.Logger.class, org.slf4j.LoggerFactory.getLogger(getLogger().getName()),
        ComponentLogger.class, ComponentLogger.logger(getLogger().getName()),
        java.util.logging.Logger.class, getLogger(),
        PluginContainer.class, container,
        com.velocitypowered.api.plugin.PluginDescription.class, container.getDescription(),
        EventManager.class, environment.events,
        CommandManager.class, environment.commands,
        PluginManager.class, environment.plugins,
        Scheduler.class, environment.scheduler);
  }

  /**
   * One Guice injector per plugin, bound to what {@link #resolve} offers. Guice builds
   * whatever the plugin asks of it, or of a child injector with the plugin's own modules; Conduit's
   * own injection of the main class stays as it is.
   */
  private synchronized Injector injector(VelocityPluginHost.Container container) {
    if (injector == null) {
      Map<Class<?>, Object> services = services(container);
      Path data = dataDirectory();
      injector = Guice.createInjector(new AbstractModule() {
        @SuppressWarnings("unchecked")
        @Override protected void configure() {
          for (var service : services.entrySet()) {
            // Every Guice injector has its own java.util.logging.Logger binding, which cannot be replaced.
            if (service.getKey() != java.util.logging.Logger.class) bind((Class<Object>) service.getKey()).toInstance(service.getValue());
          }
          bind(Path.class).annotatedWith(DataDirectory.class).toInstance(data);
          // Through a provider, not toInstance: Guice injects the members of an instance it is bound
          // to, and the plugin's were injected once already.
          bind((Class<Object>) mainClass).toProvider((com.google.inject.Provider<Object>) VelocityPluginHandle.this::plugin);
        }
      });
    }
    return injector;
  }

  /** The plugin's main instance, for anything that injects the main class. */
  private Object plugin() {
    Object instance = plugin;
    if (instance == null) {
      throw new IllegalStateException("Velocity plugin " + velocityDescription.getId() + ": " + mainClass.getName()
          + " is asked for while its own constructor is still running, so there is no instance to give yet");
    }
    return instance;
  }

  /** By name: plugins mark injection with javax, jakarta or Guice's @Inject. */
  private static boolean injectable(AnnotatedElement element) {
    for (Annotation annotation : element.getDeclaredAnnotations()) if (INJECT.contains(annotation.annotationType().getName())) return true;
    return false;
  }
}
