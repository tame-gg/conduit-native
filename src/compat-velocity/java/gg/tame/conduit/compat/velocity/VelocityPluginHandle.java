package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
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
import java.util.Set;

/**
 * A Velocity plugin as Conduit's plugin manager sees it. Everything the Velocity plugin registers
 * natively (commands, scheduled tasks) is owned by this handle, so Conduit releases it on disable.
 *
 * <p>Injection is Conduit's own, not Guice: one constructor, then {@code @Inject} fields and methods, each
 * resolved by type from the list in {@link #resolve}, or, for the plugin's own concrete classes,
 * built the same way. Anything else fails the load, naming the type, rather than being left null.
 */
final class VelocityPluginHandle extends ConduitPlugin {
  private static final Set<String> INJECT = Set.of("javax.inject.Inject", "jakarta.inject.Inject", "com.google.inject.Inject");
  private final VelocityEnvironment environment;
  private final VelocityPluginHost.Description velocityDescription;
  private final VelocityClassLoader loader;
  private final Class<?> mainClass;
  private volatile VelocityPluginHost.Container container;

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
    environment.plugins.remove(current);
    current.shutdownExecutor();
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
    if (type == ProxyServer.class) return environment.proxy;
    // Both through slf4j, which the proxy binds to java.util.logging: the plugin's own Conduit logger.
    if (type == org.slf4j.Logger.class) return org.slf4j.LoggerFactory.getLogger(getLogger().getName());
    if (type == ComponentLogger.class) return ComponentLogger.logger(getLogger().getName());
    if (type == java.util.logging.Logger.class) return getLogger();
    if (type == Path.class) {
      for (Annotation annotation : annotations) if (annotation instanceof DataDirectory) return dataDirectory();
      throw new IllegalStateException("a Path is injected only with @DataDirectory");
    }
    if (type == PluginContainer.class) return container;
    if (type == com.velocitypowered.api.plugin.PluginDescription.class) return container.getDescription();
    if (type == EventManager.class) return environment.events;
    if (type == CommandManager.class) return environment.commands;
    if (type == PluginManager.class) return environment.plugins;
    // The plugin's own concrete classes are built on demand, as Guice does: bStats' Metrics.Factory,
    // which almost every plugin bundles, is injected this way.
    if (type.getClassLoader() instanceof VelocityClassLoader && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
      return construct(container, type, depth + 1);
    }
    throw new IllegalStateException("cannot inject " + type.getName() + " into Velocity plugin " + container.id()
        + "; Conduit injects ProxyServer, org.slf4j.Logger, ComponentLogger, java.util.logging.Logger, @DataDirectory Path, PluginContainer,"
        + " PluginDescription, EventManager, CommandManager, PluginManager and the plugin's own concrete classes (there is no Guice)");
  }

  /** By name: plugins mark injection with javax, jakarta or Guice's @Inject, and only the first ships here. */
  private static boolean injectable(AnnotatedElement element) {
    for (Annotation annotation : element.getDeclaredAnnotations()) if (INJECT.contains(annotation.annotationType().getName())) return true;
    return false;
  }
}
