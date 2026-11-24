package io.gunmu.skatalyst.api;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SyntaxElement;
import io.gunmu.skatalyst.addon.AddonRegistry;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.addon.SkriptAddon;

import java.util.Objects;
import java.util.function.Function;

/**
 * Registers Skatalyst capabilities scoped to an existing Skript addon.
 * A new plugin instance can register from its first {@code onEnable}. A disable event revokes
 * that instance's registrations and closes admission until its next {@code PluginEnableEvent}.
 */
public final class SkatalystAddonApi {

	private final SkriptAddon addon;

	private SkatalystAddonApi(SkriptAddon addon) {
		this.addon = Objects.requireNonNull(addon);
	}

	public static SkatalystAddonApi forAddon(SkriptAddon addon) {
		return new SkatalystAddonApi(addon);
	}

	/**
	 * Allows Skatalyst to use the annotation on the exact registered syntax class.
	 */
	public Registration registerAnnotated(Class<? extends SyntaxElement> syntax) {
		return AddonRegistry.registerAnnotated(addon, syntax);
	}

	/**
	 * Registers semantics and an optional exact numeric replacement. The factory is invoked on the script
	 * loading thread after initialization and simplification. It may return null for unsupported shapes.
	 */
	public <E extends Expression<?>> Registration registerAnnotated(
		Class<E> syntax, Function<? super E, @Nullable NumericIntrinsic> factory) {
		return AddonRegistry.registerAnnotated(addon, syntax, Objects.requireNonNull(factory));
	}

	/**
	 * Revokes this registration for subsequently admitted calls of the original or replacement path.
	 */
	public interface Registration extends AutoCloseable {
		@Override
		void close();
	}
}
