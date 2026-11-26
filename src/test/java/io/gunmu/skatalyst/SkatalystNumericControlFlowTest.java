package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import io.gunmu.skatalyst.compiler.optimize.CoreOptimizations;
import org.bukkit.event.Event;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.ATHROW;

public class SkatalystNumericControlFlowTest {
	@Test
	public void continueAndElseIfExitLeaveOneExceptionRangeForBothNumericVariants() throws ReflectiveOperationException, IOException {
		Fixture fixture = fixture("""
			loop {_n} times:
				if loop-iteration < 4:
					continue
				else if loop-iteration > 16:
					exit loop
				set {_total} to {_total} + loop-value * 2
			""");
		for (Number initial : List.of(0L, 0.25)) {
			Event compiled = ContextlessEvent.get();
			Event interpreted = ContextlessEvent.get();
			try {
				for (Event event : List.of(compiled, interpreted)) {
					Variables.setVariable("n", 20L, event, true);
					Variables.setVariable("total", initial, event, true);
				}
				runOriginal(fixture.loop, interpreted);
				assertNull(fixture.code.entry(fixture.loop).walk(compiled));
				assertEquals(260.0 + initial.doubleValue(), ((Number) Variables.getVariable("total", compiled, true)).doubleValue(), 0.0);
				assertEquals(Variables.getVariable("total", interpreted, true), Variables.getVariable("total", compiled, true));
			} finally {
				fixture.loop.exit(compiled);
				fixture.loop.exit(interpreted);
				Variables.removeLocals(compiled);
				Variables.removeLocals(interpreted);
			}
		}
	}

	@Test
	public void nestedTerminalArmsRestoreTheirTailsAndKeepIndependentEntries() throws ReflectiveOperationException, IOException {
		for (String header : List.of("while {_visits} < 6:", "loop 6 times:")) {
			Fixture fixture = fixture(header + "\n" + """
				\tadd 1 to {_visits}
				\tif loop-iteration < 4:
				\t	if loop-iteration < 2:
				\t		continue
				\t	else:
				\t		exit loop
				\t	add 100 to {_inner}
				\telse:
				\t	exit loop
				\tadd 100 to {_outer}
				""");
			var outerIf = (TriggerSection) fixture.loop.skatalystItems().get(1);
			var innerIf = (TriggerSection) outerIf.skatalystItems().getFirst();
			TriggerItem continued = innerIf.skatalystItems().getFirst();
			TriggerItem innerTail = outerIf.skatalystItems().getLast();
			TriggerItem outerTail = fixture.loop.skatalystItems().getLast();
			Event event = ContextlessEvent.get();
			try {
				continued.setNext(null);
				innerTail.setNext(null);
				Variables.setVariable("visits", 0L, event, true);
				assertNull(fixture.code.entry(fixture.loop).walk(event));
				assertEquals(2L, Variables.getVariable("visits", event, true));
				assertNull(Variables.getVariable("inner", event, true));
				assertNull(Variables.getVariable("outer", event, true));
				assertSame(innerTail, continued.getActualNext());
				assertSame(outerTail, innerTail.getActualNext());

				// A caller can still enter the original lexical tail on its own.
				innerTail.setNext(null);
				outerTail.setNext(null);
				Variables.setVariable("inner", 0L, event, true);
				Variables.setVariable("outer", 0L, event, true);
				assertNull(fixture.code.entry(innerTail).walk(event));
				assertEquals(100L, Variables.getVariable("inner", event, true));
				assertEquals(0L, Variables.getVariable("outer", event, true));
				assertNull(fixture.code.entry(outerTail).walk(event));
				assertEquals(100L, Variables.getVariable("outer", event, true));
				assertEquals(2L, Variables.getVariable("visits", event, true));
			} finally {
				fixture.loop.exit(event);
				Variables.removeLocals(event);
			}
		}
	}

	@Test
	public void loopWithOnlyExitPathsHasNoUnreachableIncrementOrBackEdge() throws ReflectiveOperationException, IOException {
		for (String header : List.of("while {_visits} < 6:", "loop 6 times:")) {
			Fixture fixture = fixture(header + "\n" + """
				\tadd 1 to {_visits}
				\tif {_visits} < 2:
				\t	exit loop
				\telse:
				\t	exit loop
				\tcontinue
				""");
			Event event = ContextlessEvent.get();
			try {
				Variables.setVariable("visits", 0L, event, true);
				assertNull(fixture.code.entry(fixture.loop).walk(event));
				assertEquals(1L, Variables.getVariable("visits", event, true));
			} finally {
				fixture.loop.exit(event);
				Variables.removeLocals(event);
			}
		}
	}

	private record Fixture(LoopSection loop, CompiledCode code) {}

	private static Fixture fixture(String body) throws IOException, ReflectiveOperationException {
		Trigger trigger = SkatalystExecutionTest.parseBody(body, node -> new Trigger(ParserInstance.get().getCurrentScript(),
			"numeric control flow", new SimpleEvent(), ScriptLoader.loadItems(node)));
		var loop = (LoopSection) trigger.skatalystItems().getFirst();
		var plan = CoreOptimizations.plan(trigger).get(loop);
		assertNotNull(plan);
		var plans = Map.<TriggerItem, BytecodeCompiler.Optimization>of(loop, plan);
		var code = BytecodeCompiler.compile(trigger, plans);
		assertTrue(code.optimized(loop));
		assertReachableBytecode(trigger, plans);
		return new Fixture(loop, code);
	}

	private static void runOriginal(TriggerItem item, Event event) {
		while (item != null) {
			item = TriggerItem.skatalystStep(item, event);
		}
	}

	private static void assertReachableBytecode(Trigger trigger, Map<TriggerItem, BytecodeCompiler.Optimization> plans)
			throws ReflectiveOperationException {
		// Hidden classes have no class resource. Inspect the same chunk encoder that linked the runnable entry.
		var collect = BytecodeCompiler.class.getDeclaredMethod("collect", TriggerItem.class, TriggerItem.class,
			TriggerItem.class, TriggerItem.class, List.class, Set.class);
		collect.setAccessible(true);
		var items = new ArrayList<>();
		collect.invoke(null, trigger, null, null, null, items, Collections.newSetFromMap(new IdentityHashMap<>()));
		var encode = BytecodeCompiler.class.getDeclaredMethod("encodeChunk", List.class, MethodHandle.class, Map.class);
		encode.setAccessible(true);
		var chunk = encode.invoke(null, items, TriggerItem.skatalystWalkHandle(), plans);
		var writer = chunk.getClass().getDeclaredMethod("writer");
		writer.setAccessible(true);
		var bytes = ((ClassWriter) writer.invoke(chunk)).toByteArray();

		class Walk extends MethodVisitor {
			int throwsCount;
			int handlerCount;

			Walk() {
				super(ASM5);
			}

			@Override
			public void visitLdcInsn(Object value) {
				// Constant payloads, including condy, do not affect the control-flow counts.
			}

			@Override
			public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
				assertEquals("java/lang/Exception", type);
				handlerCount++;
			}

			@Override
			public void visitInsn(int opcode) {
				if (opcode == ATHROW) throwsCount++;
			}
		}
		var walk = new Walk();
		new ClassReader(bytes).accept(new ClassVisitor(ASM5) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return name.equals("walk") ? walk : null;
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		assertEquals("only invalid entry and WalkFailure throw", 2, walk.throwsCount);
		assertEquals("no unreachable gap in the walk handler", 1, walk.handlerCount);
	}
}
