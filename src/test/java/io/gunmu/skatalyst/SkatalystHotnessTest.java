package io.gunmu.skatalyst;

import ch.njol.skript.config.Config;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.util.Kleenean;
import io.gunmu.skatalyst.compiler.CompiledCode;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.skriptlang.skript.lang.script.Script;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SkatalystHotnessTest {

	private static final int CALL_THRESHOLD = 128;
	private static final int BACKEDGE_THRESHOLD = 1024;

	@Test
	public void aHotTriggerPublishesAndUsesGeneratedCodeAutomatically() throws IOException {
		Probe body = new Probe((node, event) -> {
			event.bodyCalls++;
			event.recordGeneratedFrame();
		});
		Script owner = activeOwner();
		Trigger trigger = new Trigger(owner, "Skatalyst call threshold", new SimpleEvent(), List.of(body));
		ProbeEvent event = new ProbeEvent();
		try {
			for (int call = 1; call < CALL_THRESHOLD; call++)
				assertTrue(trigger.execute(event));
			assertNull(Skatalyst.compiledCode(trigger));
			assertTrue(trigger.execute(event));
			event.expectGeneratedFrame(awaitCompilation(trigger), body);
			assertTrue(trigger.execute(event));
			assertEquals(CALL_THRESHOLD + 1, event.bodyCalls);
			assertEquals(CALL_THRESHOLD + 1, event.firstGeneratedVisit);
		} finally {
			owner.invalidate();
		}
	}

	@Test
	public void compilationAtALoopBackedgeTakesEffectInTheSameInvocation() throws IOException {
		int iterations = BACKEDGE_THRESHOLD + 4;
		Probe prefix = new Probe((node, event) -> {
			assertNull(Skatalyst.compiledCode(Objects.requireNonNull(node.getTrigger())));
			event.prefixCalls++;
		});
		Probe body = new Probe((node, event) -> {
			event.bodyCalls++;
			if (event.bodyCalls == BACKEDGE_THRESHOLD + 1) {
				// The preceding interpreted backedge has submitted the compilation by this point.
				event.expectGeneratedFrame(awaitCompilation(Objects.requireNonNull(node.getTrigger())), node);
			}
			event.recordGeneratedFrame();
		});
		Probe suffix = new Probe((node, event) -> event.suffixCalls++);
		ProbeLoop loop = new ProbeLoop(body, iterations);
		prefix.setNext(loop);
		loop.setNext(suffix);
		Script owner = activeOwner();
		Trigger trigger = new Trigger(owner, "Skatalyst backedge threshold", new SimpleEvent(), List.of(prefix, loop, suffix));
		ProbeEvent event = new ProbeEvent();
		try {
			assertTrue(trigger.execute(event));
			assertEquals(1, event.prefixCalls);
			assertEquals(iterations, event.bodyCalls);
			assertEquals(1, event.suffixCalls);
			assertTrue("the original invocation never entered the generated loop",
				event.firstGeneratedVisit > BACKEDGE_THRESHOLD && event.firstGeneratedVisit <= iterations);
		} finally {
			owner.invalidate();
		}
	}

	private static Script activeOwner() throws IOException {
		Config config = new Config(new ByteArrayInputStream("# synthetic trigger\n".getBytes(StandardCharsets.UTF_8)),
			"skatalyst-hotness-test.sk", true, false, ":");
		Script script = new Script(config, List.of());
		script.skatalystScope().activate();
		return script;
	}

	private static CompiledCode awaitCompilation(Trigger trigger) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		do {
			CompiledCode code = Skatalyst.compiledCode(trigger);
			if (code != null)
				return code;
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		} while (System.nanoTime() - deadline < 0);
		throw new AssertionError("the compiler did not publish an entry for " + trigger.getName());
	}

	private static final class ProbeEvent extends Event {
		private static final HandlerList HANDLERS = new HandlerList();
		private static final StackWalker WALKER = StackWalker.getInstance(Set.of(
			StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
		private @Nullable Class<?> generatedClass;
		private int prefixCalls;
		private int bodyCalls;
		private int suffixCalls;
		private int firstGeneratedVisit;

		private void expectGeneratedFrame(CompiledCode code, TriggerItem item) {
			generatedClass = Objects.requireNonNull(code.entry(item)).chunk().getClass();
		}

		private void recordGeneratedFrame() {
			if (generatedClass != null && firstGeneratedVisit == 0 && WALKER.walk(frames ->
				frames.anyMatch(frame -> frame.getDeclaringClass() == generatedClass)))
				firstGeneratedVisit = bodyCalls;
		}

		@Override
		public @NotNull HandlerList getHandlers() {
			return HANDLERS;
		}
	}

	private static final class Probe extends TriggerItem {
		private final BiConsumer<Probe, ProbeEvent> callback;

		private Probe(BiConsumer<Probe, ProbeEvent> callback) {
			this.callback = callback;
		}

		@Override
		protected boolean run(Event event) {
			callback.accept(this, (ProbeEvent) event);
			return true;
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst probe";
		}
	}

	private static final class ProbeLoop extends LoopSection {
		private final Probe body;
		private final int iterations;

		private ProbeLoop(Probe body, int iterations) {
			this.body = body;
			this.iterations = iterations;
			setTriggerItems(List.of(body));
		}

		@Override
		public ProbeLoop setNext(@Nullable TriggerItem next) {
			super.setNext(next);
			body.setNext(this);
			return this;
		}

		@Override
		public @Nullable TriggerItem getActualNext() {
			return getNext();
		}

		@Override
		protected @Nullable TriggerItem walk(Event event) {
			return walk(event, ((ProbeEvent) event).bodyCalls < iterations);
		}

		@Override
		public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult,
							SectionNode sectionNode, List<TriggerItem> triggerItems) {
			throw new UnsupportedOperationException("synthetic loop");
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst probe loop";
		}
	}

}
