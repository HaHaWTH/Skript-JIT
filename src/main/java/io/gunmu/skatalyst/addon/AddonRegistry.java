package io.gunmu.skatalyst.addon;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SyntaxElement;
import com.google.common.collect.MapMaker;
import io.gunmu.skatalyst.Skatalyst;
import io.gunmu.skatalyst.addon.compat.SkBeeProfile;
import io.gunmu.skatalyst.api.NumericIntrinsic;
import io.gunmu.skatalyst.api.SkatalystAddonApi.Registration;
import io.gunmu.skatalyst.api.SkatalystSemantics;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.docs.Origin;
import org.skriptlang.skript.registration.SyntaxInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@ApiStatus.Internal
public final class AddonRegistry {

	private static final Object LOCK = new Object();
	private static final Map<Class<?>, Contribution> CONTRIBUTIONS = new HashMap<>();
	private static final Set<Plugin> DISABLED = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());
	private static final Map<SyntaxInfo<?>, SyntaxRegistration> SYNTAX = new MapMaker().weakKeys().makeMap();
	private static final ClassValue<Boolean> EXTERNAL = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return type.getClassLoader() != AddonRegistry.class.getClassLoader()
				|| type.isAnnotationPresent(SkatalystSemantics.class);
		}
	};
	private static volatile long generation;

	private AddonRegistry() {
	}

	public static long generation() {
		return generation;
	}

	public static Registration registerAnnotated(SkriptAddon addon, Class<? extends SyntaxElement> syntax) {
		return registerAnnotated(owner(addon, syntax), addon, syntax, element -> null);
	}

	public static <E extends Expression<?>> Registration registerAnnotated(
		SkriptAddon addon, Class<E> syntax, Function<? super E, @Nullable NumericIntrinsic> factory) {
		return registerAnnotated(owner(addon, syntax), addon, syntax, element -> factory.apply(syntax.cast(element)));
	}

	private static Plugin owner(SkriptAddon addon, Class<?> syntax) {
		Plugin plugin = JavaPlugin.getProvidingPlugin(addon.source());
		if (JavaPlugin.getProvidingPlugin(syntax) != plugin) {
			throw new IllegalArgumentException("The syntax must belong to the Skript addon registering its capabilities");
		}
		return plugin;
	}

	static Registration registerAnnotated(
		Plugin plugin, SkriptAddon addon, Class<? extends SyntaxElement> syntax,
		Function<SyntaxElement, @Nullable NumericIntrinsic> factory) {
		SkatalystSemantics semantics = syntax.getDeclaredAnnotation(SkatalystSemantics.class);
		if (semantics == null) {
			throw new IllegalArgumentException(syntax.getName() + " must declare @SkatalystSemantics");
		}

		boolean preservesLocals = semantics.eventEscape() == SkatalystSemantics.EventEscape.NO_ESCAPE
			&& semantics.locals() != SkatalystSemantics.Locals.UNKNOWN
			&& !semantics.mayReenter() && !semantics.maySuspend();
		Contribution contribution = new Contribution(plugin, addon, syntax, preservesLocals, false, factory);
		synchronized (LOCK) {
			if (DISABLED.contains(plugin) || !plugin.isEnabled()) {
				throw new IllegalStateException("The Skript addon must be enabled to register Skatalyst capabilities");
			}
			if (!installLocked(contribution)) {
				throw new IllegalArgumentException("Duplicate Skatalyst registration for " + syntax.getName());
			}
		}
		Skatalyst.addonRegistryChanged();
		return () -> close(contribution);
	}

	private static boolean installLocked(Contribution contribution) {
		if (CONTRIBUTIONS.putIfAbsent(contribution.syntax, contribution) != null) return false;
		generation++;
		return true;
	}

	private static void close(Contribution contribution) {
		synchronized (LOCK) {
			if (!CONTRIBUTIONS.remove(contribution.syntax, contribution)) {
				return;
			}
			contribution.activity.revoke();
			generation++;
		}
		Skatalyst.addonRegistryChanged();
	}

	public static void syntaxRegistered(SyntaxInfo<?> info) {
		if (!isExternal(info.type())) {
			return;
		}
		synchronized (LOCK) {
			SYNTAX.computeIfAbsent(info, ignored -> new SyntaxRegistration()).references++;
		}
	}

	public static void syntaxUnregistered(SyntaxInfo<?> info) {
		boolean changed;
		synchronized (LOCK) {
			SyntaxRegistration registration = SYNTAX.get(info);
			if (registration == null || --registration.references != 0) {
				return;
			}
			SYNTAX.remove(info);
			changed = registration.inUse;
			registration.activity.revoke();
			if (changed) {
				generation++;
			}
		}
		if (changed) {
			Skatalyst.addonRegistryChanged();
		}
	}

	static boolean isExternal(Class<?> type) {
		return EXTERNAL.get(type);
	}

	static @Nullable Match lookup(SyntaxInfo<?> info, SyntaxElement element) {
		if (element.getClass() != info.type()) {
			return null;
		}
		synchronized (LOCK) {
			Contribution contribution = CONTRIBUTIONS.get(info.type());
			SyntaxRegistration registration = SYNTAX.get(info);
			if (contribution == null || registration == null || !contribution.matchesOrigin(info.origin())) {
				return null;
			}
			registration.inUse = true;
			return new Match(contribution, registration.activity);
		}
	}

	public static void onPluginEnable(Plugin plugin) {
		Class<? extends Expression<?>> nbt = SkBeeProfile.syntax(plugin);
		synchronized (LOCK) {
			// A plugin can disable itself and return normally from onEnable.
			if (!plugin.isEnabled()) return;
			DISABLED.remove(plugin);
			if (nbt == null || !installLocked(new Contribution(plugin, null, nbt, false, true, element -> null))) return;
		}
		Skatalyst.addonRegistryChanged();
	}

	public static void onPluginDisable(Plugin plugin) {
		boolean changed;
		synchronized (LOCK) {
			// Paper delivers the disable event while Plugin.isEnabled() is still true.
			DISABLED.add(plugin);
			changed = CONTRIBUTIONS.values().removeIf(contribution -> {
				if (contribution.plugin != plugin) {
					return false;
				}
				contribution.activity.revoke();
				return true;
			});
			if (changed) {
				generation++;
			}
		}
		if (changed) {
			Skatalyst.addonRegistryChanged();
		}
	}

	static final class Activity {
		private volatile boolean active = true;
		private @Nullable Map<ParsedBinding.NumericCall, Boolean> numeric;

		boolean isActive() {
			return active;
		}

		synchronized void attach(ParsedBinding.NumericCall call) {
			if (!active) {
				call.detach();
				return;
			}
			if (numeric == null) {
				numeric = new MapMaker().weakKeys().makeMap();
			}
			numeric.put(call, Boolean.TRUE);
		}

		synchronized void revoke() {
			active = false;
			if (numeric != null) {
				numeric.keySet().forEach(ParsedBinding.NumericCall::detach);
				numeric = null;
			}
		}
	}

	private static final class SyntaxRegistration {
		private final Activity activity = new Activity();
		private int references;
		private boolean inUse;
	}

	private record Contribution(
		Plugin plugin, @Nullable SkriptAddon addon, Class<? extends SyntaxElement> syntax, boolean preservesLocals,
		boolean skBeeGetter, Function<SyntaxElement, @Nullable NumericIntrinsic> factory, Activity activity) {
		private Contribution(
			Plugin plugin, @Nullable SkriptAddon addon, Class<? extends SyntaxElement> syntax, boolean preservesLocals,
			boolean skBeeGetter, Function<SyntaxElement, @Nullable NumericIntrinsic> factory) {
			this(plugin, addon, syntax, preservesLocals, skBeeGetter, factory, new Activity());
		}

		private boolean matchesOrigin(Origin origin) {
			return addon == null || (origin instanceof Origin.AddonOrigin declared
				&& declared.addon().source() == addon.source() && declared.addon().name().equals(addon.name()));
		}
	}

	record Match(Contribution contribution, Activity syntax) {
		boolean preservesLocals() {
			return contribution.preservesLocals;
		}

		boolean skBeeGetter() {
			return contribution.skBeeGetter;
		}

		Activity provider() {
			return contribution.activity;
		}

		@Nullable NumericIntrinsic intrinsic(SyntaxElement element) {
			if (!provider().isActive() || !syntax.isActive()) {
				return null;
			}
			try {
				return contribution.factory.apply(element);
			} catch (RuntimeException | LinkageError failure) {
				Skript.debug("Skatalyst capability factory failed for " + contribution.syntax.getName() + ": " + failure);
				return null;
			}
		}
	}
}
