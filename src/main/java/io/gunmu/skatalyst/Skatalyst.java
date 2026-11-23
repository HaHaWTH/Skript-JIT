package io.gunmu.skatalyst;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.log.SkriptLogger;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import io.gunmu.skatalyst.compiler.DirectFunction;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.common.function.Signature;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Supplier;

/** Owns hotness, compilation and the transition between generated and interpreted code. */
@ApiStatus.Internal
public final class Skatalyst {

	private static final int CALL_THRESHOLD = 128;
	private static final int BACKEDGE_THRESHOLD = 1024;
	private static final int QUEUE_CAPACITY = 64;
	private static final Object REJECTED = new Object();

	private static final Object LIFECYCLE = new Object();
	private static final Map<Scope, Set<Site>> TRACKED = new HashMap<>();
	private static final Set<Event> ESCAPED_LOCALS =
		Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static @Nullable ThreadPoolExecutor worker;
	private static volatile boolean enabled = true;
	private static boolean caseInsensitive = true;

	private Skatalyst() {}

	/** One lifetime, including the pending compilation jobs, of a loaded script instance. */
	public static final class Scope {
		private enum Stage {
			LOADING,
			ACTIVE,
			CLOSED
		}

		private volatile Stage stage = Stage.LOADING;

		public void activate() {
			synchronized (LIFECYCLE) {
				if (stage == Stage.LOADING)
					stage = Stage.ACTIVE;
			}
		}

		public void close() {
			synchronized (LIFECYCLE) {
				stage = Stage.CLOSED;
				Set<Site> sites = TRACKED.remove(this);
				if (sites != null)
					sites.forEach(Skatalyst::reset);
			}
		}
	}

	/** The runtime state associated with an individual trigger. */
	public static final class Site {
		private static final AtomicIntegerFieldUpdater<Site> CALLS =
			AtomicIntegerFieldUpdater.newUpdater(Site.class, "calls");
		private static final AtomicIntegerFieldUpdater<Site> BACKEDGES =
			AtomicIntegerFieldUpdater.newUpdater(Site.class, "backedges");

		private final Trigger trigger;
		private final @Nullable Scope scope;
		private @Nullable Signature<?> functionSignature;

		private volatile int calls;
		private volatile int backedges;

		// null -> queued job -> generated code or REJECTED. Lifecycle resets invalidate the job identity.
		private volatile @Nullable Object state;

		public Site(Trigger trigger, @Nullable Scope scope) {
			this.trigger = trigger;
			this.scope = scope;
		}

		public void setFunctionSignature(Signature<?> signature) {
			this.functionSignature = signature;
		}

		public @Nullable Signature<?> functionSignature() {
			return functionSignature;
		}

		public @Nullable DirectFunction directFunction() {
			if (!enabled || SkriptLogger.debug() || scope == null || scope.stage != Scope.Stage.ACTIVE
					|| trigger.getActualNext() != null)
				return null;
			CompiledCode compiled = code();
			return compiled == null ? null : compiled.directFunction();
		}

		private @Nullable CompiledCode code() {
			return state instanceof CompiledCode compiled ? compiled : null;
		}

		public boolean walk(TriggerItem start, Event event) {
			CompiledCode code = code();
			TriggerItem current = start;
			try {
				while (current != null) {
					if (code != null) {
						CompiledCode.Entry entry = code.entry(current);
						if (entry != null) {
							current = entry.walk(event);
							code = code();
							continue;
						}
					}

					TriggerItem next = TriggerItem.skatalystStep(current, event);
					if (code == null && next instanceof LoopSection loop && isBackedge(current, loop)) {
						if (state == null && BACKEDGES.incrementAndGet(this) == BACKEDGE_THRESHOLD)
							submit(this);
						code = code();
					} else if (code != null) {
						code = code();
					}
					current = next;
				}
				return true;
			} catch (CompiledCode.WalkFailure failure) {
				return TriggerItem.skatalystReportFailure(start, failure.item(), failure.failure());
			} catch (Throwable failure) {
				return TriggerItem.skatalystReportFailure(start, current, failure);
			}
		}
	}

