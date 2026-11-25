package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.sections.SecWhile;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import io.gunmu.skatalyst.compiler.optimize.CoreOptimizations;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SkatalystWhileTest {

	private static final String BRANCH = """
		while {_i} is less than 16:
		\tadd 1 to {_i}
		\tif {_i} is greater than 8:
		\t\tadd {_i} to {_value}
		\telse:
		\t\tadd 1 to {_value}
		""";

	@Test
	public void wholeBranchBenchmarkRunsInTheScalarHeader() throws IOException {
		Fixture fixture = fixture(BRANCH);
		TriggerItem takenTail = ((TriggerSection) fixture.loop.skatalystItems().get(1)).skatalystItems().getLast();
		TriggerItem elseTail = ((TriggerSection) fixture.loop.skatalystItems().get(2)).skatalystItems().getLast();
		takenTail.setNext(null);
		elseTail.setNext(null);
		Event event = ContextlessEvent.get();
		try {
			seed(event, 15L, 0L);
			assertNull(fixture.entry.walk(event));
			assertEquals(16L, local(event, "value"));
			assertSame(fixture.loop, takenTail.getActualNext());
			assertNull("an untaken arm must keep its successor", elseTail.getActualNext());

			seed(event, 0L, 0L);
			assertNull("a body item escaped from the scalar header", fixture.entry.walk(event));
			assertEquals(16L, local(event, "i"));
			assertEquals(108L, local(event, "value"));
			assertSame(fixture.loop, elseTail.getActualNext());

			Long untouched = 12345L;
			seed(event, 16L, untouched);
			assertNull(fixture.entry.walk(event));
			assertSame(untouched, local(event, "value"));
		} finally {
			Variables.removeLocals(event);
		}
	}

	@Test
	public void promotionResumesTheExactAddWithTheOriginalIterationAndBranchTail() throws IOException {
		Fixture fixture = fixture("""
			do while {_i} is less than 2:
			\tadd 1 to {_i}
			\tif {_i} is greater than 1:
			\t\tadd 1 to {_value}
			\t\tadd 7 to {_seen}
			\telse:
			\t\tadd 1 to {_seen}
			\tadd 5 to {_seen}
			""");
		List<TriggerItem> body = fixture.loop.skatalystItems();
		List<TriggerItem> arm = ((TriggerSection) body.get(1)).skatalystItems();
		for (boolean alreadyActive : List.of(false, true)) {
			Event event = ContextlessEvent.get();
			Event interpreted = ContextlessEvent.get();
			try {
				for (Event e : List.of(event, interpreted)) {
					seed(e, 0L, Long.MAX_VALUE);
					Variables.setVariable("seen", 0L, e, true);
				}
				run(fixture.loop, interpreted);
				if (alreadyActive) {
					TriggerItem item = TriggerItem.skatalystStep(fixture.loop, event);
					while (item != fixture.loop)
						item = TriggerItem.skatalystStep(item, event);
				}
				arm.getLast().setNext(null);
				TriggerItem resume = fixture.entry.walk(event);
				assertSame(arm.getFirst(), resume);
				assertSame(body.getLast(), arm.getLast().getActualNext());
				assertEquals(2L, fixture.loop.getLoopCounter(event));
				assertEquals(2L, local(event, "i"));
				assertEquals(6L, local(event, "seen"));
				assertEquals(Long.MAX_VALUE, local(event, "value"));
				run(resume, event);
				assertEquals(Math.scalb(1.0, 63), local(event, "value"));
				assertEquals(18L, local(event, "seen"));
				for (String name : List.of("i", "value", "seen"))
					assertEquals(local(interpreted, name), local(event, name));
			} finally {
				Variables.removeLocals(interpreted);
				Variables.removeLocals(event);
			}
		}
	}

	@Test
	public void incompatibleOrExposedLocalsFallBackBeforeTheFirstEffect() throws IOException {
		Fixture fixture = fixture(BRANCH);
		for (Object input : new Object[]{null, (short) 7, 1.5f, "text", 42L}) {
			Event event = ContextlessEvent.get();
			Event interpreted = ContextlessEvent.get();
			try {
				seed(interpreted, 15L, input);
				seed(event, 15L, input);
				if (input instanceof Long)
					Skatalyst.exposeLocals(event);
				run(fixture.loop, interpreted);
				TriggerItem first = fixture.entry.walk(event);
				assertSame(fixture.loop.skatalystItems().getFirst(), first);
				assertEquals(15L, local(event, "i"));
				assertSame(input, local(event, "value"));
				run(first, event);
				assertEquals(local(interpreted, "value"), local(event, "value"));
				assertEquals(16L, local(event, "i"));
			} finally {
				Variables.removeLocals(interpreted);
				Variables.removeLocals(event);
			}
		}
	}

	@Test
	public void rewiredInteriorEdgeUsesTheLiveGraph() throws IOException {
		Fixture fixture = fixture(BRANCH);
		TriggerItem first = fixture.loop.skatalystItems().getFirst();
		first.setNext(null);
		Event event = ContextlessEvent.get();
		try {
			seed(event, 0L, 0L);
			assertSame(first, fixture.entry.walk(event));
			run(first, event);
			assertEquals(1L, local(event, "i"));
			assertEquals(0L, local(event, "value"));
		} finally {
			fixture.loop.exit(event);
			Variables.removeLocals(event);
		}
	}

	@Test
	public void doWhileRunsOnceAndUsesTheWrappedLongComparison() throws IOException {
		Fixture fixture = fixture("""
			do while {_i} is less than 0:
			\tadd 1 to {_i}
			\tif {_left} is not less than {_right}:
			\t\tadd 3 to {_value}
			""");
		Event event = ContextlessEvent.get();
		try {
			seed(event, 0L, 0L);
			Variables.setVariable("left", Long.MIN_VALUE, event, true);
			Variables.setVariable("right", 1L, event, true);
			for (long attempt = 1; attempt <= 2; attempt++) {
				assertNull(fixture.entry.walk(event));
				assertEquals(attempt, local(event, "i"));
				assertEquals(attempt * 3, local(event, "value"));
			}
		} finally {
			Variables.removeLocals(event);
		}
	}

	private record Fixture(SecWhile loop, CompiledCode.Entry entry) {}

	private static Fixture fixture(String source) throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody(source, body -> new Trigger(
			ParserInstance.get().getCurrentScript(), "while fixture", new SimpleEvent(), ScriptLoader.loadItems(body)));
		var loop = (SecWhile) trigger.skatalystItems().getFirst();
		var plan = CoreOptimizations.plan(trigger).get(loop);
		assertNotNull("the parsed while has no scalar plan", plan);
		// Isolate the real plan so ordinary dispatch cannot silently execute a body item for it.
		Trigger isolated = new Trigger(null, "isolated header", new SimpleEvent(), List.of());
		var entry = BytecodeCompiler.compile(isolated, Map.of(isolated, plan)).entry(isolated);
		assertNotNull(entry);
		return new Fixture(loop, entry);
	}

	private static void run(@Nullable TriggerItem item, Event event) {
		while (item != null)
			item = TriggerItem.skatalystStep(item, event);
	}

	private static void seed(Event event, Long index, @Nullable Object value) {
		Variables.setVariable("i", index, event, true);
		Variables.setVariable("value", value, event, true);
	}

	private static @Nullable Object local(Event event, String name) {
		return Variables.getVariable(name, event, true);
	}
}
