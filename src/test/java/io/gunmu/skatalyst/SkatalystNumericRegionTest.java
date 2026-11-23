package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.sections.SecLoop;
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

public class SkatalystNumericRegionTest {
	private static final String SCALAR = """
		set {_x} to {_x} * 2 + 1
		add {_delta} to {_x}
		remove 2 from {_x}
		if {_x} > 8:
			set {_marker} to 1
		""";

	@Test
	public void straightArithmeticReturnsTheContinuationOfTheFusedComparison() throws IOException {
		Fixture fixture = fixture(SCALAR);
		var selected = ((TriggerSection) fixture.items.getLast()).skatalystItems().getFirst();
		Event event = ContextlessEvent.get();
		try {
			set(event, "x", 4L);
			set(event, "delta", 3L);
			assertSame(selected, fixture.entry.walk(event));
			assertEquals(10L, get(event, "x"));

			set(event, "x", 2.5);
			set(event, "delta", 1.25);
			assertNull(fixture.entry.walk(event));
			assertEquals(5.25, get(event, "x"));

			Skatalyst.exposeLocals(event);
			set(event, "x", 4L);
			assertSame(fixture.items.get(1), fixture.entry.walk(event));
			assertEquals(9L, get(event, "x"));
		} finally {
			Variables.removeLocals(event);
		}
	}

	@Test
	public void straightPromotionWalksAnOverflowingHeadOnceAndSpillsBeforeALaterOverflow() throws IOException {
		Fixture fixture = fixture(SCALAR);
		Event event = ContextlessEvent.get();
		try {
			set(event, "x", Long.MAX_VALUE);
			set(event, "delta", 1L);
			assertSame(fixture.items.get(1), fixture.entry.walk(event));
			assertEquals((double) Long.MAX_VALUE * 2 + 1, get(event, "x"));

			set(event, "x", 1L);
			set(event, "delta", Long.MAX_VALUE);
			assertSame(fixture.items.get(1), fixture.entry.walk(event));
			assertEquals(3L, get(event, "x"));
			run(fixture.items.get(1), event);
			assertEquals(3.0 + Long.MAX_VALUE - 2, get(event, "x"));

			fixture.items.get(1).setNext(null);
			set(event, "x", 1L);
			set(event, "delta", 1L);
			assertSame(fixture.items.get(1), fixture.entry.walk(event));
			assertEquals(3L, get(event, "x"));
		} finally {
			Variables.removeLocals(event);
		}
	}

