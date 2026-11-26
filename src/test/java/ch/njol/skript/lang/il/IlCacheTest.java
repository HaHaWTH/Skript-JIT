package ch.njol.skript.lang.il;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.test.runner.SkriptJUnitTest;
import ch.njol.skript.test.runner.TestTracker;
import ch.njol.skript.variables.Variables;
import ch.njol.util.OpenCloseable;
import org.bukkit.entity.Pig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.lang.structure.Structure;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

/**
 * End-to-end tests for the persistent intermediate-language (IL) script parse cache: cold load + persist,
 * simulated restart + replay, replayed code execution, invalidation when a fixture file changes, and replay speed.
 * <p>
 * The IL cache directory ({@code <data>/il-cache}) is intentionally <b>not</b> deleted by this class: it also caches
 * the startup JUnit and example scripts, and keeping it lets a later test-server run with the same built jar (and
 * therefore the same environment fingerprint) replay those startup scripts from disk. The fixtures written by this
 * class contain a fresh UUID on every run, so their content hash is always new and they can never get a stale hit.
 */
public class IlCacheTest extends SkriptJUnitTest {

	private static final String LOAD_COUNT = "il_cache_load_count";
	private static final String LOOP_VALUE = "il_cache_loop_value";
	private static final String TRIGGER_FIRED = "il_cache_trigger_fired";
	private static final String TRIGGER_DAMAGE = "il_cache_trigger_damage";
	private static final String EXTRA_MARKER = "il_cache_extra_marker";
	private static final String MULTIPLICATION_RESULT = "il_cache_multiplication_result";
	private static final String SUBTRACTION_RESULT = "il_cache_subtraction_result";
	private static final String CONSUMER_VALUE = "il_cache_consumer_value";
	private static final String CONSUMER_MARKER = "il_cache_consumer_marker";

	/** Number of generated statement groups per trigger body in {@link #replayIsFasterThanColdParse()}. */
	private static final int BIG_FIXTURE_ITERATIONS = 60;

	private final Path fixtureDirectory = Skript.getInstance().getDataFolder().toPath()
		.resolve("scripts").resolve("il-cache-test");
	private final Path fixtureFile = fixtureDirectory.resolve("fixture.sk");
	private final Path bigFixtureFile = fixtureDirectory.resolve("big-fixture.sk");
	private final Set<Path> regressionCacheFiles = new HashSet<>();

	/** Failures that existed before the current test method started; only new script assertion failures fail it. */
	private Set<String> failuresBefore;

	@Before
	public void setUp() throws Exception {
		// Loading synchronously is required: loadScripts(...).join() deadlocks on the main thread when async loading is on.
		assumeFalse("The IL cache test requires synchronous script loading", ScriptLoader.isAsync());
		IlManager.setEnabledOverride(Boolean.TRUE);
		assertTrue("IlManager.enabled() must honour the test override", IlManager.enabled());

		// The shared IL cache directory is deliberately left alone (see the class javadoc). The fixtures contain a
		// fresh UUID, so their content hash is new and their cold load cannot hit a stale cache file.
		IlManager.clearInMemoryCaches();
		deleteRecursively(fixtureDirectory);
		IlManager.resetStats();

		Files.createDirectories(fixtureDirectory);
		Files.writeString(fixtureFile, fixture(UUID.randomUUID().toString()), StandardCharsets.UTF_8);

		// The fixture increments this variable on every load, so it must start from a known value.
		Variables.setVariable(LOAD_COUNT, 0L, null, false);

		failuresBefore = new HashSet<>(TestTracker.getFailedTests().keySet());
	}

