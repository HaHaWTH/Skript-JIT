package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import io.gunmu.skatalyst.compiler.optimize.CoreOptimizations;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.*;

public class SkatalystNumericRegionTest {
	private static final String SCALAR = """
		set {_x} to {_x} * 2 + 1
		add {_delta} to {_x}
		remove 2 from {_x}
		if {_x} > 8:
			set {_marker} to 1
		""";
	private static final String DYNAMIC_LOOP_BODY = """
		\tadd 1 to {_index}
		\tadd {_index} to {_total}
		""";

	@Test
	public void compiledAddAndRemoveHonorNewLongAndIntegerRhsCloners() throws IOException, ReflectiveOperationException {
		Fixture add = changeWithComparison("add {_step} to {_total}");
		Fixture remove = changeWithComparison("remove {_step} from {_total}");
		assertRhsCloner(add, remove, Long.class, 2L, 102L);
		assertRhsCloner(add, remove, Integer.class, 2, 102);
	}

	private static <T extends Number> void assertRhsCloner(Fixture add, Fixture remove, Class<T> type,
			T step, T replacement) throws ReflectiveOperationException {
		assertChange(add, step, 2.25, false);
		assertChange(remove, step, -1.75, false);
		withRestoredCloner(type, info -> {
			List<T> calls = new ArrayList<>();
			info.cloner(value -> {
				calls.add(value);
				return replacement;
			});
			assertChange(add, step, 102.25, true);
			assertChange(remove, step, -101.75, true);
			// The all-Double variant never passes an integral RHS to beforeChange.
			assertChange(add, 2.0, 2.25, false);
			assertEquals(List.of(step, step, step, step), calls);
		});
	}

	@Test
	public void setChecksTheEvaluatedRhsAndAddDoesNotCloneTheArithmeticResult() throws IOException, ReflectiveOperationException {
		Fixture set = changeWithComparison("set {_total} to {_step} / 2");
		Fixture add = changeWithComparison("add {_step} to {_total}");
		assertChange(set, 5L, 2.5, false);
		withRestoredCloner(Double.class, info -> {
			List<Double> calls = new ArrayList<>();
			info.cloner(value -> {
				calls.add(value);
				return value + 100.0;
			});
			assertChange(set, 5L, 102.5, true);
			assertChange(add, 2L, 2.25, false);
			assertEquals(List.of(2.5, 2.5), calls);
		});
	}

	@Test
	public void exactLongCopyKeepsItsFastPathWithAnIntegerCloner() throws IOException, ReflectiveOperationException {
		Fixture copy = changeWithComparison("set {_copy} to {_step}\nset {_total} to {_copy} + 0.25");
		withRestoredCloner(Integer.class, info -> {
			info.cloner(value -> {
				throw new AssertionError("the copied value is a Long");
			});
			assertChange(copy, 2L, 2.25, false);
		});
	}

	private static Fixture changeWithComparison(String statements) throws IOException {
		return fixture(statements + "\nif {_total} > -1000:\n\tset {_done} to true");
	}

	private static void assertChange(Fixture fixture, Number step, double expectedTotal, boolean fallsBack) {
		Event compiled = ContextlessEvent.get();
		Event interpreted = ContextlessEvent.get();
		try {
			for (Event event : List.of(compiled, interpreted)) {
				set(event, "step", step);
				set(event, "total", 0.25);
			}
			run(fixture.items.getFirst(), interpreted);
			TriggerSection comparison = (TriggerSection) fixture.items.getLast();
			TriggerItem resume = fixture.entry.walk(compiled);
			assertSame(fallsBack ? fixture.items.get(1) : comparison.skatalystItems().getFirst(), resume);
			assertEquals(expectedTotal, get(compiled, "total"));
			run(resume, compiled);
			assertEquals(get(interpreted, "total"), get(compiled, "total"));
			assertEquals(Boolean.TRUE, get(compiled, "done"));
		} finally {
			Variables.removeLocals(compiled);
			Variables.removeLocals(interpreted);
		}
	}

