package ch.njol.skript.lang.il;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.config.Config;
import ch.njol.skript.config.Node;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.SyntaxElement;
import ch.njol.skript.lang.function.Signature;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.il.IlFunctionDependencies.Lookup;
import ch.njol.skript.lang.il.IlSyntaxEnvironment.Ordering;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The entry point of the intermediate language (IL) script cache.
 * <p>
 * This class keeps a persistent record of the <i>result</i> of parsing process, the
 * {@link IlNode IL tree}, as it represents the script's AST.
 * <p>
 * The cache is keyed by the SHA-256 hash of the script source file.
 * A fingerprint of the parse environment (Skript build, addons, registered syntaxes and
 * classes, relevant config values, server version) is stored in the cache header as well.
 */
@ApiStatus.Internal
public final class IlManager {

	/**
	 * The parse call sites that use the IL cache.
	 */
	public enum Site {
		SECTION(SyntaxRegistry.SECTION),
		EFFECT_SECTION(SyntaxRegistry.SECTION),
		STATEMENT_SIMPLE(SyntaxRegistry.STATEMENT),
		STATEMENT_SECTION(SyntaxRegistry.STATEMENT),
		CONDITION(SyntaxRegistry.CONDITION),
		EFFECT(SyntaxRegistry.EFFECT);

		final SyntaxRegistry.Key<?> registryKey;

		Site(SyntaxRegistry.Key<?> registryKey) {
			this.registryKey = registryKey;
		}
	}

	/**
	 * A recorded IL tree or an entry that could not be recorded safely.
	 */
	public static final byte STATE_NODE = 0;
	/** Record type used by older cache files for a failed parse. */
	public static final byte STATE_MISS = 1;
	public static final byte STATE_UNSAFE = 2;

	public record Entry(
		byte state,
		@Nullable IlNode node,
		int flags,
		int parseContext,
		String expr,
		@Nullable Ordering ordering,
		List<Lookup> functions
	) {
		Entry(byte state, @Nullable IlNode node, int flags, int parseContext, String expr) {
			this(state, node, flags, parseContext, expr, null, List.of());
		}

		Entry withOrdering(Ordering current) {
			return new Entry(state, node, flags, parseContext, expr, current, functions);
		}
	}

	/**
	 * Counters for diagnostics and tests.
	 */
	public record Stats(
		long cacheLoads,
		long cacheSaves,
		long entriesLoaded,
		long entryHits,
		long replays,
		long replayFailures,
		long uncacheable,
		long conflicts
	) {
	}

	private IlManager() {
	}

	/*
	 * Configuration / toggles
	 */

	private static volatile Boolean enabledOverride = null;

	public static void setEnabledOverride(@Nullable Boolean enabled) {
		enabledOverride = enabled;
	}

	public static boolean enabled() {
		Boolean override = enabledOverride;
		if (override != null)
			return override;
		return SkriptConfig.useScriptParseCache.value();
	}

	/*
	 * Statistics
	 */

	private static final AtomicLong CACHE_LOADS = new AtomicLong();
	private static final AtomicLong CACHE_SAVES = new AtomicLong();
	private static final AtomicLong ENTRIES_LOADED = new AtomicLong();
	private static final AtomicLong ENTRY_HITS = new AtomicLong();
	private static final AtomicLong REPLAYS = new AtomicLong();
	private static final AtomicLong REPLAY_FAILURES = new AtomicLong();
	private static final AtomicLong UNCACHEABLE = new AtomicLong();
	private static final AtomicLong CONFLICTS = new AtomicLong();

	public static Stats stats() {
		return new Stats(CACHE_LOADS.get(),
			CACHE_SAVES.get(),
			ENTRIES_LOADED.get(),
			ENTRY_HITS.get(),
			REPLAYS.get(),
			REPLAY_FAILURES.get(),
			UNCACHEABLE.get(),
			CONFLICTS.get());
	}

	public static void resetStats() {
		CACHE_LOADS.set(0);
		CACHE_SAVES.set(0);
		ENTRIES_LOADED.set(0);
		ENTRY_HITS.set(0);
		REPLAYS.set(0);
		REPLAY_FAILURES.set(0);
		UNCACHEABLE.set(0);
		CONFLICTS.set(0);
	}

