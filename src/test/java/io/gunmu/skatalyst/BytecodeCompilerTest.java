package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.SimpleEvent;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.script.Script;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.NOP;
import static org.objectweb.asm.Opcodes.POP;

public class BytecodeCompilerTest {

	@Test
	public void generatesHiddenClassAndSupportsEntryAtAnInteriorNode() {
		RecordingItem first = new RecordingItem("first");
		RecordingItem second = new RecordingItem("second");
		RecordingItem third = new RecordingItem("third");
		Trigger trigger = trigger(first, second, third);
		CompiledCode code = BytecodeCompiler.compile(trigger);

		CompiledCode.Entry start = code.entry(trigger);
		assertNotNull(start);
		assertTrue(start.chunk().getClass().isHidden());
		RecordingEvent fromStart = new RecordingEvent();
		assertNull(start.walk(fromStart));
		assertEquals(List.of("first", "second", "third"), fromStart.trace);

		CompiledCode.Entry resume = code.entry(second);
		assertNotNull(resume);
		RecordingEvent fromInterior = new RecordingEvent();
		assertNull(resume.walk(fromInterior));
		assertEquals(List.of("second", "third"), fromInterior.trace);
	}

	@Test
	public void poolsLocalNamesByValueAndPreservesScriptConstantIdentity() {
		String name = new String("total");
		String equalName = new String("total");
		List<String> writes = List.of(name, "i");
		List<String> equalWrites = List.of(equalName, "i");
		List<String> reversedWrites = List.of("i", name);
		var function = BytecodeCompiler.compileFunction(List.of(), null, emitter -> {
			var method = emitter.method();
			emitter.loadLocalName(name);
			method.visitInsn(POP);
			emitter.loadLocalName(equalName);
			method.visitInsn(POP);
			for (List<String> names : List.of(writes, equalWrites, reversedWrites)) {
				emitter.loadScalarWriteNames(names);
				method.visitInsn(POP);
			}
			emitter.loadConstant(name, Object.class);
			method.visitInsn(POP);
			emitter.loadConstant(equalName, Object.class);
			method.visitInsn(POP);

			// return MethodHandles.classData(MethodHandles.lookup(), "_", List.class);
			Type lookup = Type.getType(MethodHandles.Lookup.class);
			String handles = Type.getInternalName(MethodHandles.class);
			method.visitMethodInsn(INVOKESTATIC, handles, "lookup", Type.getMethodDescriptor(lookup), false);
			method.visitLdcInsn("_");
			method.visitLdcInsn(Type.getType(List.class));
			method.visitMethodInsn(INVOKESTATIC, handles, "classData", Type.getMethodDescriptor(Type.getType(Object.class),
				lookup, Type.getType(String.class), Type.getType(Class.class)), false);
			method.visitInsn(ARETURN);
		});

		var body = function.body();
		assertNotNull(body);
		List<?> data = (List<?>) body.invoke(null, null, null, null);
		assertEquals(List.of(name, writes, reversedWrites, name, equalName), data);
		assertSame(name, data.get(0));
		assertSame(writes, data.get(1));
		assertSame(name, data.get(3));
		assertSame(equalName, data.get(4));
	}

