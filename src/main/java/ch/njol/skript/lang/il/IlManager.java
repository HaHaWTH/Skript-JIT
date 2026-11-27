package ch.njol.skript.lang.il;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.config.Config;
import ch.njol.skript.config.Node;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.SyntaxElement;
import ch.njol.skript.lang.parser.ParserInstance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.registration.SyntaxInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
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
		SECTION,
		EFFECT_SECTION,
		STATEMENT_SIMPLE,
		STATEMENT_SECTION,
		CONDITION,
		EFFECT
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
		String expr
	) {
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

	private static volatile String environmentFingerprint;

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
			String fingerprint = environmentFingerprint();
			IlScriptCache cache = IlScriptCache.load(file.toPath(), sourceHash, fingerprint);
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
			if (cache.saveIfDirty()) {
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
		String fingerprint = environmentFingerprint;
		if (fingerprint == null) {
			fingerprint = IlEnvironment.computeFingerprint();
			environmentFingerprint = fingerprint;
		}
		return fingerprint;
	}

	/*
	 * Recording sessions
	 */

	/**
	 * The state of a single recorded parse call.
	 */
	static final class Session {
		final Map<Object, IlNode> nodes = new java.util.IdentityHashMap<>();
		boolean uncacheable = false;
	}

	private static final ThreadLocal<Deque<Session>> SESSIONS = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<int[]> REPLAY_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static final ThreadLocal<Set<String>> REPLAYING_KEYS = ThreadLocal.withInitial(HashSet::new);

	static @Nullable Session currentSession() {
		return SESSIONS.get().peek();
	}

	public static boolean isRecording() {
		return recordingSession() != null;
	}

	private static @Nullable Session recordingSession() {
		Session session = currentSession();
		return session != null && !session.uncacheable && !isReplaying() ? session : null;
	}

	public static boolean isReplaying() {
		return REPLAY_DEPTH.get()[0] > 0;
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
		final @Nullable IlScriptCache cache;
		final @Nullable Entry cached;
		@Nullable Session session;

		Frame(Site site, String expr, int flags, int parseContext, String key,
		      @Nullable IlScriptCache cache, @Nullable Entry cached) {
			this.site = site;
			this.expr = expr;
			this.flags = flags;
			this.parseContext = parseContext;
			this.key = key;
			this.cache = cache;
			this.cached = cached;
		}

		public @Nullable Entry cached() {
			return cached;
		}

		/**
		 * @return the cache key of this site call.
		 */
		public String key() {
			return key;
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
			if (cache == null) return null;
			Node node = parser.getNode();
			if (node == null) return null;
			int line = node.getLine();
			if (line < 0) return null;
			String key = site.ordinal() + ":" + line + ":" + expr;
			return new Frame(site, expr, flags, parseContext, key, cache, cache.get(key));
		} catch (Exception e) {
			// the IL must never break script loading
			debug("beginSite failed: " + e);
			return null;
		}
	}

	/**
	 * Opens a recording session for the given site call.
	 */
	public static void startRecording(Frame frame) {
		Session session = new Session();
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
		if (cache == null || result == null)
			return;
		IlNode node = session.nodes.get(result);
		byte state = STATE_NODE;
		if (node == null || session.uncacheable) {
			state = STATE_UNSAFE;
			UNCACHEABLE.incrementAndGet();
		}
		try {
			Entry entry = new Entry(state, node, frame.flags, frame.parseContext, frame.expr);
			int status = cache.put(frame.key, entry);
			if (status == IlScriptCache.CONFLICT) {
				CONFLICTS.incrementAndGet();
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
			// A class-only IL element can identify only one exact registration.
			if (infoFor(info.type()) != info) {
				session.uncacheable = true;
				return;
			}
			IlNode node = IlRecorder.buildElementNode(info, patternIndex, parseResult);
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
	public static <T> @Nullable T replay(IlNode node, String key, ch.njol.skript.lang.SkriptParser parser) {
		Set<String> replaying = REPLAYING_KEYS.get();
		if (!replaying.add(key)) return null; // fall back to a normal parse
		int[] depth = REPLAY_DEPTH.get();
		depth[0]++;
		try {
			SyntaxElement element = IlReplay.build(node, parser);
			if (element != null) {
				ENTRY_HITS.incrementAndGet();
				REPLAYS.incrementAndGet();
				return (T) element;
			}
			REPLAY_FAILURES.incrementAndGet();
			debug("IL replay failed for entry " + key);
			return null;
		} catch (Throwable t) {
			REPLAY_FAILURES.incrementAndGet();
			debug("IL replay threw for entry " + key + ": " + t);
			return null;
		} finally {
			depth[0]--;
			replaying.remove(key);
		}
	}

	/*
	 * Syntax registry lookup
	 */

	private static volatile Map<Class<?>, SyntaxInfo<?>> syntaxInfoByClass;

	/**
	 * Looks up the registered syntax info of a syntax element class.
	 */
	public static @Nullable SyntaxInfo<?> infoFor(String className) {
		Class<?> type;
		try {
			type = Class.forName(className, false, IlManager.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
		return infoFor(type);
	}

	public static @Nullable SyntaxInfo<?> infoFor(Class<?> type) {
		Map<Class<?>, SyntaxInfo<?>> map = syntaxInfoByClass;
		if (map == null) {
			synchronized (IlManager.class) {
				map = syntaxInfoByClass;
				if (map == null) {
					map = new java.util.HashMap<>();
					Set<Class<?>> ambiguous = new HashSet<>();
					for (org.skriptlang.skript.registration.SyntaxRegistry.Key<?> key : REGISTRY_KEYS) {
						for (SyntaxInfo<?> info : ch.njol.skript.Skript.instance().syntaxRegistry().syntaxes(key)) {
							Class<?> implementation = info.type();
							if (ambiguous.contains(implementation)) continue;
							SyntaxInfo<?> previous = map.putIfAbsent(implementation, info);
							if (previous != null && previous != info) {
								map.remove(implementation);
								ambiguous.add(implementation);
							}
						}
					}
					syntaxInfoByClass = map;
				}
			}
		}
		return map.get(type);
	}

	private static final java.util.List<org.skriptlang.skript.registration.SyntaxRegistry.Key<?>> REGISTRY_KEYS =
		java.util.List.of(
			org.skriptlang.skript.registration.SyntaxRegistry.STATEMENT,
			org.skriptlang.skript.registration.SyntaxRegistry.EFFECT,
			org.skriptlang.skript.registration.SyntaxRegistry.CONDITION,
			org.skriptlang.skript.registration.SyntaxRegistry.SECTION,
			org.skriptlang.skript.registration.SyntaxRegistry.EXPRESSION
		);

	public static void debug(String message) {
		try {
			if (Skript.debug())
				Skript.info("[IL] " + message);
		} catch (Exception ignored) {
			// the Skript instance may not be available yet
		}
	}

}