	public static @Nullable Site enter(@Nullable TriggerItem start, Event event) {
		if (start == null)
			return null;
		// An asynchronous trigger can schedule a synchronous section on this same event.
		if (Bukkit.getServer() != null && !Bukkit.isPrimaryThread())
			exposeLocals(event);
		if (!enabled || SkriptLogger.debug())
			return null;

		Trigger trigger = start.getTrigger();
		if (trigger == null)
			return null;

		Site site = trigger.skatalystSite();
		Scope.Stage stage = site.scope == null ? Scope.Stage.LOADING : site.scope.stage;
		Object state = site.state;
		if (stage == Scope.Stage.CLOSED || state == REJECTED)
			return null;
		if (stage == Scope.Stage.LOADING)
			return state instanceof CompiledCode ? site : null;

		if (state == null && Site.CALLS.incrementAndGet(site) == CALL_THRESHOLD)
			submit(site);
		return site;
	}

	/** Allows future reads through the original event to observe each write. */
	public static void exposeLocals(Event event) {
		ESCAPED_LOCALS.add(event);
	}

	public static boolean localsEscaped(Event event) {
		return ESCAPED_LOCALS.contains(event);
	}

	public static void configure(boolean enabled, boolean caseInsensitive) {
		synchronized (LIFECYCLE) {
			if (Skatalyst.enabled == enabled && Skatalyst.caseInsensitive == caseInsensitive)
				return;
			Skatalyst.enabled = enabled;
			Skatalyst.caseInsensitive = caseInsensitive;
			clear();
		}
	}

	public static void shutdown() {
		synchronized (LIFECYCLE) {
			enabled = false;
			clear();
			if (worker != null) {
				worker.shutdownNow();
				worker = null;
			}
		}
	}

	private static boolean isBackedge(TriggerItem previous, LoopSection loop) {
		if (previous == loop)
			return true;
		for (TriggerSection parent = previous.getParent(); parent != null; parent = parent.getParent()) {
			if (parent == loop)
				return true;
		}
		return false;
	}

	private static void submit(Site site) {
		synchronized (LIFECYCLE) {
			if (!enabled || site.scope == null || site.scope.stage != Scope.Stage.ACTIVE || site.state != null)
				return;

			if (worker == null) {
				worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
						Thread thread = new Thread(task, "Skatalyst compiler");
						thread.setDaemon(true);
						return thread;
					});
			}
			Job job = claim(site);
			try {
				worker.execute(job);
			} catch (RejectedExecutionException full) {
				invalidate(site);
			}
		}
	}

	private static Job claim(Site site) {
		Job job = new Job(site);
		TRACKED.computeIfAbsent(site.scope, scope -> new HashSet<>()).add(site);
		site.state = job;
		return job;
	}

	private static void clear() {
		TRACKED.values().forEach(sites -> sites.forEach(Skatalyst::reset));
		TRACKED.clear();
	}

	private static void reset(Site site) {
		if (site.state instanceof Job job && worker != null)
			worker.remove(job);
		Site.CALLS.set(site, 0);
		Site.BACKEDGES.set(site, 0);
		site.state = null;
	}

	private static void invalidate(Site site) {
		Set<Site> sites = TRACKED.get(site.scope);
		if (sites != null && sites.remove(site) && sites.isEmpty())
			TRACKED.remove(site.scope);
		reset(site);
	}

	static void invalidate(Trigger trigger) {
		synchronized (LIFECYCLE) {
			invalidate(trigger.skatalystSite());
		}
	}

	static @Nullable CompiledCode compiledCode(Trigger trigger) {
		return trigger.skatalystSite().code();
	}

	static boolean forceCompile(Trigger trigger) {
		return forceCompile(trigger, () -> BytecodeCompiler.compile(trigger));
	}

	static boolean forceCompile(Trigger trigger, Supplier<CompiledCode> compiler) {
		Job job;
		synchronized (LIFECYCLE) {
			Site site = trigger.skatalystSite();
			if (!enabled || (site.scope != null && site.scope.stage == Scope.Stage.CLOSED))
				return false;
			reset(site);
			job = claim(site);
		}
		return job.compile(compiler);
	}

	private record Job(Site site) implements Runnable {
		@Override
		public void run() {
			compile(() -> BytecodeCompiler.compile(site.trigger));
		}

		private boolean compile(Supplier<CompiledCode> compiler) {
			if (site.state != this)
				return false;

			CompiledCode code = null;
			try {
				code = compiler.get();
			} catch (RuntimeException | LinkageError failure) {
				Skript.debug("Skatalyst compilation failed for " + site.trigger.getDebugLabel() + ": " + failure);
			} finally {
				synchronized (LIFECYCLE) {
					if (site.state == this)
						site.state = code == null || code.isEmpty() ? REJECTED : code;
				}
			}
			return code != null && site.state == code;
		}
	}
}