	/*
	 * Script lifecycle
	 */

	/**
	 * Prepares the IL cache for a script that is about to be parsed.
	 */
	public static void beginScript(Script script) {
		if (!enabled()) return;
		Config config = script.getConfig();
		String sourceHash = config.getSourceHash();
		if (sourceHash == null) return;
		File file = config.getFile();
		assert file != null;
		try {
			IlSyntaxEnvironment environment = IlSyntaxEnvironment.current();
			if (environment == null) return;
			IlScriptCache cache = IlScriptCache.load(file.toPath(), sourceHash, environment);
			script.addData(cache);
			CACHE_LOADS.incrementAndGet();
			ENTRIES_LOADED.addAndGet(cache.entryCount());
		} catch (Exception e) {
			debug("Could not load IL cache for " + file.getName() + ": " + e);
		}
	}

	/**
	 * Saves and releases the IL cache of a script that has finished loading.
	 */
	public static void finishScript(Script script) {
		IlScriptCache cache = script.getData(IlScriptCache.class);
		if (cache == null) return;
		script.removeData(IlScriptCache.class);
		if (!enabled()) return;
		try {
			if (cache.environment().active() && cache.saveIfDirty()) {
				CACHE_SAVES.incrementAndGet();
			}
		} catch (Exception e) {
			debug("Could not save IL cache for " + script.getConfig().getFileName() + ": " + e);
		}
	}

