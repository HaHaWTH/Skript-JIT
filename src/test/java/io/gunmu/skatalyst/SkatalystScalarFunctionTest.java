package io.gunmu.skatalyst;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.effects.EffReturn;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.function.FunctionRegistry;
import ch.njol.skript.lang.function.ScriptFunction;
import ch.njol.skript.lang.function.Signature;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleLiteral;
import org.junit.Test;
import org.skriptlang.skript.common.function.FunctionParser;
import org.skriptlang.skript.common.function.FunctionReference;
import org.skriptlang.skript.common.function.FunctionReference.Argument;
import org.skriptlang.skript.common.function.FunctionReference.ArgumentType;

import java.io.IOException;

import static org.junit.Assert.*;

public class SkatalystScalarFunctionTest {

	@Test
	public void numericParametersAndResultCrossTheScalarEntry() throws Exception {
		var function = parse("skatalystScalarParameters", "return {_a} * 2 + {_b}");
		Trigger trigger = body(function);
		var registry = FunctionRegistry.getRegistry();
		registry.register(null, function);
		try {
			assertEquals(21L, execute(reference(function, 8L, 5L)));
			assertTrue(Skatalyst.forceCompile(trigger));
			var direct = trigger.skatalystSite().directFunction();
			assertNotNull(describe(trigger), direct);
			assertTrue(direct.body().getClass().isHidden());
			assertEquals(21L, direct.body().invoke(8L, 5L, null, null));
			assertEquals(21.5, direct.body().invoke(8.25, 5.0, null, null));
			assertEquals(21L, execute(reference(function, 8L, 5L)));
			assertNull(direct.body().invoke(Long.MAX_VALUE, 5L, null, null));
			assertEquals((double) Long.MAX_VALUE * 2 + 5, execute(reference(function, Long.MAX_VALUE, 5L)));
			assertNull(direct.body().invoke(8L, 5.0, null, null));
			assertEquals(21.0, execute(reference(function, 8L, 5.0)));
		} finally {
			registry.remove(function.getSignature());
			trigger.getScript().skatalystScope().close();
		}
	}

	@Test
	public void reloadingAFunctionDropsItsOldScalarEntry() throws Exception {
		String name = "skatalystScalarReload";
		var first = parse(name, "return {_a} + {_b}");
		var second = parse(name, "return {_a} * {_b}");
		Trigger original = body(first);
		Trigger replacement = body(second);
		var registry = FunctionRegistry.getRegistry();
		registry.register(null, first);
		try {
			var reference = reference(first, 8L, 5L);
			assertEquals(13L, execute(reference));
			assertTrue(Skatalyst.forceCompile(original));
			assertNotNull(describe(original), original.skatalystSite().directFunction());
			assertEquals(13L, execute(reference));

			original.getScript().skatalystScope().close();
			registry.remove(first.getSignature());
			first.getSignature().calls().forEach(FunctionReference::invalidate);
			assertNull(original.skatalystSite().directFunction());

			registry.register(null, second);
			assertEquals(40L, execute(reference));
			assertTrue(Skatalyst.forceCompile(replacement));
			assertNotNull(describe(replacement), replacement.skatalystSite().directFunction());
			assertEquals(40L, execute(reference));
		} finally {
			registry.remove(first.getSignature());
			registry.remove(second.getSignature());
			original.getScript().skatalystScope().close();
			replacement.getScript().skatalystScope().close();
		}
	}

	private static String describe(Trigger trigger) {
		var statement = (EffReturn) trigger.skatalystItems().getFirst();
		var expression = statement.skatalystScalarValue();
		return "signature=" + trigger.skatalystSite().functionSignature() + ", next=" + trigger.getActualNext()
			+ ", external=" + statement.skatalystExposesLocals() + ", return="
			+ (expression == null ? "none" : expression.getClass().getName() + " / " + expression.getReturnType())
			+ ", compiled=" + Skatalyst.compiledCode(trigger).directFunction();
	}

	private static ScriptFunction<Number> parse(String name, String source) throws IOException {
		return SkatalystExecutionTest.parseBody(source, node -> {
			@SuppressWarnings("unchecked")
			Signature<Number> signature = (Signature<Number>) FunctionParser.parse(
				"skatalyst-test.sk", name, "a: number, b: number", "number", false);
			assertNotNull(signature);
			return new ScriptFunction<>(signature, node);
		});
	}

	private static Trigger body(ScriptFunction<?> function) throws ReflectiveOperationException {
		var field = ScriptFunction.class.getDeclaredField("trigger");
		field.setAccessible(true);
		Trigger trigger = (Trigger) field.get(function);
		trigger.getScript().skatalystScope().activate();
		return trigger;
	}

	@SuppressWarnings("unchecked")
	private static FunctionReference<Number> reference(ScriptFunction<Number> function, Number first, Number second) {
		Argument<Expression<?>>[] arguments = (Argument<Expression<?>>[]) new Argument<?>[]{
			new Argument<>(ArgumentType.UNNAMED, null, new SimpleLiteral<>(first, false)),
			new Argument<>(ArgumentType.UNNAMED, null, new SimpleLiteral<>(second, false))
		};
		return new FunctionReference<>(null, function.getSignature().getName(), function.getSignature(), arguments);
	}

	private static Number execute(FunctionReference<Number> reference) {
		Number result = reference.execute(ContextlessEvent.get());
		reference.function().resetReturnValue();
		return result;
	}
}
