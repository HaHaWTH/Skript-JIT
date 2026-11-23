package io.gunmu.skatalyst;

import ch.njol.skript.config.Config;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ReturnHandler;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.log.RetainingLogHandler;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.skriptlang.skript.lang.script.Script;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SkatalystExecutionTest {

	@Test
	public void countedLoopAgreesWithTheInterpreter() throws IOException {
		assertBothPaths(Number.class, new Number[]{1000L}, """
			set {_sum} to 0
			loop 1000 times:
			\tset {_sum} to {_sum} + 1
			return {_sum}
			""", 1);
	}

	@Test
	public void nativeCountedLoopReadsItsCurrentValueAndIteration() throws IOException {
		assertBothPaths(Number.class, new Number[]{231L}, """
			set {_sum} to 0
			loop 6 times:
			\tset {_value} to loop-value
			\tset {_iteration} to loop-iteration
			\tset {_sum} to {_sum} + {_value} * 10 + {_iteration}
			return {_sum}
			""", 1);
	}

	@Test
	public void integerOverflowPromotesToDoubleWithoutRepeatingEarlierUpdates() throws IOException {
		assertBothPaths(Number.class, new Number[]{Math.scalb(1.0, 63)}, """
			set {_updates} to 0
			set {_sum} to 9223372036854775806
			loop 3 times:
			\tset {_updates} to {_updates} + 1
			\tset {_sum} to {_sum} + 1
			if {_updates} is not 3:
			\treturn -1
			return {_sum}
			""", 2);
	}

	@Test
	public void decimalSlotsPreserveTheRoundedResultOfEachOperation() throws IOException {
		double expected = 1.25;
		for (int index = 0; index < 12; index++)
			expected = expected * 1.001 / 2.0;

		assertBothPaths(Number.class, new Number[]{expected}, """
			set {_weight} to 1.25
			loop 12 times:
			\tset {_weight} to {_weight} * 1.001 / 2.0
			return {_weight}
			""", 1);
	}

	@Test
	public void branchContinueAndExitUseTheirRealSuccessors() throws IOException {
		assertBothPaths(Number.class, new Number[]{4L}, """
			set {_total} to 0
			loop 5 times:
			\tif loop-number is 2:
			\t\tcontinue
			\tif loop-number is 4:
			\t\texit loop
			\tset {_total} to {_total} + loop-number
			return {_total}
			""", 1, 0);
	}

	@Test
	public void conditionalAtTheEndOfAWhileBodyStaysInOneGeneratedEntry() throws IOException {
		ReturnCapture<Number> capture = new ReturnCapture<>(Number.class);
		Trigger trigger = parseBody("""
			set {_index} to 0
			set {_total} to 0
			while {_index} < 6:
			\tadd 1 to {_index}
			\tif {_index} <= 3:
			\t\tadd 10 to {_total}
			\telse:
			\t\tadd 100 to {_total}
			return {_total}
			""", body -> capture.loadReturnableTrigger(body, "Skatalyst while branch test", new SimpleEvent()));
		CompiledCode.Entry entry = BytecodeCompiler.compile(trigger).entry(trigger);
		assertNotNull(entry);
		Event event = ContextlessEvent.get();
		try {
			assertNull("the generated entry returned before finishing the trigger", entry.walk(event));
			assertArrayEquals(new Number[]{330L}, capture.result);
		} finally {
			Variables.removeLocals(event);
		}
		Number[] compiled = capture.result;
		capture.result = null;
		assertTrue(trigger.execute(ContextlessEvent.get()));
		assertArrayEquals(compiled, capture.result);
	}

	@Test
	public void dynamicNamesCanObserveWritesToStaticLocals() throws IOException {
		assertBothPaths(String.class, new String[]{"overwritten/after"}, """
			set {_fixed} to "before"
			set {_name} to "fixed"
			set {_%{_name}%} to "overwritten"
			set {_other} to "after"
			set {_out} to "%{_fixed}%/%{_other}%"
			return {_out}
			""", 4);
	}

	@Test
	public void objectSlotsRespectBothVariableCasingModes() throws IOException {
		String script = """
			set {_Mixed} to "capital"
			set {_mixed} to "lower"
			set {_copy} to {_mixed}
			set {_result} to {_copy}
			return "%{_Mixed}%/%{_result}%"
			""";
		boolean original = Variables.caseInsensitiveVariables;
		try {
			Variables.caseInsensitiveVariables = true;
			assertBothPaths(String.class, new String[]{"lower/lower"}, script, 1);

			Variables.caseInsensitiveVariables = false;
			assertBothPaths(String.class, new String[]{"capital/lower"}, script, 1);
		} finally {
			Variables.caseInsensitiveVariables = original;
		}
	}

	@Test
	public void numericComparisonPreservesWrappingEpsilonAndMissingValues() throws IOException {
		assertComparison("{_left} > {_right}", List.of(
			new ComparisonCase(Long.MIN_VALUE, 1L, true),
			new ComparisonCase(1L, 2L, false),
			new ComparisonCase(Math.nextUp(1.0), 1.0, false),
			new ComparisonCase(Double.POSITIVE_INFINITY, 1.0, true),
			new ComparisonCase(null, 1L, false)
		));
	}

	@Test
	public void numericComparisonKeepsIdentityAndNonnumericFallback() throws IOException {
		Double sharedNaN = Double.NaN;
		assertComparison("{_left} is {_right}", List.of(
			new ComparisonCase(sharedNaN, sharedNaN, true),
			new ComparisonCase("value", "value".substring(0, 4) + "e", true),
			new ComparisonCase("value", "other", false),
			new ComparisonCase(1.0, null, false)
		));
	}

	private static void assertComparison(String condition, List<ComparisonCase> cases) throws IOException {
		ReturnCapture<Number> capture = new ReturnCapture<>(Number.class);
		Trigger trigger = parseBody(condition + "\nreturn 42\n",
			body -> capture.loadReturnableTrigger(body, "Skatalyst comparison test", new SimpleEvent()));
		try {
			for (ComparisonCase testCase : cases)
				assertComparisonResult(trigger, capture, testCase);
			assertNull(Skatalyst.compiledCode(trigger));
			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Skatalyst.compiledCode(trigger);
			assertNotNull(code);
			assertTrue("standalone comparison did not receive a specialized emitter",
				code.optimized(trigger.skatalystItems().getFirst()));
			for (ComparisonCase testCase : cases)
				assertComparisonResult(trigger, capture, testCase);
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	private static void assertComparisonResult(Trigger trigger, ReturnCapture<Number> capture, ComparisonCase testCase) {
		Event event = ContextlessEvent.get();
		Variables.setVariable("left", testCase.left(), event, true);
		Variables.setVariable("right", testCase.right(), event, true);
		capture.result = null;
		assertTrue(trigger.execute(event));
		assertArrayEquals(testCase.toString(), testCase.matches() ? new Number[]{42L} : null, capture.result);
	}

	private record ComparisonCase(@Nullable Object left, @Nullable Object right, boolean matches) {}

	private static <T> void assertBothPaths(Class<T> type, T[] expected, String source, int... optimizedPath) throws IOException {
		ReturnCapture<T> capture = new ReturnCapture<>(type);
		Trigger trigger = parseBody(source, body -> capture.loadReturnableTrigger(body, "Skatalyst test", new SimpleEvent()));
		try {
			Event interpretedEvent = ContextlessEvent.get();
			assertTrue(trigger.execute(interpretedEvent));
			assertNull(Skatalyst.compiledCode(trigger));
			T[] interpreted = capture.result;
			assertArrayEquals(expected, interpreted);

			capture.result = null;
			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Skatalyst.compiledCode(trigger);
			assertNotNull(code);
			TriggerItem optimized = trigger;
			for (int index : optimizedPath)
				optimized = ((TriggerSection) optimized).skatalystItems().get(index);
			assertTrue("the expected node was not optimized: " + optimized, code.optimized(optimized));
			Event compiledEvent = ContextlessEvent.get();
			assertTrue(trigger.execute(compiledEvent));
			assertArrayEquals(interpreted, capture.result);
			assertNull(Variables.copyLocalVariables(compiledEvent));
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	static <T> T parseBody(String source, Function<SectionNode, T> parse) throws IOException {
		String scriptSource = "fixture:\n\t" + source.stripTrailing().replace("\n", "\n\t") + "\n";
		Config config = new Config(new ByteArrayInputStream(scriptSource.getBytes(StandardCharsets.UTF_8)),
			"skatalyst-test.sk", true, false, ":");
		SectionNode body = (SectionNode) config.getMainNode().get("fixture");
		assertNotNull(body);

		ParserInstance parser = ParserInstance.get();
		parser.setActive(new Script(config, List.of()));
		try (RetainingLogHandler log = SkriptLogger.startRetainingLog()) {
			parser.setCurrentEvent("Skatalyst test", ContextlessEvent.class);
			T value = parse.apply(body);
			assertTrue(log.getLog().toString(), !log.hasErrors());
			return value;
		} finally {
			parser.setInactive();
		}
	}

	private static final class ReturnCapture<T> implements ReturnHandler<T> {
		private final Class<T> type;
		private T[] result;

		private ReturnCapture(Class<T> type) {
			this.type = type;
		}

		@Override
		public void returnValues(Event event, Expression<? extends T> value) {
			result = value.getArray(event);
		}

		@Override
		public boolean isSingleReturnValue() {
			return true;
		}

		@Override
		public Class<T> returnValueType() {
			return type;
		}
	}

}
