package gg.tame.conduit.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a public one-argument method taking an {@link Event} (or a supertype of one) as a listener. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
  /**
   * When this listener runs among all listeners for the event: every FIRST one before any EARLY
   * one, and so on; registration order within the same slot. LAST is for listeners that act on
   * the final outcome of a cancellable event and should not change it.
   */
  Order order() default Order.NORMAL;

  enum Order { FIRST, EARLY, NORMAL, LATE, LAST }
}