	@Test
	public void countedBoundCanInstallAClonerBeforeTheFirstIterationWithoutBeingReadAgain() throws IOException, ReflectiveOperationException {
		Fixture fixture = fixture("""
			loop {_bound} times:
				add loop-value to {_total}
			set {_done} to true
			""");
		SecLoop loop = (SecLoop) fixture.items.getFirst();
		withRestoredCloner(Long.class, info -> {
			Event compiled = ContextlessEvent.get();
			Event interpreted = ContextlessEvent.get();
			try {
				set(compiled, "bound", 3L);
				set(compiled, "total", 0.25);
				assertSame(fixture.items.getLast(), fixture.entry.walk(compiled));
				assertEquals(6.25, get(compiled, "total"));

				List<Long> calls = new ArrayList<>();
				AtomicInteger evaluations = new AtomicInteger();
				Number bound = new AtomicInteger(3) {
					@Override
					public double doubleValue() {
						assertEquals("times bound evaluated twice", 1, evaluations.incrementAndGet());
						info.cloner(value -> {
							calls.add(value);
							return value + 100L;
						});
						return super.doubleValue();
					}
				};
				set(compiled, "bound", bound);
				set(compiled, "total", 0.25);
				TriggerItem resume = fixture.entry.walk(compiled);
				assertSame(loop.skatalystItems().getFirst(), resume);
				assertEquals(1, evaluations.get());
				assertEquals(1L, loop.getCurrent(compiled));
				assertEquals(1L, loop.getLoopCounter(compiled));
				assertTrue(loop.skatalystHasIterator(compiled));
				assertEquals(0.25, get(compiled, "total"));
				assertTrue(calls.isEmpty());
				// Let debug formatting read a regular value. The resumed loop still uses the captured bound.
				set(compiled, "bound", 0L);
				run(resume, compiled);
				assertEquals(1, evaluations.get());
				assertEquals(List.of(1L, 2L, 3L), calls);
				assertEquals(306.25, get(compiled, "total"));
				assertFalse(loop.skatalystHasIterator(compiled));

				calls.clear();
				set(interpreted, "bound", 3L);
				set(interpreted, "total", 0.25);
				run(loop, interpreted);
				assertEquals(List.of(1L, 2L, 3L), calls);
				assertEquals(get(interpreted, "total"), get(compiled, "total"));
				assertEquals(get(interpreted, "done"), get(compiled, "done"));
			} finally {
				loop.exit(compiled);
				loop.exit(interpreted);
				Variables.removeLocals(compiled);
				Variables.removeLocals(interpreted);
			}
		});
	}

	private static <T> void withRestoredCloner(Class<T> type, Consumer<ClassInfo<T>> test) throws ReflectiveOperationException {
		ClassInfo<T> info = Classes.getExactClassInfo(type);
		assertNotNull(info);
		var original = info.getCloner();
		assertNull(original);
		var field = ClassInfo.class.getDeclaredField("cloner");
		field.setAccessible(true);
		try {
			test.accept(info);
		} finally {
			field.set(info, original);
		}
	}

	@Test
	public void mixedRuntimeInputsCompleteWhileAndCountedLoopsInOneEntry() throws IOException {
		for (String header : List.of("while {_index} < 4:", "loop 4 times:")) {
			Fixture fixture = fixture(header + "\n" + DYNAMIC_LOOP_BODY + "set {_done} to true\n");
			LoopSection loop = (LoopSection) fixture.items.getFirst();
			for (Number total : List.of(0L, 0.25)) {
				Event interpreted = ContextlessEvent.get();
				Event compiled = ContextlessEvent.get();
				try {
					for (Event event : List.of(interpreted, compiled)) {
						set(event, "index", 0L);
						set(event, "total", total);
					}
					run(loop, interpreted);
					assertSame("the whole loop should complete in its generated entry",
						fixture.items.getLast(), fixture.entry.walk(compiled));
					assertEquals(4L, get(compiled, "index"));
					assertEquals(get(interpreted, "total"), get(compiled, "total"));
					if (total instanceof Long) {
						assertEquals(10L, get(compiled, "total"));
					} else {
						assertEquals(10.25, get(compiled, "total"));
					}
				} finally {
					loop.exit(compiled);
					Variables.removeLocals(compiled);
					Variables.removeLocals(interpreted);
				}
			}
		}
	}