	@Test
	public void bareComparisonKeepsSharedNaNAndUsesTheBuiltInDecimalRelation() throws IOException {
		Fixture fixture = fixture("""
			set {_copy} to {_left}
			add 0.25 to {_n}
			{_copy} is {_right}
			set {_marker} to 1
			""");
		Double nan = Double.valueOf(Double.NaN);
		for (Double[] pair : List.of(new Double[]{nan, nan},
				new Double[]{nan, Double.longBitsToDouble(0x7ff8000000000001L)},
				new Double[]{Math.nextUp(1.0), 1.0}, new Double[]{-0.0, 0.0},
				new Double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY})) {
			Event event = ContextlessEvent.get();
			Event interpreted = ContextlessEvent.get();
			try {
				for (Event e : List.of(event, interpreted)) {
					set(e, "n", 1.0);
					set(e, "left", pair[0]);
					set(e, "right", pair[1]);
				}
				run(fixture.items.getFirst(), interpreted);
				assertSame(get(interpreted, "marker") == null ? null : fixture.items.getLast(), fixture.entry.walk(event));
				assertSame(pair[0], get(event, "copy"));
				assertEquals(1.25, get(event, "n"));
			} finally {
				Variables.removeLocals(event);
				Variables.removeLocals(interpreted);
			}
		}
	}

	@Test
	public void countedAndWhileHeadersFinishNestedContinueExitAndMixedDecimalUpdates() throws IOException {
		String body = """
			set {_i} to {_i} + 1
			if {_i} < 3:
				if {_i} is 1:
					continue
				else if {_i} is 2:
					remove 0.5 from {_score}
			else:
				if {_i} is 6:
					exit loop
				set {_score} to {_score} * 1.5 + 0.25
			add 2 to {_seen}
			""";
		for (String header : List.of("while {_i} < 8:", "loop 8 times:")) {
			Fixture fixture = fixture(header + "\n\t" + body.stripTrailing().replace("\n", "\n\t"));
			LoopSection loop = (LoopSection) fixture.items.getFirst();
			Event event = ContextlessEvent.get();
			Event interpreted = ContextlessEvent.get();
			try {
				for (Event e : List.of(event, interpreted)) {
					set(e, "i", 0L);
					set(e, "score", 4.0);
					set(e, "seen", 0L);
				}
				run(loop, interpreted);
				assertNull("the header returned a body item", fixture.entry.walk(event));
				for (String name : List.of("i", "score", "seen"))
					assertEquals(get(interpreted, name), get(event, name));
				assertEquals(6L, get(event, "i"));
				assertEquals(8L, get(event, "seen"));
				assertEquals(1L, loop.getLoopCounter(event));
				if (loop instanceof SecLoop counted) {
					assertFalse(counted.skatalystHasIterator(event));
					assertNull(counted.getCurrent(event));
				}
			} finally {
				Variables.removeLocals(event);
				Variables.removeLocals(interpreted);
			}
		}
	}

	@Test
	public void countedElseIfUsesThePrimitiveIterationAndStopsAtTheLiveSuccessor() throws IOException {
		Fixture fixture = fixture("""
			loop {_n} times:
				if loop-iteration < 4:
					continue
				else if loop-iteration > 16:
					exit loop
				add loop-value to {_sum}
			set {_finished} to true
			""");
		SecLoop loop = (SecLoop) fixture.items.getFirst();
		Event event = ContextlessEvent.get();
		try {
			set(event, "n", 100L);
			set(event, "sum", 0L);
			assertSame(fixture.items.getLast(), fixture.entry.walk(event));
			assertEquals(130L, get(event, "sum"));
			assertFalse(loop.skatalystHasIterator(event));

			set(event, "sum", 0L);
			TriggerItem first = TriggerItem.skatalystStep(loop, event);
			assertSame(loop.skatalystItems().getFirst(), first);
			assertSame(first, fixture.entry.walk(event));
			assertEquals(2L, loop.getCurrent(event));
			run(first, event);
			assertEquals(130L, get(event, "sum"));
		} finally {
			loop.exit(event);
			Variables.removeLocals(event);
		}
	}

	@Test
	public void nestedSideExitRestoresTakenTailsAndTheAlreadyActiveWhileCounter() throws IOException {
		Fixture fixture = fixture("""
			do while {_i} < 3:
				set {_i} to {_i} + 1
				if {_i} > 0:
					if {_i} > 1:
						set {_value} to {_value} * 2
						set {_last} to loop-iteration
					else:
						set {_last} to loop-iteration
				add 1 to {_seen}
			""");
		LoopSection loop = (LoopSection) fixture.items.getFirst();
		List<TriggerItem> body = loop.skatalystItems();
		var inner = (TriggerSection) ((TriggerSection) body.get(1)).skatalystItems().getFirst();
		for (boolean active : List.of(false, true)) {
			Event event = ContextlessEvent.get();
			try {
				set(event, "i", 0L);
				set(event, "seen", 0L);
				set(event, "value", Long.MAX_VALUE);
				if (active) {
					TriggerItem item = TriggerItem.skatalystStep(loop, event);
					while (item != loop)
						item = TriggerItem.skatalystStep(item, event);
				}
				inner.skatalystItems().getLast().setNext(null);
				TriggerItem resume = fixture.entry.walk(event);
				assertSame(inner.skatalystItems().getFirst(), resume);
				assertSame(body.getLast(), inner.skatalystItems().getLast().getActualNext());
				assertEquals(2L, loop.getLoopCounter(event));
				assertEquals(1L, get(event, "seen"));
				assertEquals(1L, get(event, "last"));
				run(resume, event);
				assertEquals(3L, get(event, "seen"));
				assertEquals(3L, get(event, "last"));
				assertEquals((double) Long.MAX_VALUE * 4, get(event, "value"));
			} finally {
				loop.exit(event);
				Variables.removeLocals(event);
			}
		}
	}

	private record Fixture(List<TriggerItem> items, CompiledCode.Entry entry) {}

	private static Fixture fixture(String source) throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody(source, body -> new Trigger(ParserInstance.get().getCurrentScript(),
			"numeric fixture", new SimpleEvent(), ScriptLoader.loadItems(body)));
		var plan = CoreOptimizations.plan(trigger).get(trigger.skatalystItems().getFirst());
		assertNotNull("missing numeric region for " + source, plan);
		Trigger isolated = new Trigger(null, "isolated numeric region", new SimpleEvent(), List.of());
		var entry = BytecodeCompiler.compile(isolated, Map.of(isolated, plan)).entry(isolated);
		assertNotNull(entry);
		return new Fixture(trigger.skatalystItems(), entry);
	}

	private static void run(@Nullable TriggerItem item, Event event) {
		while (item != null)
			item = TriggerItem.skatalystStep(item, event);
	}

	private static void set(Event event, String name, Object value) {
		Variables.setVariable(name, value, event, true);
	}

	private static @Nullable Object get(Event event, String name) {
		return Variables.getVariable(name, event, true);
	}
}
