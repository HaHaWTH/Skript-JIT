package io.gunmu.skatalyst.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes how the registered syntax's original implementation uses the current invocation.
 * The declaration covers the original runtime entry points the syntax implements, including changing
 * an expression when supported. It applies to the exact class and is activated by explicit registration.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SkatalystSemantics {

	/**
	 * Access through asynchronous work, callbacks, or retaining the local frame counts as escape.
	 */
	enum EventEscape {
		UNKNOWN,
		NO_ESCAPE
	}

	/**
	 * Direct and indirect access to Skript locals, excluding operands whose evaluation is tracked separately.
	 */
	enum Locals {
		UNKNOWN,
		NONE,
		READ,
		WRITE
	}

	EventEscape eventEscape() default EventEscape.UNKNOWN;

	Locals locals() default Locals.UNKNOWN;

	boolean mayReenter() default true;

	boolean maySuspend() default true;
}
