package ch.njol.skript.lang.il;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.config.Config;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.function.FunctionRegistry;
import ch.njol.skript.log.LogEntry;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.test.runner.SkriptJUnitTest;
import ch.njol.skript.test.runner.TestTracker;
import ch.njol.skript.variables.Variables;
import ch.njol.util.OpenCloseable;
import org.bukkit.entity.Pig;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.docs.Origin;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.lang.structure.Structure;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

/**
 * End-to-end tests for the persistent intermediate-language (IL) script parse cache: cold load + persist,
 * disk replay, replayed code execution, invalidation when a fixture file changes, and replay speed.
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
	private static final String INPUT_DIGEST_VALUE = "il_cache_input_digest_value";
	private static SkriptAddon registrationAddon;

	/**
	 * Number of generated statement groups per trigger body in {@link #replayIsFasterThanColdParse()}.
	 */
	private static final int BIG_FIXTURE_ITERATIONS = 60;

	private final Path fixtureDirectory = Skript.getInstance().getDataFolder().toPath()
		.resolve("scripts").resolve("il-cache-test");
	private final Path fixtureFile = fixtureDirectory.resolve("fixture.sk");
	private final Path bigFixtureFile = fixtureDirectory.resolve("big-fixture.sk");
	private final Set<Path> regressionCacheFiles = new HashSet<>();

	/**
	 * Failures that existed before the current test method started; only new script assertion failures fail it.
	 */
	private Set<String> failuresBefore;

	@BeforeClass
	public static void registerTestAddon() {
		registrationAddon = Skript.instance().registerAddon(IlCacheTest.class, "Skript IL Cache Tests");
	}

	@Before
	public void setUp() throws Exception {
		// Loading synchronously is required: loadScripts(...).join() deadlocks on the main thread when async loading is on.
		assumeFalse("The IL cache test requires synchronous script loading", ScriptLoader.isAsync());
		IlManager.setEnabledOverride(Boolean.TRUE);
		assertTrue("IlManager.enabled() must honour the test override", IlManager.enabled());

		// The shared IL cache directory is deliberately left alone (see the class javadoc). The fixtures contain a
		// fresh UUID, so their content hash is new and their cold load cannot hit a stale cache file.
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

		// --- 2) reload the script using the persisted IL ---
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
	public void hashesTheInputSnapshotEvenWhenTheFileChangesBeforeParsing() throws Exception {
		Path source = fixtureDirectory.resolve("input-snapshot.sk");
		String fixture = "\uFEFF# " + UUID.randomUUID() + "\r\non load:\r\n\tset {" + INPUT_DIGEST_VALUE + "} to ";
		Files.writeString(source, fixture + "11", StandardCharsets.UTF_8);
		String originalHash = IlEnvironment.hashFile(source.toFile());
		assertNotNull(originalHash);
		File canonicalFile = source.toFile().getCanonicalFile();
		// This event runs after Config has read the file and before Skript starts loading its structures.
		ScriptLoader.ScriptPreInitEvent replaceSource = configs -> {
			if (configs.stream().noneMatch(config -> canonicalFile.equals(config.getFile()))) return;
			try {
				Files.writeString(source, fixture + "29", StandardCharsets.UTF_8);
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		};
		Script original;
		ScriptLoader.eventRegistry().register(replaceSource);
		try {
			original = loadFile(source.toFile());
		} finally {
			ScriptLoader.eventRegistry().unregister(replaceSource);
		}
		assertEquals(11, globalNumber(INPUT_DIGEST_VALUE).longValue());
		assertEquals(originalHash, original.getConfig().getSourceHash());
		Path originalIl = trackFixtureCache(originalHash);
		assertNotNull(IlFile.load(originalIl.toFile(), originalHash, IlManager.environmentFingerprint()));

		String replacementHash = IlEnvironment.hashFile(source.toFile());
		assertNotNull(replacementHash);
		assertNotEquals(originalHash, replacementHash);
		long entriesLoaded = IlManager.stats().entriesLoaded();
		Script replacement = reloadFile(original);
		assertEquals(29, globalNumber(INPUT_DIGEST_VALUE).longValue());
		assertEquals(replacementHash, replacement.getConfig().getSourceHash());
		assertEquals("There is no IL for the replacement until it is parsed", entriesLoaded, IlManager.stats().entriesLoaded());
		trackFixtureCache(replacementHash);

		IlManager.setEnabledOverride(false);
		assertNull("Disabled caching must skip hashing the script", reloadFile(replacement).getConfig().getSourceHash());
	}

	@Test
	public void keepsSeparateCacheStateForTwoLoadsOfTheSameConfig() throws IOException {
		Config config = new Config(Files.newInputStream(fixtureFile), fixtureFile.getFileName().toString(),
			fixtureFile.toFile().getCanonicalFile(), true, false, ":", true);
		assertNotNull(config.getSourceHash());
		Script first = new Script(config, List.of());
		Script second = new Script(config, List.of());

		IlManager.beginScript(first);
		IlManager.beginScript(second);
		IlScriptCache firstCache = first.getData(IlScriptCache.class);
		IlScriptCache secondCache = second.getData(IlScriptCache.class);
		assertNotNull(firstCache);
		assertNotNull(secondCache);
		assertNotSame(firstCache, secondCache);

		IlManager.finishScript(first);
		assertNull(first.getData(IlScriptCache.class));
		assertSame(secondCache, second.getData(IlScriptCache.class));
		IlManager.finishScript(second);
		assertNull(second.getData(IlScriptCache.class));
	}

	@Test
	public void concurrentSavesUseIndependentTemporaryFiles() throws Exception {
		Path directory = Files.createDirectory(fixtureDirectory.resolve("concurrent-save"));
		File file = directory.resolve("script.il").toFile();
		String sourceHash = "test-source";
		String fingerprint = IlManager.environmentFingerprint();
		Map<String, IlManager.Entry> firstEntries = Map.of(
			"first", new IlManager.Entry(IlManager.STATE_NODE, new IlNode.Unparsed("first"), 0, 0, "first"),
			"first-extra", new IlManager.Entry(IlManager.STATE_UNSAFE, null, 0, 0, "first-extra"));
		Map<String, IlManager.Entry> secondEntries = Map.of(
			"second", new IlManager.Entry(IlManager.STATE_NODE, new IlNode.Unparsed("second"), 0, 0, "second"),
			"second-extra", new IlManager.Entry(IlManager.STATE_UNSAFE, null, 0, 0, "second-extra"));
		CyclicBarrier openedWriters = new CyclicBarrier(2);

		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> {
				IlFile.save(file, sourceHash, fingerprint, awaitBothWriters(firstEntries, openedWriters));
				return null;
			});
			var second = workers.submit(() -> {
				IlFile.save(file, sourceHash, fingerprint, awaitBothWriters(secondEntries, openedWriters));
				return null;
			});
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}

		var loaded = IlFile.load(file, sourceHash, fingerprint);
		assertTrue("The published file must contain one complete save", firstEntries.equals(loaded) || secondEntries.equals(loaded));
		try (var files = Files.list(directory)) {
			assertEquals("No temporary files may remain", List.of(file.toPath()), files.toList());
		}
	}

	private static Map<String, IlManager.Entry> awaitBothWriters(Map<String, IlManager.Entry> entries, CyclicBarrier barrier) {
		return new AbstractMap<>() {
			@Override
			public int size() {
				return entries.size();
			}

			@Override
			public Set<Map.Entry<String, IlManager.Entry>> entrySet() {
				try {
					barrier.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("Interrupted waiting for both cache writers", exception);
				} catch (BrokenBarrierException | TimeoutException exception) {
					throw new AssertionError("Expected both cache writers to open their output first", exception);
				}
				return entries.entrySet();
			}
		};
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
		String missKey = IlManager.cacheKey(IlManager.Site.STATEMENT_SIMPLE, 3, assignment,
			SkriptParser.ALL_FLAGS, ParseContext.DEFAULT.ordinal());
		entries.put(missKey, new IlManager.Entry(IlManager.STATE_MISS, null,
			SkriptParser.ALL_FLAGS, ParseContext.DEFAULT.ordinal(), assignment));
		IlFile.save(cacheFile.toFile(), sourceHash, fingerprint, entries);

		Files.writeString(provider, "function " + functionName + "() :: number:\n\treturn 41\n", StandardCharsets.UTF_8);
		loadFile(provider.toFile());
		trackFixtureCache(provider);
		var beforeMissingRetry = IlManager.stats();
		Script resolved = reloadFile(cold);
		var afterMissingRetry = IlManager.stats();
		assertEquals("The valid neighbouring entry must still replay", 1,
			delta(beforeMissingRetry.replays(), afterMissingRetry.replays()));
		assertEquals("The unresolved statement must now run", 41, globalNumber(CONSUMER_VALUE).longValue());
		assertEquals("The consumer file must be unchanged", consumerSource, Files.readString(consumer));

		Variables.deleteVariable(CONSUMER_VALUE, null, false);
		var beforeReplay = IlManager.stats();
		reloadFile(resolved);
		var afterReplay = IlManager.stats();
		assertEquals("The newly resolved statement must now replay alongside its neighbour", 2,
			delta(beforeReplay.replays(), afterReplay.replays()));
		assertEquals(41, globalNumber(CONSUMER_VALUE).longValue());
	}

	@Test
	public void rerecordsAStaleChildStatementWhileItsLoopReplays() throws IOException {
		Path source = fixtureDirectory.resolve("nested-recording.sk");
		String header = "loop 3 times";
		String statement = "add 1 to {" + CONSUMER_VALUE + "}";
		Files.writeString(source, "# " + UUID.randomUUID() + "\non load:\n\t" + header + ":\n\t\t" + statement + "\n");
		Variables.setVariable(CONSUMER_VALUE, 0L, null, false);
		Script cold = loadFile(source.toFile());
		assertEquals(3L, globalNumber(CONSUMER_VALUE).longValue());
		File cacheFile = trackFixtureCache(source).toFile();
		String sourceHash = cold.getConfig().getSourceHash();
		assertNotNull(sourceHash);
		String environment = IlManager.environmentFingerprint();
		String headerKey = IlManager.cacheKey(IlManager.Site.SECTION, 3, header, SkriptParser.ALL_FLAGS, ParseContext.DEFAULT.ordinal());
		String childKey = IlManager.cacheKey(IlManager.Site.STATEMENT_SIMPLE, 4, statement, SkriptParser.ALL_FLAGS, ParseContext.DEFAULT.ordinal());
		var entries = IlFile.load(cacheFile, sourceHash, environment);
		assertNotNull(entries);
		var loop = entries.get(headerKey);
		var child = entries.get(childKey);
		assertNotNull(loop);
		assertNotNull(child);
		assertEquals(IlManager.STATE_NODE, loop.state());
		assertEquals(IlManager.STATE_NODE, child.state());
		assertNotNull(loop.ordering());
		entries.put(childKey, child.withOrdering(new IlSyntaxEnvironment.Ordering("unprovable child order", new int[0][])));
		IlFile.save(cacheFile, sourceHash, environment, entries);

		var before = IlManager.stats();
		Script repaired = reloadFile(cold);
		var after = IlManager.stats();
		assertEquals("Only the loop header can replay", 1, after.replays() - before.replays());
		assertEquals(before.replayFailures(), after.replayFailures());
		assertEquals(6L, globalNumber(CONSUMER_VALUE).longValue());
		var saved = IlFile.load(cacheFile, sourceHash, environment);
		assertNotNull(saved);
		var savedChild = saved.get(childKey);
		assertNotNull("Cold parsing inside the replayed loop must publish the child again", savedChild);
		assertEquals(IlManager.STATE_NODE, savedChild.state());
		assertNotNull(savedChild.ordering());
		assertEquals(loop.ordering().fingerprint(), savedChild.ordering().fingerprint());

		reloadFile(repaired);
		var next = IlManager.stats();
		assertEquals("Both the loop and its restored child must replay", 2, next.replays() - after.replays());
		assertEquals(after.replayFailures(), next.replayFailures());
		assertEquals(9L, globalNumber(CONSUMER_VALUE).longValue());
	}

	@Test
	public void aStatementSiteReplaysWhenOnlyTheRecordedEffectOrderDiffers() throws IOException {
		Path source = fixtureDirectory.resolve("statement-scope.sk");
		String statement = "set {" + CONSUMER_MARKER + "} to true";
		Files.writeString(source, "# " + UUID.randomUUID() + "\non load:\n\t" + statement + "\n");
		Script cold = loadFile(source.toFile());
		assertEquals(Boolean.TRUE, global(CONSUMER_MARKER));
		File cacheFile = trackFixtureCache(source).toFile();
		String sourceHash = cold.getConfig().getSourceHash();
		assertNotNull(sourceHash);
		String environment = IlManager.environmentFingerprint();
		String key = IlManager.cacheKey(IlManager.Site.STATEMENT_SIMPLE, 3, statement, SkriptParser.ALL_FLAGS, ParseContext.DEFAULT.ordinal());
		var entries = IlFile.load(cacheFile, sourceHash, environment);
		assertNotNull(entries);
		var entry = entries.get(key);
		assertNotNull(entry);
		assertEquals(IlManager.STATE_NODE, entry.state());
		assertNotNull(entry.ordering());
		int[][] orders = entry.ordering().keys().clone();
		// The v2 ordering table stores STRUCTURE, SECTION, STATEMENT, EFFECT, CONDITION, EXPRESSION.
		int effectKey = 3;
		assertEquals(Skript.instance().syntaxRegistry().syntaxes(SyntaxRegistry.EFFECT).size(), orders[effectKey].length);
		orders[effectKey] = new int[0];
		entries.put(key, entry.withOrdering(new IlSyntaxEnvironment.Ordering("different effect scope", orders)));
		IlFile.save(cacheFile, sourceHash, environment, entries);

		Variables.deleteVariable(CONSUMER_MARKER, null, false);
		var before = IlManager.stats();
		reloadFile(cold);
		var after = IlManager.stats();
		assertEquals("This site matched the STATEMENT registry", 1, after.replays() - before.replays());
		assertEquals(before.replayFailures(), after.replayFailures());
		assertEquals(Boolean.TRUE, global(CONSUMER_MARKER));
	}

	@Test
	public void pluralReturnAfterProviderReloadReportsTheSameErrorAsAColdConsumer() throws IOException {
		String name = "il_cardinality_" + UUID.randomUUID().toString().replace("-", "");
		Path provider = fixtureDirectory.resolve("cardinality-provider.sk");
		Path consumer = fixtureDirectory.resolve("cardinality-consumer.sk");
		Files.writeString(provider, "function " + name + "() :: number:\n\treturn 3\n");
		Script providerScript = loadFile(provider.toFile());
		trackFixtureCache(provider);
		String source = """
			on load:
				set {%s} to true
				loop %s() times:
					set {%s} to loop-value
			""".formatted(CONSUMER_MARKER, name, CONSUMER_VALUE);
		Files.writeString(consumer, source);
		Script consumerScript = loadFile(consumer.toFile());
		assertEquals(3L, globalNumber(CONSUMER_VALUE).longValue());
		trackFixtureCache(consumer);
		ScriptLoader.unloadScript(consumerScript);

		Files.writeString(provider, "function " + name + "() :: numbers:\n\treturn 2 and 3\n");
		reloadFile(providerScript);
		trackFixtureCache(provider);
		var liveSignatures = FunctionRegistry.getRegistry().getSignatures(null, name);
		assertEquals(1, liveSignatures.size());
		assertFalse(liveSignatures.iterator().next().isSingle());
		clearConsumerResults();
		long before = IlManager.stats().replays();
		List<String> replayErrors = loadErrors(consumer);
		assertEquals("The independent assignment must replay", 1, IlManager.stats().replays() - before);
		assertNull("The invalid loop body must not run", global(CONSUMER_VALUE));
		assertEquals(Boolean.TRUE, global(CONSUMER_MARKER));

		ScriptLoader.unloadScript(requireLoadedScript(consumer.toFile()));
		IlManager.setEnabledOverride(false);
		clearConsumerResults();
		List<String> coldErrors = loadErrors(consumer);
		assertTrue("Expected the consumer's invalid loop bound: " + coldErrors,
			coldErrors.stream().anyMatch(message -> message.contains(name)));
		assertEquals(coldErrors, replayErrors);
		assertNull(global(CONSUMER_VALUE));
		assertEquals(Boolean.TRUE, global(CONSUMER_MARKER));
		assertEquals(source, Files.readString(consumer));
	}

	private List<String> loadErrors(Path file) throws IOException {
		try (var log = SkriptLogger.startRetainingLog()) {
			loadFile(file.toFile());
			return log.getLog().stream()
				.filter(entry -> entry.getLevel().intValue() >= SkriptLogger.SEVERE.intValue())
				.map(LogEntry::getMessage).toList();
		}
	}

	@Test
	public void replaysAChangedFunctionBodyAndReparsesWhenAnExactOverloadAppears() throws IOException {
		String name = "il_overload_" + UUID.randomUUID().toString().replace("-", "");
		Path provider = fixtureDirectory.resolve("overload-provider.sk");
		Path consumer = fixtureDirectory.resolve("overload-consumer.sk");
		String listSignature = "function " + name + "(values: ints) :: int:\n\treturn ";
		Files.writeString(provider, listSignature + "7\n");
		Script providerScript = loadFile(provider.toFile());
		trackFixtureCache(provider);
		String source = """
			on load:
				set {%s} to true
				set {%s} to %s(1, 2)
			""".formatted(CONSUMER_MARKER, CONSUMER_VALUE, name);
		Files.writeString(consumer, source);
		Script consumerScript = loadFile(consumer.toFile());
		assertEquals(7L, globalNumber(CONSUMER_VALUE).longValue());
		trackFixtureCache(consumer);
		ScriptLoader.unloadScript(consumerScript);

		Files.writeString(provider, listSignature + "9\n");
		providerScript = reloadFile(providerScript);
		trackFixtureCache(provider);
		consumerScript = loadAndCheckConsumer(consumer, 9, 2);
		ScriptLoader.unloadScript(consumerScript);

		// Declare the exact overload first. Skript's duplicate check otherwise matches the existing varargs signature.
		Files.writeString(provider, "function " + name + "(x: int, y: int) :: int:\n\treturn 13\n\n" + listSignature + "9\n");
		reloadFile(providerScript);
		trackFixtureCache(provider);
		assertEquals(2, FunctionRegistry.getRegistry().getSignatures(null, name).size());
		IlManager.setEnabledOverride(false);
		try {
			Script coldControl = loadAndCheckConsumer(consumer, 13, 0);
			ScriptLoader.unloadScript(coldControl);
		} finally {
			IlManager.setEnabledOverride(true);
		}
		consumerScript = loadAndCheckConsumer(consumer, 13, 1);
		ScriptLoader.unloadScript(consumerScript);
		loadAndCheckConsumer(consumer, 13, 2);
		assertEquals(source, Files.readString(consumer));
	}

	private Script loadAndCheckConsumer(Path file, long expected, long expectedReplays) throws IOException {
		clearConsumerResults();
		long before = IlManager.stats().replays();
		Script script = loadFile(file.toFile());
		assertEquals(Boolean.TRUE, global(CONSUMER_MARKER));
		assertEquals(expected, globalNumber(CONSUMER_VALUE).longValue());
		assertEquals("Replayed consumer statements", expectedReplays, IlManager.stats().replays() - before);
		return script;
	}

	private static void clearConsumerResults() {
		Variables.deleteVariable(CONSUMER_VALUE, null, false);
		Variables.deleteVariable(CONSUMER_MARKER, null, false);
	}

	@Test
	public void replaysTwoRegistrationsOfTheSameEffectWithTheirOwnSuppliersAndPatterns() throws IOException {
		var registry = registrationAddon.syntaxRegistry();
		var origin = Origin.of(registrationAddon);
		List<String> calls = new ArrayList<>();
		var first = SyntaxInfo.builder(IlRegistrationProbe.class).origin(origin)
			.supplier(() -> new IlRegistrationProbe("first", calls))
			.addPatterns("il cache unused registration pattern", "il cache first registration [:tagged]").build();
		var second = SyntaxInfo.builder(IlRegistrationProbe.class).origin(origin)
			.supplier(() -> new IlRegistrationProbe("second", calls))
			.addPattern("il cache second registration").build();
		registry.register(SyntaxRegistry.EFFECT, first);
		registry.register(SyntaxRegistry.EFFECT, second);
		try {
			Path scriptFile = fixtureDirectory.resolve("registration-probes.sk");
			Files.writeString(scriptFile, """
				# %s
				on load:
					il cache first registration tagged
					il cache second registration
				""".formatted(UUID.randomUUID()));
			var script = loadFile(scriptFile.toFile());
			var expected = List.of("first/1/true", "second/0/false");
			assertEquals(expected, calls);
			trackFixtureCache(scriptFile);

			calls.clear();
			long before = IlManager.stats().replays();
			script = reloadFile(script);
			assertEquals(expected, calls);
			assertEquals("Both exact registrations must replay", 2, IlManager.stats().replays() - before);

			ScriptLoader.unloadScript(script);
			registry.unregister(SyntaxRegistry.EFFECT, second);
			calls.clear();
			List<String> replayErrors = loadErrors(scriptFile);
			assertEquals(List.of("first/1/true"), calls);
			assertTrue("The removed effect must fail to parse: " + replayErrors,
				replayErrors.stream().anyMatch(message -> message.contains("il cache second registration")));

			ScriptLoader.unloadScript(requireLoadedScript(scriptFile.toFile()));
			IlManager.setEnabledOverride(false);
			calls.clear();
			assertEquals(replayErrors, loadErrors(scriptFile));
			assertEquals(List.of("first/1/true"), calls);
		} finally {
			registry.unregister(SyntaxRegistry.EFFECT, second);
			registry.unregister(SyntaxRegistry.EFFECT, first);
		}
	}

	@Test
	public void sameNamedEffectsFromUnknownLoadersUseTheirOwnOriginalRegistrations() throws Exception {
		byte[] classBytes;
		try (var input = IlRegistrationProbe.class.getResourceAsStream("IlRegistrationProbe.class")) {
			assertNotNull(input);
			classBytes = input.readAllBytes();
		}
		class ProbeLoader extends ClassLoader {
			ProbeLoader() {
				super(IlRegistrationProbe.class.getClassLoader());
			}

			Class<? extends Effect> defineProbe() {
				return defineClass(IlRegistrationProbe.class.getName(), classBytes, 0, classBytes.length).asSubclass(Effect.class);
			}
		}
		var leftType = new ProbeLoader().defineProbe();
		var rightType = new ProbeLoader().defineProbe();
		assertEquals(IlRegistrationProbe.class.getName(), leftType.getName());
		assertEquals(leftType.getName(), rightType.getName());
		assertNotSame(IlRegistrationProbe.class, leftType);
		assertNotSame(leftType, rightType);
		assertNotSame(leftType.getClassLoader(), rightType.getClassLoader());
		List<String> calls = new ArrayList<>();
		var registry = registrationAddon.syntaxRegistry();
		var owned = loaderProbe(IlRegistrationProbe.class, "owned", calls);
		var left = loaderProbe(leftType, "left", calls);
		var right = loaderProbe(rightType, "right", calls);
		registry.register(SyntaxRegistry.EFFECT, owned);
		registry.register(SyntaxRegistry.EFFECT, left);
		registry.register(SyntaxRegistry.EFFECT, right);
		try {
			Path scriptFile = fixtureDirectory.resolve("loader-probes.sk");
			Files.writeString(scriptFile, """
				# %s
				on load:
					il cache loader owned
					il cache loader left
					il cache loader right
				""".formatted(UUID.randomUUID()));
			var expected = List.of("owned/0/false", "left/0/false", "right/0/false");
			var script = loadFile(scriptFile.toFile());
			assertEquals(expected, calls);
			trackFixtureCache(scriptFile);

			calls.clear();
			long before = IlManager.stats().replays();
			reloadFile(script);
			assertEquals(expected, calls);
			assertEquals("Only the registration owned by Skript's loader is replayable", 1, IlManager.stats().replays() - before);
		} finally {
			registry.unregister(SyntaxRegistry.EFFECT, right);
			registry.unregister(SyntaxRegistry.EFFECT, left);
			registry.unregister(SyntaxRegistry.EFFECT, owned);
		}
	}

	private static <T extends Effect> SyntaxInfo<T> loaderProbe(Class<T> type, String name, List<String> calls)
		throws NoSuchMethodException {
		var constructor = type.getConstructor(String.class, List.class);
		return SyntaxInfo.builder(type).origin(Origin.of(registrationAddon)).addPattern("il cache loader " + name).supplier(() -> {
			try {
				return constructor.newInstance(name, calls);
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError(exception);
			}
		}).build();
	}

	@Test
	public void replayIsFasterThanColdParse() throws Exception {
		String bigFixture = bigFixture(UUID.randomUUID().toString(), BIG_FIXTURE_ITERATIONS);
		int lineCount = (int) bigFixture.lines().count();
		Files.writeString(bigFixtureFile, bigFixture, StandardCharsets.UTF_8);

		try {
			// Cold parse: the fresh run id gives the file a new content hash, so this is guaranteed to be a miss.
			var beforeCold = IlManager.stats();
			long coldStart = System.nanoTime();
			Script coldScript = loadFile(bigFixtureFile.toFile());
			long coldNanos = System.nanoTime() - coldStart;
			var afterCold = IlManager.stats();

			// Reload from the freshly written IL files.
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
		return trackFixtureCache(sourceHash);
	}

	private Path trackFixtureCache(String sourceHash) throws IOException {
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
			for (String variable : new String[]{LOAD_COUNT, LOOP_VALUE, TRIGGER_FIRED, TRIGGER_DAMAGE, EXTRA_MARKER,
				MULTIPLICATION_RESULT, SUBTRACTION_RESULT, CONSUMER_VALUE, CONSUMER_MARKER, INPUT_DIGEST_VALUE}) {
				Variables.deleteVariable(variable, null, false);
			}
			IlManager.setEnabledOverride((Boolean) null);
			IlManager.resetStats();
		}
	}

}
