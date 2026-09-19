// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.log;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * Turns on ANSI colour in a Windows console that has it switched off.
 *
 * <p>Windows consoles understand ANSI escapes, but a process has to ask for it: until someone calls
 * {@code SetConsoleMode} with {@code ENABLE_VIRTUAL_TERMINAL_PROCESSING}, the console prints the
 * escapes as text, and a coloured log line arrives as {@code <ESC>[97m[12:34:56 CST INFO]: ...}.
 * Windows Terminal asks on its own behalf; the console host you get from double-clicking a .bat
 * does not, which is where the junk came from.
 *
 * <p>A pure-Java process could not make that call until the foreign-function API, which is final in
 * Java 22. Conduit compiles against 21, so the three kernel32 calls are made reflectively: on 21
 * the lookup fails and colour is simply left off, and from 22 up -- which is what anyone running
 * this actually has -- the console is switched over and the colours work in plain {@code cmd.exe}.
 *
 * <p>Every failure here is silent and means "no colour". Nothing about logging is worth a stack
 * trace on startup, let alone one from a console-mode call.
 */
final class WindowsConsole {

  /** GetStdHandle's argument for stdout and stderr; both carry log lines, so both are switched. */
  private static final int STD_OUTPUT_HANDLE = -11;
  private static final int STD_ERROR_HANDLE = -12;
  private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

  private WindowsConsole() {}

  /** True when both console streams will now render ANSI escapes. */
  static boolean enableVirtualTerminal() {
    try {
      Class<?> arenaClass = Class.forName("java.lang.foreign.Arena");
      Object arena = arenaClass.getMethod("ofConfined").invoke(null);
      try {
        return switchOver(arena, arenaClass);
      } finally {
        ((AutoCloseable) arena).close();
      }
    } catch (Throwable unavailable) {
      // Java 21, a stripped runtime, a security manager, or a kernel32 that would not load.
      return false;
    }
  }

  private static boolean switchOver(Object arena, Class<?> arenaClass) throws Throwable {
    Class<?> linkerClass = Class.forName("java.lang.foreign.Linker");
    Class<?> lookupClass = Class.forName("java.lang.foreign.SymbolLookup");
    Class<?> segmentClass = Class.forName("java.lang.foreign.MemorySegment");
    Class<?> layoutClass = Class.forName("java.lang.foreign.MemoryLayout");
    Class<?> descriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor");
    Class<?> valueLayoutClass = Class.forName("java.lang.foreign.ValueLayout");
    Class<?> intLayoutClass = Class.forName("java.lang.foreign.ValueLayout$OfInt");

    Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
    Object kernel32 = lookupClass.getMethod("libraryLookup", String.class, arenaClass)
        .invoke(null, "kernel32.dll", arena);
    Method find = lookupClass.getMethod("find", String.class);
    Method downcall = linkerClass.getMethod("downcallHandle", segmentClass, descriptorClass,
        Class.forName("[Ljava.lang.foreign.Linker$Option;"));

    Object addressLayout = valueLayoutClass.getField("ADDRESS").get(null);
    Object intLayout = valueLayoutClass.getField("JAVA_INT").get(null);
    Method descriptorOf = descriptorClass.getMethod("of", layoutClass,
        java.lang.reflect.Array.newInstance(layoutClass, 0).getClass());

    MethodHandle getStdHandle = handle(downcall, linker, find, kernel32, "GetStdHandle",
        descriptorOf, addressLayout, new Object[] {intLayout});
    MethodHandle getConsoleMode = handle(downcall, linker, find, kernel32, "GetConsoleMode",
        descriptorOf, intLayout, new Object[] {addressLayout, addressLayout});
    MethodHandle setConsoleMode = handle(downcall, linker, find, kernel32, "SetConsoleMode",
        descriptorOf, intLayout, new Object[] {addressLayout, intLayout});

    Object modeBuffer = arenaClass.getMethod("allocate", long.class).invoke(arena, 4L);
    Method readInt = segmentClass.getMethod("get", intLayoutClass, long.class);

    // Both streams must take it: warnings and errors go to stderr, everything else to stdout, and
    // colouring only one of them is how you end up with escapes in half the log.
    return switchStream(STD_OUTPUT_HANDLE, getStdHandle, getConsoleMode, setConsoleMode,
            modeBuffer, readInt, intLayout)
        & switchStream(STD_ERROR_HANDLE, getStdHandle, getConsoleMode, setConsoleMode,
            modeBuffer, readInt, intLayout);
  }

  private static boolean switchStream(int stream, MethodHandle getStdHandle,
      MethodHandle getConsoleMode, MethodHandle setConsoleMode, Object modeBuffer,
      Method readInt, Object intLayout) throws Throwable {
    Object handle = getStdHandle.invokeWithArguments(stream);
    // A redirected stream is not a console and has no mode to set; that is not a failure, but it
    // is also not something to claim colour for.
    if ((int) getConsoleMode.invokeWithArguments(handle, modeBuffer) == 0) return false;
    int mode = (int) readInt.invoke(modeBuffer, intLayout, 0L);
    if ((mode & ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0) return true;
    return (int) setConsoleMode.invokeWithArguments(handle,
        mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0;
  }

  /** One kernel32 function, looked up and bound to its signature. */
  private static MethodHandle handle(Method downcall, Object linker, Method find, Object lookup,
      String name, Method descriptorOf, Object returns, Object[] takes) throws Throwable {
    Object symbol = ((java.util.Optional<?>) find.invoke(lookup, name)).orElseThrow();
    Object layouts = java.lang.reflect.Array.newInstance(
        Class.forName("java.lang.foreign.MemoryLayout"), takes.length);
    for (int i = 0; i < takes.length; i++) java.lang.reflect.Array.set(layouts, i, takes[i]);
    Object descriptor = descriptorOf.invoke(null, returns, layouts);
    Object options = java.lang.reflect.Array.newInstance(
        Class.forName("java.lang.foreign.Linker$Option"), 0);
    return (MethodHandle) downcall.invoke(linker, symbol, descriptor, options);
  }
}