	@Test
	public void publicWalkerCrossesGeneratedChunkBoundariesInOrder() {
		List<String> expected = IntStream.range(0, 96).mapToObj(Integer::toString).toList();
		TriggerItem[] items = expected.stream().map(RecordingItem::new).toArray(TriggerItem[]::new);
		Trigger trigger = trigger(items);
		try {
			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Skatalyst.compiledCode(trigger);
			assertNotNull(code);
			assertNotSame(code.entry(items[0]).chunk(), code.entry(items[items.length - 1]).chunk());
			RecordingEvent event = new RecordingEvent();
			assertTrue(trigger.execute(event));
			assertEquals(expected, event.trace);
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	@Test
	public void preservesTheOriginalNodeWhenAnEmitterExceedsTheMethodBudget() {
		RecordingItem step = new RecordingItem("step");
		Trigger trigger = trigger(step);
		CompiledCode code = BytecodeCompiler.compile(trigger, Map.of(step, emitter -> {
			for (int index = 0; index < 7000; index++)
				emitter.method().visitInsn(NOP);
			emitter.loadActualNext(step);
		}));
		assertFalse(code.optimized(step));
		CompiledCode.Entry entry = code.entry(step);
		assertNotNull(entry);
		RecordingEvent event = new RecordingEvent();
		assertNull(entry.walk(event));
		assertEquals(List.of("step"), event.trace);
	}

	@Test
	public void returnsAnUnmappedSuccessorAfterRunningTheRedirectOnce() {
		RecordingItem external = new RecordingItem("external");
		RecordingItem unreachable = new RecordingItem("unreachable");
		RedirectItem redirect = new RedirectItem(external);
		Trigger trigger = trigger(new RecordingItem("first"), redirect, unreachable);
		CompiledCode code = BytecodeCompiler.compile(trigger);
		CompiledCode.Entry start = code.entry(trigger);
		assertNotNull(start);
		assertNull(code.entry(external));

		RecordingEvent event = new RecordingEvent();
		assertSame(external, start.walk(event));
		assertEquals(List.of("first", "redirect"), event.trace);
		assertTrue(TriggerItem.walk(external, event));
		assertEquals(List.of("first", "redirect", "external"), event.trace);
	}

	@Test
	public void treatsNullReturnedByAnOverrideAsTheEndOfTheWalk() {
		Trigger trigger = trigger(new RecordingItem("first"), new RedirectItem(null), new RecordingItem("unreachable"));
		CompiledCode.Entry start = BytecodeCompiler.compile(trigger).entry(trigger);
		assertNotNull(start);

		RecordingEvent event = new RecordingEvent();
		assertNull(start.walk(event));
		assertEquals(List.of("first", "redirect"), event.trace);
	}

	@Test
	public void retainsTheOriginalFailureAndFailingNode() {
		IllegalStateException cause = new IllegalStateException("failed in effect");
		RecordingItem broken = new RecordingItem("broken") {
			@Override
			protected boolean run(Event event) {
				super.run(event);
				throw cause;
			}
		};
		Trigger trigger = trigger(new RecordingItem("first"), broken, new RecordingItem("unreachable"));
		CompiledCode.Entry start = BytecodeCompiler.compile(trigger).entry(trigger);
		assertNotNull(start);

		RecordingEvent event = new RecordingEvent();
		CompiledCode.WalkFailure failure = assertThrows(CompiledCode.WalkFailure.class, () -> start.walk(event));
		assertSame(broken, failure.item());
		assertSame(cause, failure.failure());
		assertEquals(List.of("first", "broken"), event.trace);
	}

	@Test
	public void publicTriggerExecutionUsesTheInstalledHiddenClass() {
		RecordingItem step = new RecordingItem("step");
		Trigger trigger = trigger(step);
		RecordingEvent original = new RecordingEvent();
		assertTrue(trigger.execute(original));
		assertNull(Skatalyst.compiledCode(trigger));

		try {
			assertTrue(Skatalyst.forceCompile(trigger));
			CompiledCode code = Skatalyst.compiledCode(trigger);
			assertNotNull(code);
			CompiledCode.Entry entry = code.entry(step);
			assertNotNull(entry);

			RecordingEvent generated = new RecordingEvent();
			generated.expectedFrame = entry.chunk().getClass();
			assertTrue(trigger.execute(generated));
			assertTrue("the installed class did not execute", generated.sawGeneratedFrame);
			assertEquals(original.trace, generated.trace);
		} finally {
			Skatalyst.invalidate(trigger);
		}
	}

	@Test
	public void invalidationDiscardsACompilationThatFinishesLate() throws Exception {
		Trigger trigger = SkatalystExecutionTest.parseBody("set {_ready} to 1", body ->
			new Trigger(ParserInstance.get().getCurrentScript(), "Skatalyst invalidation fixture",
				new SimpleEvent(), ScriptLoader.loadItems(body)));
		Script owner = Objects.requireNonNull(trigger.getScript());
		owner.skatalystScope().activate();
		CountDownLatch compiled = new CountDownLatch(1);
		CountDownLatch publish = new CountDownLatch(1);
		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			var result = worker.submit(() -> Skatalyst.forceCompile(trigger, () -> {
				CompiledCode code = BytecodeCompiler.compile(trigger);
				compiled.countDown();
				try {
					if (!publish.await(10, TimeUnit.SECONDS))
						throw new AssertionError("test did not allow the compiler to publish");
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new AssertionError(interrupted);
				}
				return code;
			}));
			try {
				assertTrue("the background compiler never reached publication", compiled.await(10, TimeUnit.SECONDS));
			} finally {
				owner.invalidate();
				publish.countDown();
			}
			assertFalse(result.get(10, TimeUnit.SECONDS));
			assertNull(Skatalyst.compiledCode(trigger));
			assertFalse(Skatalyst.forceCompile(trigger));
		}
	}

	private static Trigger trigger(TriggerItem... items) {
		for (int index = 1; index < items.length; index++)
			items[index - 1].setNext(items[index]);
		return new Trigger(null, "Skatalyst bytecode fixture", new SimpleEvent(), List.of(items));
	}

	private static final class RecordingEvent extends Event {
		private static final HandlerList HANDLERS = new HandlerList();
		private static final StackWalker STACK_WALKER = StackWalker.getInstance(Set.of(
			StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
		private final List<String> trace = new ArrayList<>();
		private @Nullable Class<?> expectedFrame;
		private boolean sawGeneratedFrame;

		private void record(String item) {
			trace.add(item);
			if (expectedFrame != null) {
				sawGeneratedFrame = STACK_WALKER.walk(frames -> frames
					.anyMatch(frame -> frame.getDeclaringClass() == expectedFrame));
			}
		}

		@Override
		public @NotNull HandlerList getHandlers() {
			return HANDLERS;
		}
	}

	private static class RecordingItem extends TriggerItem {
		private final String name;

		private RecordingItem(String name) {
			this.name = name;
		}

		@Override
		protected boolean run(Event event) {
			((RecordingEvent) event).record(name);
			return true;
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return name;
		}
	}

	private static final class RedirectItem extends TriggerItem {
		private final @Nullable TriggerItem destination;

		private RedirectItem(@Nullable TriggerItem destination) {
			this.destination = destination;
		}

		@Override
		protected @Nullable TriggerItem walk(Event event) {
			((RecordingEvent) event).record("redirect");
			return destination;
		}

		@Override
		protected boolean run(Event event) {
			throw new AssertionError("used run instead of the overridden walk");
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "redirect";
		}
	}

}
