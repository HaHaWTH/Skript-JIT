package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.optimize.CoreOptimizations;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SkatalystCollectionLoopTest {

	private static final String LITERAL_COPIES = """
		loop "red", "green" and "blue":
			set {_last} to loop-value
			set {_copy} to loop-value
		""";

	@Test
	public void literalIteratorAndCurrentValueStayInTheGeneratedEntry() throws IOException {
		Trigger trigger = parse(LITERAL_COPIES);
		SecLoop loop = (SecLoop) trigger.skatalystItems().getFirst();
		var plan = CoreOptimizations.plan(trigger).get(loop);
		assertNotNull(plan);
		var entry = BytecodeCompiler.compile(trigger, Map.of(loop, plan)).entry(loop);
		assertNotNull(entry);
		Event event = ContextlessEvent.get();
		try {
			assertNull(entry.walk(event));
			assertEquals("blue", Variables.getVariable("last", event, true));
			assertEquals("blue", Variables.getVariable("copy", event, true));
			assertFalse(loop.skatalystHasIterator(event));
			assertNull(loop.getCurrent(event));
		} finally {
			Variables.removeLocals(event);
		}
	}

	@Test
	public void runtimeWorldCollectionUsesItsOriginalIteratorOrder() throws IOException {
		Trigger trigger = parse("""
			loop all worlds:
				set {_world} to loop-world
			""");
		SecLoop loop = (SecLoop) trigger.skatalystItems().getFirst();
		var plan = CoreOptimizations.plan(trigger).get(loop);
		assertNotNull(plan);
		var entry = BytecodeCompiler.compile(trigger, Map.of(loop, plan)).entry(loop);
		assertNotNull(entry);
		Event event = ContextlessEvent.get();
		try {
			assertNull(entry.walk(event));
			assertEquals(Bukkit.getWorlds().getLast(), Variables.getVariable("world", event, true));
			assertFalse(loop.skatalystHasIterator(event));
		} finally {
			Variables.removeLocals(event);
		}
	}

	@Test
	public void liveLinksAndActiveIteratorsUseTheOriginalContinuation() throws IOException {
		Trigger trigger = parse(LITERAL_COPIES);
		SecLoop loop = (SecLoop) trigger.skatalystItems().getFirst();
		var plan = CoreOptimizations.plan(trigger).get(loop);
		assertNotNull(plan);
		Trigger isolated = new Trigger(null, "isolated collection", new SimpleEvent(), List.of());
		var entry = BytecodeCompiler.compile(isolated, Map.of(isolated, plan)).entry(isolated);
		assertNotNull(entry);
		Event event = ContextlessEvent.get();
		TriggerItem first = loop.skatalystItems().getFirst();
		try {
			assertSame(first, TriggerItem.skatalystStep(loop, event));
			assertEquals("red", loop.getCurrent(event));
			assertSame(first, entry.walk(event));
			assertEquals("green", loop.getCurrent(event));
			loop.exit(event);
			first.setNext(null);
			assertSame(first, entry.walk(event));
			assertNull(TriggerItem.skatalystStep(first, event));
			assertEquals("red", Variables.getVariable("last", event, true));
			assertNull(Variables.getVariable("copy", event, true));
		} finally {
			loop.exit(event);
			Variables.removeLocals(event);
		}
	}

	@Test
	public void keyedPeekingAndInputMutationRetainTheOrdinaryLoop() throws IOException {
		for (String source : List.of("""
			loop {_things::*}:
				set {_last} to loop-index
			""", """
			loop {_things::*}:
				set {_last} to next loop-value
			""", """
			loop {_things::*}:
				delete {_things::%loop-index%}
			""")) {
			Trigger trigger = parse(source);
			assertFalse(CoreOptimizations.plan(trigger).containsKey(trigger.skatalystItems().getFirst()));
		}
	}

	private static Trigger parse(String source) throws IOException {
		return SkatalystExecutionTest.parseBody(source, body -> new Trigger(
			ParserInstance.get().getCurrentScript(), "collection fixture", new SimpleEvent(), ScriptLoader.loadItems(body)));
	}
}