	public static @Nullable Path cacheDirectory() {
		try {
			File dataFolder = Skript.getInstance().getDataFolder();
			if (dataFolder == null)
				return null;
			return dataFolder.toPath().resolve("il-cache");
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Builds the fingerprint of the current parse environment.
	 * May variant as the JVM flags changes, but this was untested.
	 */
	public static String environmentFingerprint() {
		return IlEnvironment.computeFingerprint();
	}

	/** Invalidates the bound syntax environment when Skript replaces the registry itself. */
	public static void syntaxRegistryReplaced() {
		IlSyntaxEnvironment.registryReplaced();
	}

	/*
	 * Recording sessions
	 */

	/**
	 * The state of a single recorded parse call.
	 */
	static final class Session {
		final IlSyntaxEnvironment environment;
		final int replayDepth;
		final Map<Object, IlNode> nodes = new java.util.IdentityHashMap<>();
		@Nullable Set<Lookup> functions;
		boolean uncacheable = false;

		Session(IlSyntaxEnvironment environment, int replayDepth) {
			this.environment = environment;
			this.replayDepth = replayDepth;
		}
	}

	private static final ThreadLocal<Deque<Session>> SESSIONS = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<Deque<Entry>> ACTIVE_REPLAYS = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<Set<String>> REPLAYING_KEYS = ThreadLocal.withInitial(HashSet::new);

	static @Nullable Session currentSession() {
		Session session = SESSIONS.get().peek();
		return session != null && session.replayDepth == ACTIVE_REPLAYS.get().size() ? session : null;
	}

	public static boolean isRecording() {
		return recordingSession() != null;
	}

	private static @Nullable Session recordingSession() {
		Session session = currentSession();
		return session != null && !session.uncacheable ? session : null;
	}

	/**
	 * Records actual function candidate queries, including misses made by losing syntax candidates.
	 */
	public static void observeFunctionLookup(@Nullable String namespace, String name, Set<Signature<?>> signatures) {
		Deque<Session> sessions = SESSIONS.get();
		Deque<Entry> replays = ACTIVE_REPLAYS.get();
		Entry replay = replays.peek();
		if (sessions.isEmpty() && replay == null) return;
		Lookup lookup;
		try {
			lookup = IlFunctionDependencies.describe(namespace, name, signatures);
		} catch (RuntimeException | LinkageError e) {
			debug("Could not fingerprint function lookup " + name + ": " + e);
			lookup = null;
		}
		if (replay != null) {
			// Check the candidates actually used if the registry changed after preflight
			if (lookup == null || !replay.functions().contains(lookup)) throw new StaleLookup();
		}
		int depth = replays.size();
		for (Session session : sessions) {
			if (session.replayDepth != depth) break;
			if (session.uncacheable) continue;
			if (lookup == null) {
				session.uncacheable = true;
			} else {
				if (session.functions == null) session.functions = new LinkedHashSet<>();
				session.functions.add(lookup);
			}
		}
	}

	private static final class StaleLookup extends RuntimeException {
		private StaleLookup() {
			super(null, null, false, false);
		}
	}


	/**
	 * A site call, either replays a cached entry or opens a recording session.
	 */
	public static final class Frame {

		final Site site;
		final String expr;
		final int flags;
		final int parseContext;
		final String key;
		final IlScriptCache cache;
		final @Nullable Entry cached;
		final boolean hadCachedNode;
		@Nullable Session session;
		boolean replayFailed;

		Frame(Site site, String expr, int flags, int parseContext, String key,
			      IlScriptCache cache, @Nullable Entry cached, boolean hadCachedNode) {
			this.site = site;
			this.expr = expr;
			this.flags = flags;
			this.parseContext = parseContext;
			this.key = key;
			this.cache = cache;
			this.cached = cached;
			this.hadCachedNode = hadCachedNode;
		}

		public @Nullable Entry cached() {
			return cached;
		}

		public boolean canReplay() {
			return cache.environment().active();
		}

	}

	/**
	 * Starts an IL site call. Returns {@code null} if the IL is unavailable for this call.
	 */
	public static @Nullable Frame beginSite(Site site, String expr, int flags, int parseContext) {
		try {
			if (!enabled()) return null;
			ParserInstance parser = ParserInstance.get();
			if (!parser.isActive()) return null;
			IlScriptCache cache = parser.getCurrentScript().getData(IlScriptCache.class);
			if (cache == null || !cache.environment().active()) return null;
			Node node = parser.getNode();
			if (node == null) return null;
			int line = node.getLine();
			if (line < 0) return null;
			String key = cacheKey(site, line, expr, flags, parseContext);
			Entry cached = cache.get(key);
			boolean hadCachedNode = false;
			if (cached != null && cached.state() == STATE_NODE) {
				hadCachedNode = true;
				IlSyntaxEnvironment environment = cache.environment();
				if (!valid(cached, environment, site)) {
					cache.invalidate(key);
					cached = null;
				} else if (cached.ordering() != environment.ordering()) {
					cached = cached.withOrdering(environment.ordering());
					cache.refresh(key, cached);
				}
			}
			// A cold parent must observe the complete function lookup history of its children.
			if (currentSession() != null) cached = null;
			return new Frame(site, expr, flags, parseContext, key, cache, cached, hadCachedNode);
		} catch (Exception e) {
			// the IL must never break script loading
			debug("beginSite failed: " + e);
			return null;
		}
	}

	static String cacheKey(Site site, int line, String expression, int flags, int parseContext) {
		return site.ordinal() + ":" + line + ":" + flags + ":" + parseContext + ":" + expression;
	}

	private static boolean valid(Entry entry, IlSyntaxEnvironment environment, Site site) {
		for (Lookup lookup : entry.functions()) {
			if (!IlFunctionDependencies.matches(lookup)) return false;
		}
		Ordering ordering = entry.ordering();
		return ordering != null && (ordering.fingerprint().equals(environment.ordering().fingerprint())
			|| entry.node() != null && IlReplay.precedenceMatches(entry.node(), environment, ordering, site.registryKey));
	}

	/**
	 * Opens a recording session for the given site call.
	 */
	public static void startRecording(Frame frame) {
		if (!frame.cache.environment().active()) return;
		int replayDepth = ACTIVE_REPLAYS.get().size();
		// Repair a previously replayable child without recording every failed candidate in a replayed section.
		if (replayDepth != 0 && !frame.hadCachedNode) return;
		Session session = new Session(frame.cache.environment(), replayDepth);
		frame.session = session;
		SESSIONS.get().push(session);
	}

	/**
	 * Ends a recording session and records a successful parse for the next load.
	 */
	public static void finishSite(Frame frame, @Nullable Object result) {
		Session session = frame.session;
		if (session == null)
			return;
		Deque<Session> sessions = SESSIONS.get();
		if (!sessions.isEmpty() && sessions.peek() == session)
			sessions.pop();
		frame.session = null;

		IlScriptCache cache = frame.cache;
		if (result == null || !session.environment.active()) return;
		IlNode node = session.nodes.get(result);
		byte state = STATE_NODE;
		if (node == null || session.uncacheable) {
			state = STATE_UNSAFE;
			UNCACHEABLE.incrementAndGet();
		}
		try {
			Entry entry = state == STATE_NODE ? new Entry(state, node, frame.flags, frame.parseContext, frame.expr,
				session.environment.ordering(), session.functions == null ? List.of() : List.copyOf(session.functions))
				: new Entry(state, null, frame.flags, frame.parseContext, frame.expr);
			if (frame.replayFailed && entry.equals(frame.cached)) {
				entry = new Entry(STATE_UNSAFE, null, frame.flags, frame.parseContext, frame.expr);
			}
			int status = cache.put(frame.key, entry);
			if (status == IlScriptCache.CONFLICT) {
				CONFLICTS.incrementAndGet();
				cache.markDirty();
			} else if (status == IlScriptCache.ADDED) {
				cache.markDirty();
			}
		} catch (Exception e) {
			debug("could not store IL entry " + frame.key + ": " + e);
		}
	}

	/**
	 * Ends a recording session after a failure while nothing is stored.
	 */
	public static void abortSite(Frame frame) {
		Session session = frame.session;
		if (session == null)
			return;
		Deque<Session> sessions = SESSIONS.get();
		if (!sessions.isEmpty() && sessions.peek() == session)
			sessions.pop();
		frame.session = null;
	}

	/**
	 * Records a successfully created syntax element as an IL node.
	 */
	public static void recordElement(Object result, SyntaxInfo<?> info, int patternIndex,
	                                 ParseResult parseResult) {
		Session session = recordingSession();
		if (session == null) return;
		try {
			IlRegistration registration = session.environment.binding(info);
			if (registration == null) {
				session.uncacheable = true;
				return;
			}
			IlNode node = IlRecorder.buildElementNode(registration, patternIndex, parseResult);
			if (node == null) {
				session.uncacheable = true;
				return;
			}
			session.nodes.put(result, node);
		} catch (Exception e) {
			// the IL must never break script loading
			session.uncacheable = true;
			debug("could not record element: " + e);
		}
	}

	/**
	 * Registers a leaf expression created by the parser.
	 */
	public static void registerLeaf(Object object, IlNode node) {
		Session session = recordingSession();
		if (session != null) session.nodes.put(object, node);
	}

	/**
	 * @return the IL node that was registered for the given object in the current recording session, if any.
	 */
	public static @Nullable IlNode nodeFor(Object object) {
		Session session = currentSession();
		return session == null ? null : session.nodes.get(object);
	}

	/**
	 * Replays a recorded node. Returns {@code null} when the node could not be replayed.
	 */
	@SuppressWarnings("unchecked")
	public static <T> @Nullable T replay(Frame frame, Entry entry, SkriptParser parser) {
		IlNode node = entry.node();
		assert node != null;
		String key = frame.key;
		Set<String> replaying = REPLAYING_KEYS.get();
		if (!replaying.add(key)) return null; // fall back to a normal parse
		Deque<Entry> active = ACTIVE_REPLAYS.get();
		active.push(entry);
		try {
			SyntaxElement element = IlReplay.build(node, parser);
			if (element != null) {
				ENTRY_HITS.incrementAndGet();
				REPLAYS.incrementAndGet();
				return (T) element;
			}
			REPLAY_FAILURES.incrementAndGet();
			debug("IL replay failed for entry " + key);
		} catch (StaleLookup e) {
			debug("Function lookup changed during IL replay of " + key);
		} catch (Throwable t) {
			REPLAY_FAILURES.incrementAndGet();
			debug("IL replay threw for entry " + key + ": " + t);
		} finally {
			active.pop();
			replaying.remove(key);
		}
		frame.replayFailed = true;
		frame.cache.invalidate(key);
		return null;
	}

	public static void debug(String message) {
		try {
			if (Skript.debug())
				Skript.info("[IL] " + message);
		} catch (Exception ignored) {
			// the Skript instance may not be available yet
		}
	}

}
