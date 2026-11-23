package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.test.runner.SkriptAsyncJUnitTest;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.CompiledCode;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SkatalystDelayTest extends SkriptAsyncJUnitTest {

	private static final String OUTER = "-skatalyst-delay::outer";
	private static final String BODY = "-skatalyst-delay::body";
	private static final String RESUMED = "-skatalyst-delay::resumed";

	@Test
	public void waitEffectAndWaitSectionPreserveTheirSeparateLocalMaps() throws Exception {
		Trigger trigger = onServerThread(() -> SkatalystExecutionTest.parseBody("""
			set {_snapshot} to 11
			wait 1 tick:
			\tset {-skatalyst-delay::body} to {_snapshot}
			set {_snapshot} to 13
			set {-skatalyst-delay::outer} to {_snapshot}
			wait 2 ticks
			set {-skatalyst-delay::resumed} to {_snapshot}
			""", node -> new Trigger(ParserInstance.get().getCurrentScript(),
			"Skatalyst delay test", new SimpleEvent(), ScriptLoader.loadItems(node))));
		try {
			DelayResult interpreted = run(trigger);
			assertEquals(new DelayResult(13L, 11L, 13L, true, true), interpreted);
			assertTrue(Skatalyst.forceCompile(trigger));
			assertEquals(interpreted, run(trigger));
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	@Test
	public void aBackgroundWalkOnlyDisablesSlotBatchingForItsOwnEvent() throws Exception {
		Trigger async = onServerThread(() -> SkatalystExecutionTest.parseBody("set {_seed} to 1", body ->
			new Trigger(ParserInstance.get().getCurrentScript(), "Skatalyst async ingress",
				new SimpleEvent(), ScriptLoader.loadItems(body))));
		Trigger compiled = onServerThread(() -> SkatalystExecutionTest.parseBody("""
			set {_copy} to {_input}
			set {_answer} to {_copy}
			loop 3 times:
			\tset {_counter} to {_counter} + 1
			""", body -> new Trigger(ParserInstance.get().getCurrentScript(), "Skatalyst shared locals test",
			new SimpleEvent(), ScriptLoader.loadItems(body))));
		LocalWriteProbe shared = new LocalWriteProbe();
		LocalWriteProbe separate = new LocalWriteProbe();
		try {
			assertFalse("the async fixture ran on the primary thread", Bukkit.isPrimaryThread());
			assertTrue(TriggerItem.walk(async, shared));
			onServerThread(() -> {
				assertTrue(Skatalyst.forceCompile(compiled));
				CompiledCode code = Skatalyst.compiledCode(compiled);
				assertNotNull(code);
				assertTrue(code.optimized(compiled.skatalystItems().get(0)));
				assertTrue(code.optimized(compiled.skatalystItems().get(2)));

				List<String> names = List.of("copy", "answer", "counter");
				assertTrue(Skatalyst.localsEscaped(shared));
				assertFalse(Skatalyst.localsEscaped(separate));
				assertFalse(Variables.skatalystCanBatchScalarWrites(shared, names));
				assertTrue(Variables.skatalystCanBatchScalarWrites(separate, names));
				runWithLocals(compiled, shared);
				runWithLocals(compiled, separate);
				assertTrue("the shared event must execute the original writes", shared.sawOriginalWrites);
				assertFalse("a different event must still use slots", separate.sawOriginalWrites);
				return null;
			});
		} finally {
			Variables.removeLocals(shared);
			Variables.removeLocals(separate);
			Skatalyst.invalidate(compiled);
		}
	}

	private static void runWithLocals(Trigger trigger, LocalWriteProbe event) {
		Variables.setVariable("input", "retained", event, true);
		Variables.setVariable("counter", 4L, event, true);
		event.trackWrites = true;
		try {
			assertTrue(TriggerItem.walk(trigger, event));
		} finally {
			event.trackWrites = false;
		}
		assertEquals("retained", Variables.getVariable("answer", event, true));
		assertEquals(7L, Variables.getVariable("counter", event, true));
	}

	private static DelayResult run(Trigger trigger) throws Exception {
		Event event = ContextlessEvent.get();
		CompletableFuture<DelayResult> finished = new CompletableFuture<>();
		Skript plugin = Skript.getInstance();
		onServerThread(() -> {
			assertTrue(trigger.execute(event));
			Object immediateOuter = Variables.getVariable(OUTER, event, false);
			assertNull(Variables.getVariable(BODY, event, false));
			assertNull(Variables.getVariable(RESUMED, event, false));
			Bukkit.getScheduler().runTaskLater(plugin, () -> finished.complete(new DelayResult(
				immediateOuter, Variables.getVariable(BODY, event, false), Variables.getVariable(RESUMED, event, false),
				Delay.isDelayed(event), Variables.copyLocalVariables(event) == null)), 4);
			return null;
		});
		try {
			return finished.get(15, TimeUnit.SECONDS);
		} finally {
			onServerThread(() -> {
				for (String variable : new String[]{OUTER, BODY, RESUMED})
					Variables.setVariable(variable, null, event, false);
				return null;
			});
		}
	}

	private static <T> T onServerThread(Callable<T> action) throws Exception {
		return Bukkit.getScheduler().callSyncMethod(Skript.getInstance(), action).get(15, TimeUnit.SECONDS);
	}

	private record DelayResult(@Nullable Object immediateOuter, @Nullable Object sectionValue,
							   @Nullable Object resumedValue, boolean delayed, boolean localsRemoved) {}

	private static final class LocalWriteProbe extends Event {
		private static final HandlerList HANDLERS = new HandlerList();
		private static final StackWalker WALKER = StackWalker.getInstance(Set.of(
			StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
		private boolean trackWrites;
		private boolean sawOriginalWrites;

		@Override
		public int hashCode() {
			// The local-map access identifies the original effect without changing the script's values.
			if (trackWrites && !sawOriginalWrites)
				sawOriginalWrites = WALKER.walk(frames -> frames.anyMatch(frame ->
					frame.getDeclaringClass() == EffChange.class && frame.getMethodName().equals("execute")));
			return super.hashCode();
		}

		@Override
		public @NotNull HandlerList getHandlers() {
			return HANDLERS;
		}
	}

}