	@Test
	public void coldLoadSavesAndReplaysIlEntries() throws Exception {
		// --- 1) cold load: parse everything and persist it as IL ---
		int cacheFilesBefore = cacheFileCount();
		Script script = loadFile(fixtureFile.toFile());
		List<String> coldFingerprint = fingerprint(script);
		var afterColdLoad = IlManager.stats();
		assertEquals("the cold load must not hit any IL entry", 0, afterColdLoad.entryHits());
		assertTrue("the cold load must save the script to the IL cache", afterColdLoad.cacheSaves() > 0);
		assertNotNull("the IL cache directory must be known after a cold load", IlManager.cacheDirectory());
		assertTrue("the cold load must write a new IL cache file", cacheFileCount() > cacheFilesBefore);
		assertEquals("the cold load must not report replay failures", 0, afterColdLoad.replayFailures());
		assertEquals("the load event must have run on the cold load", 1, globalNumber(LOAD_COUNT).intValue());
		assertEquals("the loop must have run on the cold load", 3, globalNumber(LOOP_VALUE).intValue());
		assertNoScriptFailures("the cold load");

		// --- 2) simulate a restart, then reload: the IL must be replayed from disk only ---
		IlManager.clearInMemoryCaches();
		var beforeReplayedLoad = IlManager.stats();
		Script replayed = reloadFile(script);
		var afterReplayedLoad = IlManager.stats();
		assertNotSame("a reload must create a new Script instance", script, replayed);
		assertEquals("the replayed script must have the same structures", coldFingerprint, fingerprint(replayed));
		assertTrue("the reload must hit IL entries loaded from disk",
			delta(beforeReplayedLoad.entryHits(), afterReplayedLoad.entryHits()) > 0);
		assertTrue("the reload must replay IL elements",
			delta(beforeReplayedLoad.replays(), afterReplayedLoad.replays()) > 0);
		assertEquals("replaying must not fail", 0, afterReplayedLoad.replayFailures());
		assertEquals("the replayed load event must have run", 2, globalNumber(LOAD_COUNT).intValue());
		assertEquals("the replayed loop must have run", 3, globalNumber(LOOP_VALUE).intValue());
		assertNoScriptFailures("the cache-replayed load");

		// --- 3) the replayed trigger items must still execute (not only parse) ---
		assertEquals("the load event must reset the trigger variable", Boolean.FALSE, global(TRIGGER_FIRED));
		Pig pig = spawnTestPig();
		pig.damage(10); // 10 Bukkit damage points == 5 Skript "hearts"
		assertEquals("the replayed damage trigger did not run or its function did not return true",
			Boolean.TRUE, global(TRIGGER_FIRED));
		assertEquals("the replayed damage trigger saw the wrong damage", 5.0,
			globalNumber(TRIGGER_DAMAGE).doubleValue(), 1.0e-6);
		assertEquals("firing the event must not load the script again", 2, globalNumber(LOAD_COUNT).intValue());
		assertNoScriptFailures("the event fired through the replayed trigger");

		// --- 4) a changed file must invalidate the cache ---
		String changedFixture = Files.readString(fixtureFile, StandardCharsets.UTF_8)
			+ "\non load:\n\tset {" + EXTRA_MARKER + "} to true\n";
		Files.writeString(fixtureFile, changedFixture, StandardCharsets.UTF_8);
		IlManager.clearInMemoryCaches();
		var beforeChangedLoad = IlManager.stats();
		script = reloadFile(replayed);
		var afterChangedLoad = IlManager.stats();
		List<String> changedFingerprint = fingerprint(script);
		assertEquals("a changed script must not replay old IL entries", 0L,
			delta(beforeChangedLoad.entryHits(), afterChangedLoad.entryHits()));
		assertTrue("a changed script must be parsed and cached again",
			delta(beforeChangedLoad.cacheSaves(), afterChangedLoad.cacheSaves()) > 0);
		// The appended structure is another 'on load' event, so it must produce exactly the same fingerprint entry
		// as the existing 'on load' structure of the cold fixture (whatever wrapper class events use).
		List<String> expectedChangedFingerprint = new ArrayList<>(coldFingerprint);
		expectedChangedFingerprint.add(coldFingerprint.stream()
			.filter(entry -> entry.endsWith("|script load"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the cold fixture has no 'on load' structure")));
		assertEquals("the changed script must contain the cold structures plus the appended 'on load'",
			expectedChangedFingerprint, changedFingerprint);
		assertEquals("the added load-time trigger must run after a fresh parse", 3, globalNumber(LOAD_COUNT).intValue());
		assertEquals("the added load-time trigger must set its variable", Boolean.TRUE, global(EXTRA_MARKER));
		assertEquals("the changed load must not report replay failures", 0, afterChangedLoad.replayFailures());
		assertNoScriptFailures("the changed load");

		// --- 5) the freshly written IL must be replayed again ---
		IlManager.clearInMemoryCaches();
		var beforeSecondReplay = IlManager.stats();
		script = reloadFile(script);
		var afterSecondReplay = IlManager.stats();
		assertEquals("the re-cached script must have the same structures", changedFingerprint, fingerprint(script));
		assertTrue("the re-cached script must be replayed",
			delta(beforeSecondReplay.entryHits(), afterSecondReplay.entryHits()) > 0);
		assertTrue("the re-cached script must report replays",
			delta(beforeSecondReplay.replays(), afterSecondReplay.replays()) > 0);
		assertEquals("replaying must not fail", 0, afterSecondReplay.replayFailures());
		assertEquals("the replayed appended trigger must run", 4, globalNumber(LOAD_COUNT).intValue());
		assertNoScriptFailures("the re-cached replay");
	}

	@Test
	public void replaysArithmeticWithTheSamePrecedenceAndAssociativity() throws Exception {
		assertTrue("This fixture exercises parse-time simplification", SkriptConfig.simplifySyntaxesOnParse.value());
		Path arithmetic = fixtureDirectory.resolve("arithmetic.sk");
		Files.writeString(arithmetic, """
			# %s
			on load:
				set {%s} to 2 * 3 + 4
				set {%s} to 10 - 3 - 2
			""".formatted(UUID.randomUUID(), MULTIPLICATION_RESULT, SUBTRACTION_RESULT), StandardCharsets.UTF_8);

		Script cold = loadFile(arithmetic.toFile());
		trackFixtureCache(arithmetic);
		assertEquals(10, globalNumber(MULTIPLICATION_RESULT).longValue());
		assertEquals(5, globalNumber(SUBTRACTION_RESULT).longValue());
		assertEquals(0, IlManager.stats().replays());

		Variables.deleteVariable(MULTIPLICATION_RESULT, null, false);
		Variables.deleteVariable(SUBTRACTION_RESULT, null, false);
		IlManager.clearInMemoryCaches();
		var beforeReplay = IlManager.stats();
		reloadFile(cold);
		var afterReplay = IlManager.stats();
		assertEquals("Both arithmetic statements must replay from disk", 2,
			delta(beforeReplay.replays(), afterReplay.replays()));
		assertEquals(10, globalNumber(MULTIPLICATION_RESULT).longValue());
		assertEquals(5, globalNumber(SUBTRACTION_RESULT).longValue());
	}

	@Test
	public void reparsesLegacyMissWhenAnotherScriptDefinesTheFunction() throws Exception {
		Path consumer = fixtureDirectory.resolve("consumer.sk");
		Path provider = fixtureDirectory.resolve("provider.sk");
		String functionName = "il_cache_late_" + UUID.randomUUID().toString().replace("-", "");
		String assignment = "set {" + CONSUMER_VALUE + "} to " + functionName + "()";
		String consumerSource = "on load:\n\tset {" + CONSUMER_MARKER + "} to true\n\t" + assignment + "\n";
		Files.writeString(consumer, consumerSource, StandardCharsets.UTF_8);
		Variables.deleteVariable(CONSUMER_VALUE, null, false);

		Script cold;
		try (var log = SkriptLogger.startRetainingLog()) {
			cold = loadFile(consumer.toFile());
			assertTrue("The first load must report the missing function: " + log.getLog(), log.hasErrors());
		}
		assertNull(global(CONSUMER_VALUE));
		assertEquals(Boolean.TRUE, global(CONSUMER_MARKER));
		Path cacheFile = trackFixtureCache(consumer);
		String sourceHash = IlEnvironment.hashFile(consumer.toFile());
		String fingerprint = IlManager.environmentFingerprint();
		var entries = IlFile.load(cacheFile.toFile(), sourceHash, fingerprint);
		assertNotNull(entries);
		// Recreate the entry written by older builds for the failed statement at line 3.
		String missKey = IlManager.Site.STATEMENT_SIMPLE.ordinal() + ":3:" + assignment;
		entries.put(missKey, new IlManager.Entry(IlManager.STATE_MISS, null,
			SkriptParser.ALL_FLAGS, ParseContext.DEFAULT.ordinal(), assignment));
		IlFile.save(cacheFile.toFile(), sourceHash, fingerprint, entries);

		Files.writeString(provider, "function " + functionName + "() :: number:\n\treturn 41\n", StandardCharsets.UTF_8);
		loadFile(provider.toFile());
		trackFixtureCache(provider);
		IlManager.clearInMemoryCaches();
		var beforeMissingRetry = IlManager.stats();
		Script resolved = reloadFile(cold);
		var afterMissingRetry = IlManager.stats();
		assertEquals("The valid neighbouring entry must still replay", 1,
			delta(beforeMissingRetry.replays(), afterMissingRetry.replays()));
		assertEquals("The unresolved statement must now run", 41, globalNumber(CONSUMER_VALUE).longValue());
		assertEquals("The consumer file must be unchanged", consumerSource, Files.readString(consumer));

		Variables.deleteVariable(CONSUMER_VALUE, null, false);
		IlManager.clearInMemoryCaches();
		var beforeReplay = IlManager.stats();
		reloadFile(resolved);
		var afterReplay = IlManager.stats();
		assertEquals("The newly resolved statement must now replay alongside its neighbour", 2,
			delta(beforeReplay.replays(), afterReplay.replays()));
		assertEquals(41, globalNumber(CONSUMER_VALUE).longValue());
	}

	@Test
	public void replayIsFasterThanColdParse() throws Exception {
		String bigFixture = bigFixture(UUID.randomUUID().toString(), BIG_FIXTURE_ITERATIONS);
		int lineCount = (int) bigFixture.lines().count();
		Files.writeString(bigFixtureFile, bigFixture, StandardCharsets.UTF_8);

		try {
			// Cold parse: the fresh run id gives the file a new content hash, so this is guaranteed to be a miss.
			IlManager.clearInMemoryCaches();
			var beforeCold = IlManager.stats();
			long coldStart = System.nanoTime();
			Script coldScript = loadFile(bigFixtureFile.toFile());
			long coldNanos = System.nanoTime() - coldStart;
			var afterCold = IlManager.stats();

			// Simulated restart: drop the in-memory state and reload from the freshly written IL files.
			IlManager.clearInMemoryCaches();
			var beforeReplay = IlManager.stats();
			long replayStart = System.nanoTime();
			reloadFile(coldScript);
			long replayNanos = System.nanoTime() - replayStart;
			var afterReplay = IlManager.stats();

			long coldMs = coldNanos / 1_000_000L;
			long replayMs = replayNanos / 1_000_000L;
			double speedup = (double) coldNanos / Math.max(1L, replayNanos);
			long hits = delta(beforeReplay.entryHits(), afterReplay.entryHits());
			long replays = delta(beforeReplay.replays(), afterReplay.replays());
			long replayFailures = delta(beforeReplay.replayFailures(), afterReplay.replayFailures());
			System.out.println("[IL-CACHE-PERF] lines=" + lineCount + " coldMs=" + coldMs + " replayMs=" + replayMs
				+ " speedup=" + String.format(Locale.ROOT, "%.2f", speedup) + "x entryHits=" + hits
				+ " replays=" + replays + " replayFailures=" + replayFailures);

			assertEquals("the big fixture cold load must miss the IL", 0L,
				delta(beforeCold.entryHits(), afterCold.entryHits()));
			assertTrue("the big fixture cold load must persist IL",
				delta(beforeCold.cacheSaves(), afterCold.cacheSaves()) > 0);
			assertTrue("the big fixture reload must hit IL entries", hits > 0);
			assertTrue("the big fixture reload must report replayed elements", replays > 0);
			assertEquals("the big fixture reload must not fail replays", 0L, replayFailures);
			assertTrue("IL replay must be faster than a cold parse of " + lineCount + " lines (cold=" + coldMs
				+ "ms, replay=" + replayMs + "ms, speedup=" + String.format(Locale.ROOT, "%.2f", speedup)
				+ "x, entryHits=" + hits + ")", replayNanos < coldNanos);
		} finally {
			// Never leave the big fixture loaded, even if an assertion above failed.
			File bigFile = bigFixtureFile.toFile().getCanonicalFile();
			for (Script loaded : ScriptLoader.getLoadedScripts()) {
				File file = loaded.getConfig().getFile();
				if (file != null && bigFile.equals(file.getCanonicalFile()))
					ScriptLoader.unloadScript(loaded);
			}
			Files.deleteIfExists(bigFixtureFile);
		}
	}

	private Script loadFile(File file) throws IOException {
		ScriptLoader.loadScripts(file, OpenCloseable.EMPTY).join();
		return requireLoadedScript(file);
	}

	private Script reloadFile(Script current) throws IOException {
		File file = current.getConfig().getFile();
		assertNotNull("the reloaded script must have a file", file);
		ScriptLoader.reloadScript(current, OpenCloseable.EMPTY).join();
		return requireLoadedScript(file);
	}

	private Script requireLoadedScript(File file) throws IOException {
		File expected = file.getCanonicalFile();
		for (Script loaded : ScriptLoader.getLoadedScripts()) {
			if (expected.equals(loaded.getConfig().getFile()))
				return loaded;
		}
		fail("the script is not loaded: " + file);
		return null; // unreachable
	}

	/**
	 * Ordered structure fingerprint (class + debug representation). Trigger items are not included because their
	 * roots are not exposed by the public API; the behavioural checks prove that they were replayed correctly.
	 */
	private static List<String> fingerprint(Script script) {
		List<String> fingerprint = new ArrayList<>();
		for (Structure structure : script.getStructures()) {
			String representation;
			try {
				representation = String.valueOf(structure.toString(null, false));
			} catch (RuntimeException e) {
				representation = "<toString failed: " + e.getClass().getSimpleName() + ">";
			}
			fingerprint.add(structure.getClass().getName() + "|" + representation);
		}
		return fingerprint;
	}

	private static Object global(String name) {
		return Variables.getVariable(name, null, false);
	}

	private static Number globalNumber(String name) {
		Object value = global(name);
		assertNotNull("the variable {" + name + "} must be set", value);
		assertTrue("the variable {" + name + "} must be a number but was " + value.getClass().getName(),
			value instanceof Number);
		return (Number) value;
	}

	private void assertNoScriptFailures(String step) {
		Map<String, String> failures = TestTracker.getFailedTests();
		failures.keySet().removeAll(failuresBefore);
		assertTrue("Skript assertions failed during " + step + ": " + failures, failures.isEmpty());
	}

	private static long delta(long before, long after) {
		return after - before;
	}

	private static int cacheFileCount() throws IOException {
		Path cacheDirectory = IlManager.cacheDirectory();
		if (cacheDirectory == null || !Files.isDirectory(cacheDirectory))
			return 0;
		try (Stream<Path> files = Files.walk(cacheDirectory)) {
			return (int) files.filter(Files::isRegularFile).count();
		}
	}

	private Path trackFixtureCache(Path scriptFile) throws IOException {
		String sourceHash = IlEnvironment.hashFile(scriptFile.toFile());
		assertNotNull(sourceHash);
		Path directory = IlManager.cacheDirectory();
		assertNotNull(directory);
		try (var files = Files.list(directory)) {
			List<Path> matches = files.filter(path -> {
				String name = path.getFileName().toString();
				return name.startsWith(sourceHash + "-") && name.endsWith(".il");
			}).toList();
			assertEquals("The unique fixture must have one cache file", 1, matches.size());
			Path file = matches.getFirst();
			regressionCacheFiles.add(file);
			return file;
		}
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (path == null || !Files.exists(path))
			return;
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(path)) {
			paths = walk.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path child : paths)
			Files.deleteIfExists(child);
	}

	private static String fixture(String runId) {
		return """
			# il-cache test fixture, run id: RUN_ID
			function il_cache_probe() :: boolean:
				return true

			on load:
				set {_il_cache_x} to 1 + 2
				if {_il_cache_x} is 3:
					assert {_il_cache_x} is 3 with "il_cache: the if/assert pair did not execute"
				send "hi %{_il_cache_x}%" to console
				loop 3 times:
					set {il_cache_loop_value} to loop-value
				add 1 to {il_cache_load_count}
				set {il_cache_trigger_fired} to false
				set {il_cache_trigger_damage} to 0

			on damage of pig:
				set {il_cache_trigger_fired} to il_cache_probe()
				set {il_cache_trigger_damage} to damage
				assert {il_cache_trigger_fired} is true with "il_cache: the replayed function did not return true"
				assert damage is 5 with "il_cache: the replayed damage trigger saw the wrong damage"
			""".replace("RUN_ID", runId);
	}

	/**
	 * A large fixture used to compare a cold parse against an IL replay. Both trigger bodies are deliberately big;
	 * the damage body is never fired, it only contributes parse work.
	 */
	private static String bigFixture(String runId, int iterations) {
		StringBuilder builder = new StringBuilder(64 * 1024);
		builder.append("# il-cache big fixture, run id: ").append(runId).append('\n');
		builder.append("on load:\n\tset {_counter} to 0\n");
		appendBigBody(builder, iterations);
		builder.append("\non damage of pig:\n\tset {_counter} to 0\n");
		appendBigBody(builder, iterations);
		return builder.toString();
	}

	private static void appendBigBody(StringBuilder builder, int iterations) {
		for (int i = 1; i <= iterations; i++) {
			builder.append("\tset {_v_").append(i).append("} to ").append(i).append(" + 1\n");
			builder.append("\tadd 1 to {_counter}\n");
			builder.append("\tif {_v_").append(i).append("} is ").append(i + 1).append(":\n");
			builder.append("\t\tadd 1 to {_counter}\n");
			if (i % 20 == 0)
				builder.append("\tsend \"line ").append(i).append(": %{_v_").append(i).append("}%\" to console\n");
			if (i % 10 == 0)
				builder.append("\tloop ").append(i % 5 + 2).append(" times:\n\t\tset {_loop_last} to loop-value\n");
		}
	}

	@After
	public void tearDown() throws Exception {
		try {
			// Unload every script loaded from this test's fixture directory (the shared startup scripts stay loaded).
			File dir = fixtureDirectory.toFile().getCanonicalFile();
			for (Script loaded : ScriptLoader.getLoadedScripts()) {
				File file = loaded.getConfig().getFile();
				if (file != null && dir.equals(file.getCanonicalFile().getParentFile()))
					ScriptLoader.unloadScript(loaded);
			}
		} finally {
			// Only remove this class's fixtures and variables; the shared IL cache directory must be kept.
			deleteRecursively(fixtureDirectory);
			for (Path cacheFile : regressionCacheFiles) {
				Files.deleteIfExists(cacheFile);
			}
			for (String variable : new String[] {LOAD_COUNT, LOOP_VALUE, TRIGGER_FIRED, TRIGGER_DAMAGE, EXTRA_MARKER,
				MULTIPLICATION_RESULT, SUBTRACTION_RESULT, CONSUMER_VALUE, CONSUMER_MARKER}) {
				Variables.deleteVariable(variable, null, false);
			}
			IlManager.clearInMemoryCaches();
			IlManager.setEnabledOverride((Boolean) null);
			IlManager.resetStats();
		}
	}

}