	@Test
	public void straightMixedAddAndItsRepresentationChangingFallbackMatchOriginal() throws IOException {
		Fixture fixture = fixture("""
			add {_delta} to {_total}
			add 1 to {_total}
			set {_done} to true
			""");
		for (Number delta : List.of(2L, 2.25)) {
			Event interpreted = ContextlessEvent.get();
			Event compiled = ContextlessEvent.get();
			try {
				for (Event event : List.of(interpreted, compiled)) {
					set(event, "delta", delta);
					if (delta instanceof Long) {
						set(event, "total", 1.25);
					} else {
						set(event, "total", 1L);
					}
				}
				run(fixture.items.getFirst(), interpreted);
				TriggerItem resume = fixture.entry.walk(compiled);
				if (delta instanceof Long) {
					assertSame(fixture.items.getLast(), resume);
				} else {
					assertSame(fixture.items.get(1), resume);
					assertEquals(3.25, get(compiled, "total"));
				}
				run(resume, compiled);
				assertEquals(4.25, get(compiled, "total"));
				assertEquals(get(interpreted, "total"), get(compiled, "total"));
				assertEquals(get(interpreted, "done"), get(compiled, "done"));
			} finally {
				Variables.removeLocals(compiled);
				Variables.removeLocals(interpreted);
			}
		}
	}

	@Test
	public void mixedLoopSpillsBothRepresentationsBeforeIntegralOverflow() throws IOException {
		Fixture fixture = fixture("""
			while {_index} < 3:
			\tadd 1 to {_index}
			\tadd {_index} to {_total}
			\tadd 1 to {_count}
			set {_done} to true
			""");
		LoopSection loop = (LoopSection) fixture.items.getFirst();
		Event interpreted = ContextlessEvent.get();
		Event compiled = ContextlessEvent.get();
		try {
			for (Event event : List.of(interpreted, compiled)) {
				set(event, "index", 0L);
				set(event, "total", 0.25);
				set(event, "count", Long.MAX_VALUE);
			}
			run(loop, interpreted);
			TriggerItem resume = fixture.entry.walk(compiled);
			assertSame(loop.skatalystItems().getLast(), resume);
			assertEquals(1L, get(compiled, "index"));
			assertEquals(1.25, get(compiled, "total"));
			assertEquals(Long.MAX_VALUE, get(compiled, "count"));
			assertEquals(1L, loop.getLoopCounter(compiled));
			run(resume, compiled);
			for (String name : List.of("index", "total", "count", "done")) {
				assertEquals(name, get(interpreted, name), get(compiled, name));
			}
		} finally {
			loop.exit(compiled);
			Variables.removeLocals(compiled);
			Variables.removeLocals(interpreted);
		}
	}

	@Test
	public void generatedVariantsReadEachInputOnceAndContainMixedArithmetic() throws ReflectiveOperationException, IOException {
		var constructor = BytecodeCompiler.Emitter.class.getDeclaredConstructor(MethodVisitor.class, MethodHandle.class);
		constructor.setAccessible(true);
		for (String source : List.of("add {_index} to {_total}", "while {_index} < 4:\n" + DYNAMIC_LOOP_BODY,
				"loop 4 times:\n" + DYNAMIC_LOOP_BODY)) {
			Fixture fixture = fixture(source);
			var instructions = new NumericInstructions();
			fixture.plan.emit(constructor.newInstance(instructions, TriggerItem.skatalystWalkHandle()));
			instructions.assertSharedReads(source);
			assertTrue(source, instructions.longAddition && instructions.doubleAddition && instructions.longToDouble);
		}
	}

