package gg.tame.conduit.network;

import java.util.concurrent.ThreadFactory;

/**
 * Every thread Conduit starts that may block on a socket: virtual, except on Windows.
 *
 * <p>On Windows the JDK parks a virtual thread's socket read and its socket write through two
 * separate wepoll handles, and a readiness event can surface on the wrong handle and be dropped
 * (JDK-8334574, still open in JDK 25). A session reads each of its sockets on one thread and
 * writes it from another, so a write that had to wait could wait forever while the reader sat
 * parked on the same socket. A real NeoForge 20.2.93 join through Conduit stalled that way about
 * one time in two: the backend reader parked in a client write that never woke, the client
 * starved. A platform thread blocks in the operating system instead and never reaches that poller.
 */
public final class SocketThreads {
  // ponytail: a platform thread per socket-blocking task on Windows costs a real stack each; return
  // to virtual threads everywhere once JDK-8334574 is fixed in the JDK Conduit ships on.
  private static final ThreadFactory FACTORY = System.getProperty("os.name", "").startsWith("Windows")
      ? Thread.ofPlatform().name("conduit-io-", 0).daemon(true).factory()
      : Thread.ofVirtual().factory();

  private SocketThreads() { }

  public static ThreadFactory factory() { return FACTORY; }

  public static Thread start(Runnable task) {
    Thread thread = FACTORY.newThread(task);
    thread.start();
    return thread;
  }
}
