package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import io.gunmu.skatalyst.compiler.CompiledCode;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SkatalystReferenceSlotsTest {

	@Test
	public void spilledSlotsAreVisibleToAnAddonAndUnsetValuesDeoptBeforeAssignment() throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody("""
			set {_copy} to {_input}
			set {_before} to {_copy}
			set {_later} to {_copy}
			set {_result} to {_later}
			""", body -> {
			var items = ScriptLoader.loadItems(body);
			items.add(2, new ProbeEffect(event -> {
				event.before = Variables.getVariable("before", event, true);
				event.addonCalls++;
				Variables.setVariable("copy", "addon replacement", event, true);
			}));
			items.add(new ProbeEffect(event -> event.result = Variables.getVariable("result", event, true)));
			for (int index = 1; index < items.size(); index++)
				items.get(index - 1).setNext(items.get(index));
			return new Trigger(ParserInstance.get().getCurrentScript(), "Skatalyst reference slots test",
				new SimpleEvent(), items);
		});
		try {
			Result present = run(trigger, "portable value");
			assertEquals(new Result("portable value", "addon replacement", 1), present);
			Result absent = run(trigger, null);
			assertEquals(new Result(null, "addon replacement", 1), absent);

			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Skatalyst.compiledCode(trigger);
			assertNotNull(code);
			assertTrue("the first copy region was not optimized", code.optimized(trigger.skatalystItems().get(0)));
			assertTrue("the copy region after the addon was not optimized", code.optimized(trigger.skatalystItems().get(3)));
			assertEquals(present, run(trigger, "portable value"));
			assertEquals(absent, run(trigger, null));
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	@Test
	public void updatedInteriorSuccessorsKeepSkippedAssignmentsSkipped() throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody("""
			set {_copy} to {_input}
			set {_before} to {_copy}
			set {_result} to {_copy}
			""", body -> {
			var items = ScriptLoader.loadItems(body);
			ProbeEffect observe = new ProbeEffect(event -> {
				event.before = Variables.getVariable("before", event, true);
				event.result = Variables.getVariable("result", event, true);
			});
			items.getLast().setNext(observe);
			items.add(observe);
			return new Trigger(ParserInstance.get().getCurrentScript(), "Skatalyst live edge test",
				new SimpleEvent(), items);
		});
		TriggerItem first = trigger.skatalystItems().get(0);
		TriggerItem middle = trigger.skatalystItems().get(1);
		TriggerItem third = trigger.skatalystItems().get(2);
		try {
			first.setNext(third);
			Result interpreted = run(trigger, "portable value");
			assertEquals(new Result("stale snapshot", "portable value", 0), interpreted);

			first.setNext(middle);
			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Skatalyst.compiledCode(trigger);
			assertNotNull(code);
			assertTrue(code.optimized(first));
			first.setNext(third);
			assertEquals(interpreted, run(trigger, "portable value"));
		} finally {
			first.setNext(middle);
			Skatalyst.invalidate(trigger);
		}
	}

	@Test
	public void addonEscapeOnlyAffectsEventsThatActuallyEnterItsBranch() throws IOException {
		ProbeEffect addon = new ProbeEffect(event -> {
			assertTrue("locals must be exposed before calling the addon", Skatalyst.localsEscaped(event));
			event.before = Variables.getVariable("before", event, true);
			event.addonCalls++;
			Variables.setVariable("copy", "addon replacement", event, true);
		});
		addon.skatalystMarkExternalSyntax();

		ProbeEffect continuation = new ProbeEffect(event -> {
			event.result = Variables.getVariable("result", event, true);
			event.canBatchAfterBranch = Variables.skatalystCanBatchScalarWrites(event, List.of("later", "result"));
			if (event.continuationClass != null)
				event.sawCompiledContinuation = ProbeEvent.STACK.walk(frames -> frames.anyMatch(frame ->
					frame.getDeclaringClass() == event.continuationClass));
		});
		Trigger trigger = SkatalystExecutionTest.parseBody("""
			set {_copy} to {_input}
			set {_before} to {_copy}
			set {_later} to {_copy}
			set {_result} to {_later}
			""", body -> {
			var items = ScriptLoader.loadItems(body);
			items.add(2, new AddonBranch(addon));
			items.add(continuation);
			for (int index = 1; index < items.size(); index++)
				items.get(index - 1).setNext(items.get(index));
			return new Trigger(ParserInstance.get().getCurrentScript(), "Skatalyst external syntax test",
				new SimpleEvent(), items);
		});

		try {
			Result taken = new Result("initial value", "addon replacement", 1);
			Result skipped = new Result(null, "initial value", 0);
			assertBranch(trigger, true, taken, null);
			assertBranch(trigger, false, skipped, null);

			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Objects.requireNonNull(Skatalyst.compiledCode(trigger));
			assertTrue(code.optimized(trigger.skatalystItems().get(3)));
			Class<?> continuationClass = Objects.requireNonNull(code.entry(continuation)).chunk().getClass();
			assertBranch(trigger, true, taken, continuationClass);
			assertBranch(trigger, false, skipped, continuationClass);
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	private static void assertBranch(Trigger trigger, boolean visitsAddon, Result expected, @Nullable Class<?> continuationClass) {
		ProbeEvent event = new ProbeEvent();
		event.visitsAddon = visitsAddon;
		event.continuationClass = continuationClass;
		Variables.setVariable("input", "initial value", event, true);
		assertTrue(trigger.execute(event));
		assertEquals(expected, new Result(event.before, event.result, event.addonCalls));
		assertEquals(visitsAddon, Skatalyst.localsEscaped(event));
		assertEquals(!visitsAddon, event.canBatchAfterBranch);
		assertEquals(continuationClass != null, event.sawCompiledContinuation);
	}

	private static Result run(Trigger trigger, @Nullable String input) {
		ProbeEvent event = new ProbeEvent();
		Variables.setVariable("input", input, event, true);
		Variables.setVariable("copy", "stale copy", event, true);
		Variables.setVariable("before", "stale snapshot", event, true);
		assertTrue(trigger.execute(event));
		assertNull(Variables.copyLocalVariables(event));
		return new Result(event.before, event.result, event.addonCalls);
	}

	private record Result(@Nullable Object before, @Nullable Object result, int addonCalls) {}

	private static final class ProbeEvent extends Event {
		private static final HandlerList HANDLERS = new HandlerList();
		private static final StackWalker STACK = StackWalker.getInstance(Set.of(
			StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
		private @Nullable Object before;
		private @Nullable Object result;
		private int addonCalls;
		private boolean visitsAddon;
		private boolean canBatchAfterBranch;
		private @Nullable Class<?> continuationClass;
		private boolean sawCompiledContinuation;

		@Override
		public @NotNull HandlerList getHandlers() {
			return HANDLERS;
		}
	}

	private static final class AddonBranch extends TriggerSection {
		private AddonBranch(ProbeEffect addon) {
			super(List.of(addon));
		}

		@Override
		protected @Nullable TriggerItem walk(Event event) {
			return walk(event, ((ProbeEvent) event).visitsAddon);
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst conditional addon probe";
		}
	}

	private static final class ProbeEffect extends Effect {
		private final Consumer<ProbeEvent> callback;

		private ProbeEffect(Consumer<ProbeEvent> callback) {
			this.callback = callback;
		}

		@Override
		public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
			throw new UnsupportedOperationException("synthetic effect");
		}

		@Override
		protected void execute(Event event) {
			callback.accept((ProbeEvent) event);
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst addon probe";
		}
	}

}