	private static final class NumericInstructions extends MethodVisitor {
		private final List<Flow> flow = new ArrayList<>();
		private final Map<Label, Integer> labels = new IdentityHashMap<>();
		private boolean storingRead;
		private boolean longAddition;
		private boolean doubleAddition;
		private boolean longToDouble;

		private NumericInstructions() {
			super(ASM5);
		}

		@Override
		public void visitLdcInsn(Object value) {
			// The recorder only inspects instructions, so newer constant formats need no validation here.
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
			if (opcode == INVOKESTATIC && owner.equals("ch/njol/skript/variables/Variables") && name.equals("getVariable")) {
				storingRead = true;
			}
		}

		@Override
		public void visitVarInsn(int opcode, int slot) {
			if (storingRead) {
				assertEquals(ASTORE, opcode);
				flow.add(new Flow(slot, null, true));
				storingRead = false;
			}
		}

		@Override
		public void visitLabel(Label label) {
			labels.put(label, flow.size());
		}

		@Override
		public void visitJumpInsn(int opcode, Label target) {
			flow.add(new Flow(-1, target, opcode != GOTO));
		}

		@Override
		public void visitInsn(int opcode) {
			longAddition |= opcode == LADD;
			doubleAddition |= opcode == DADD;
			longToDouble |= opcode == L2D;
		}

		private void assertSharedReads(String source) {
			var rawReads = flow.stream().filter(instruction -> instruction.slot >= 0).toList();
			assertEquals(source, 2, rawReads.stream().mapToInt(Flow::slot).distinct().count());
			assertEquals(source, 3, rawReads.size());
			assertEquals(source, rawReads.get(1).slot, rawReads.getLast().slot);
			assertTrue(source, flow.subList(flow.indexOf(rawReads.getFirst()) + 1, flow.indexOf(rawReads.get(1)))
				.stream().anyMatch(instruction -> instruction.target != null));

			var pending = new ArrayDeque<Path>();
			var visited = new HashSet<Path>();
			pending.add(new Path(0, Set.of()));
			while (!pending.isEmpty()) {
				Path path = pending.removeFirst();
				if (path.index == flow.size() || !visited.add(path)) continue;
				Flow instruction = flow.get(path.index);
				Set<Integer> reads = path.reads;
				if (instruction.slot >= 0) {
					assertFalse(source, reads.contains(instruction.slot));
					var withRead = new HashSet<>(reads);
					withRead.add(instruction.slot);
					reads = Set.copyOf(withRead);
				}
				if (instruction.target != null) {
					pending.add(new Path(labels.get(instruction.target), reads));
				}
				if (instruction.fallsThrough) {
					pending.add(new Path(path.index + 1, reads));
				}
			}
		}

		private record Flow(int slot, @Nullable Label target, boolean fallsThrough) { }
		private record Path(int index, Set<Integer> reads) { }
	}

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

	private record Fixture(List<TriggerItem> items, CompiledCode.Entry entry, BytecodeCompiler.Optimization plan) {}

	private static Fixture fixture(String source) throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody(source, body -> new Trigger(ParserInstance.get().getCurrentScript(),
			"numeric fixture", new SimpleEvent(), ScriptLoader.loadItems(body)));
		var plan = CoreOptimizations.plan(trigger).get(trigger.skatalystItems().getFirst());
		assertNotNull("missing numeric region for " + source, plan);
		Trigger isolated = new Trigger(null, "isolated numeric region", new SimpleEvent(), List.of());
		var entry = BytecodeCompiler.compile(isolated, Map.of(isolated, plan)).entry(isolated);
		assertNotNull(entry);
		return new Fixture(trigger.skatalystItems(), entry, plan);
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
