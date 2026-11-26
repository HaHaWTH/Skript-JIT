package io.gunmu.skatalyst;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.function.ScriptFunction;
import ch.njol.skript.lang.function.Signature;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.ConvertedExpression;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import io.gunmu.skatalyst.api.NumericIntrinsic;
import io.gunmu.skatalyst.api.SkatalystAddonApi;
import io.gunmu.skatalyst.api.SkatalystSemantics;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import io.gunmu.skatalyst.compiler.optimize.CoreOptimizations;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skriptlang.skript.common.function.FunctionParser;
import org.skriptlang.skript.docs.Origin;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

import static io.gunmu.skatalyst.api.SkatalystSemantics.EventEscape.NO_ESCAPE;
import static io.gunmu.skatalyst.api.SkatalystSemantics.Locals.NONE;
import static org.junit.Assert.*;

public class SkatalystNumericIntrinsicTest {

	private static final MethodHandle TWICE = handle("twice", long.class, long.class);
	private static final MethodHandle MIX = handle("mix", double.class, double.class, long.class, long.class);
	private static SkatalystAddonApi api;
	private static SyntaxRegistry syntaxRegistry;
	private static SyntaxInfo.Expression<?, ?> twiceSyntax;
	private static SyntaxInfo.Expression<?, ?> mixSyntax;
	private static SyntaxInfo.Expression<?, ?> changeHookSyntax;
	private static int originalCalls;
	private static int beforeChangeCalls;

	@BeforeClass
	public static void registerSyntax() {
		var addon = Skript.instance().registerAddon(SkatalystNumericIntrinsicTest.class, "Skatalyst Numeric Intrinsic Tests");
		api = SkatalystAddonApi.forAddon(addon);
		syntaxRegistry = addon.syntaxRegistry();
		twiceSyntax = SyntaxInfo.Expression.builder(ExprTwice.class, Long.class)
			.origin(Origin.of(addon)).addPattern("skatalyst test twice %number%").build();
		mixSyntax = SyntaxInfo.Expression.builder(ExprMix.class, Double.class)
			.origin(Origin.of(addon)).addPattern("skatalyst test mix %number% with %number% offset %number%").build();
		changeHookSyntax = SyntaxInfo.Expression.builder(ExprChangeHook.class, Number.class)
			.origin(Origin.of(addon)).addPattern("skatalyst test hooked %number%").build();
		syntaxRegistry.register(SyntaxRegistry.EXPRESSION, twiceSyntax);
		syntaxRegistry.register(SyntaxRegistry.EXPRESSION, mixSyntax);
		syntaxRegistry.register(SyntaxRegistry.EXPRESSION, changeHookSyntax);
	}

	@AfterClass
	public static void unregisterSyntax() {
		if (syntaxRegistry != null) {
			syntaxRegistry.unregister(twiceSyntax);
			syntaxRegistry.unregister(mixSyntax);
			syntaxRegistry.unregister(changeHookSyntax);
		}
	}

	@Test
	public void wholeRhsUsesItsInheritedChangeHookForSetAddAndRemove() throws IOException {
		String rhs = "skatalyst test hooked {_input}";
		try (var lease = api.registerAnnotated(ExprChangeHook.class, expr -> new NumericIntrinsic(TWICE, List.of(expr.operand)))) {
			assertChangeHook("set {_total} to " + rhs, false, 7L, null);
			assertChangeHook("add " + rhs + " to {_total}", false, 17L, 10L);
			assertChangeHook("remove " + rhs + " from {_total}", false, 3L, 10L);
			assertChangeHook("set {_total} to " + rhs, true, 7L, null);

			beforeChangeCalls = 0;
			Fixture arithmetic = fixture("set {_total} to (" + rhs + ") + 1");
			assertBoth(arithmetic, Map.of("input", 2L), 5L);
			assertEquals("EffChange only calls beforeChange on the outer arithmetic expression", 0, beforeChangeCalls);
		}
	}

	private static void assertChangeHook(String script, boolean converted, long rewritten, @Nullable Long emptied) throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody(script, node -> {
			List<TriggerItem> items = ScriptLoader.loadItems(node);
			if (converted) {
				EffChange effect = (EffChange) items.getFirst();
				var change = effect.skatalystChange();
				ConvertedExpression<?, ?> view = ConvertedExpression.newInstance(change.source(), Long.class);
				assertNotNull(view);
				assertTrue(view.skatalystIsIdentityFor(Long.class));
				// Feed the parsed effect the same numeric source through the core's forwarding conversion.
				Expression<?>[] expressions = {change.target(), view};
				assertTrue(effect.init(expressions, 3, Kleenean.FALSE, new ParseResult(script, expressions)));
				assertSame(view, effect.skatalystChange().source());
			}
			return new Trigger(ParserInstance.get().getCurrentScript(), "beforeChange fixture", new SimpleEvent(), items);
		});
		var item = trigger.skatalystItems().getFirst();
		var code = BytecodeCompiler.compile(trigger);
		assertFalse("a native store would bypass the addon's change hook", code.optimized(item));
		var entry = code.entry(item);
		assertNotNull(entry);
		try {
			for (long input : new long[]{2, 0}) {
				for (boolean compiled : new boolean[]{false, true}) {
					Event event = ContextlessEvent.get();
					try {
						seed(event, Map.of("input", input, "total", 10L));
						originalCalls = beforeChangeCalls = 0;
						if (compiled) {
							assertNull(entry.walk(event));
						} else {
							walk(item, event);
						}
						assertEquals(1, originalCalls);
						assertEquals(1, beforeChangeCalls);
						assertValue(input == 0 ? emptied : Long.valueOf(rewritten), get(event, "total"));
					} finally {
						Variables.removeLocals(event);
					}
				}
			}
		} finally {
			code.retire();
			trigger.getScript().invalidate();
		}
	}

	@Test
	public void exactIntrinsicsWorkInScalarChangesAndBothNumericalLoops() throws IOException {
		try (var registration = registerTwice()) {
			Fixture scalar = fixture("""
				set {_total} to skatalyst test twice {_input}
				add skatalyst test twice 2 to {_total}
				remove skatalyst test twice 1 from {_total}
				""");
			assertBoth(scalar, Map.of("input", 6L), 14L);

			Fixture counted = fixture("""
				loop 4 times:
					add skatalyst test twice loop-value to {_total}
				""");
			assertBoth(counted, Map.of("total", 0L), 20L);

			Fixture conditional = fixture("""
				while skatalyst test twice {_index} < 8:
					add skatalyst test twice {_index} to {_total}
					add 1 to {_index}
				""");
			assertBoth(conditional, Map.of("total", 0L, "index", 0L), 12L);
		}
	}

	@Test
	public void mixedThreeArgumentHandlePreservesNaNPayloadAndSignedZero() throws IOException {
		try (var registration = api.registerAnnotated(ExprMix.class, expr -> new NumericIntrinsic(MIX, expr.operands))) {
			Fixture fixture = fixture("set {_total} to skatalyst test mix {_ratio} with {_first} offset {_second}");
			for (double input : new double[]{-0.0, 2.25, Double.longBitsToDouble(0x7ff8000000000042L)}) {
				assertBoth(fixture, Map.of("ratio", input, "first", 2L, "second", -2L), input);
			}
		}
	}

	@Test
	public void overflowUnsetAndRevocationResumeOriginalSyntax() throws IOException {
		try (var registration = registerTwice()) {
			Fixture fixture = fixture("""
				set {_seen} to 1
				set {_total} to skatalyst test twice ({_input} * 2)
				""");
			for (Map<String, Object> inputs : List.of(Map.of("input", (Object) Long.MAX_VALUE), Map.of("total", (Object) 99L))) {
				assertFallback(fixture, inputs);
			}
			registration.close();
			assertFallback(fixture, Map.of("input", 3L));
		}
	}

	@Test
	public void pureScriptFunctionCanCallAnIntrinsicFromItsDirectEntry() throws Exception {
		try (var registration = api.registerAnnotated(ExprMix.class, expr -> new NumericIntrinsic(MIX, expr.operands))) {
			var function = SkatalystExecutionTest.parseBody("return skatalyst test mix {_ratio} with {_first} offset {_second}", node -> {
				@SuppressWarnings("unchecked")
				Signature<Number> signature = (Signature<Number>) FunctionParser.parse(
					"skatalyst-test.sk", "skatalystMixIntrinsic", "ratio: number, first: number, second: number", "number", false);
				assertNotNull(signature);
				return new ScriptFunction<>(signature, node);
			});
			var field = ScriptFunction.class.getDeclaredField("trigger");
			field.setAccessible(true);
			Trigger trigger = (Trigger) field.get(function);
			trigger.getScript().skatalystScope().activate();
			try {
				assertTrue(Skatalyst.forceCompile(trigger));
				var direct = trigger.skatalystSite().directFunction();
				assertNotNull(direct);
				var body = direct.body();
				assertNotNull(body);
				originalCalls = 0;
				assertEquals(14.5, body.invoke(11.5, 2L, 1L, null));
				assertNull(body.invoke(11.5, 2.0, 1L, null));
				assertEquals(0, originalCalls);
				registration.close();
				assertNull(direct.body());
				assertNull(body.invoke(11.5, 2L, 1L, null));
			} finally {
				trigger.getScript().skatalystScope().close();
			}
		}
	}

	@Test
	public void descriptorMustHaveExactlyMatchingPrimitiveOperands() {
		new NumericIntrinsic(MethodHandles.constant(long.class, 1L), List.of());
		assertThrows(IllegalArgumentException.class, () -> new NumericIntrinsic(TWICE, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new NumericIntrinsic(MethodHandles.constant(Number.class, 1L), List.of()));
	}

	private static SkatalystAddonApi.Registration registerTwice() {
		return api.registerAnnotated(ExprTwice.class, expression -> new NumericIntrinsic(TWICE, List.of(expression.operand)));
	}

	private static void assertBoth(Fixture fixture, Map<String, Object> inputs, Object expected) {
		Event generic = ContextlessEvent.get();
		Event compiled = ContextlessEvent.get();
		try {
			seed(generic, inputs);
			seed(compiled, inputs);
			originalCalls = 0;
			walk(fixture.items.getFirst(), generic);
			assertTrue(originalCalls > 0);
			originalCalls = 0;
			assertNull(fixture.entry.walk(compiled));
			assertEquals(0, originalCalls);
			assertValue(expected, get(generic, "total"));
			assertValue(expected, get(compiled, "total"));
		} finally {
			Variables.removeLocals(generic);
			Variables.removeLocals(compiled);
		}
	}

	private static void assertFallback(Fixture fixture, Map<String, Object> inputs) {
		Event generic = ContextlessEvent.get();
		Event compiled = ContextlessEvent.get();
		try {
			seed(generic, inputs);
			seed(compiled, inputs);
			walk(fixture.items.getFirst(), generic);
			originalCalls = 0;
			walk(fixture.entry.walk(compiled), compiled);
			assertEquals(1, originalCalls);
			assertValue(get(generic, "total"), get(compiled, "total"));
			assertEquals(1L, get(compiled, "seen"));
		} finally {
			Variables.removeLocals(generic);
			Variables.removeLocals(compiled);
		}
	}

	private static Fixture fixture(String source) throws IOException {
		Trigger trigger = SkatalystExecutionTest.parseBody(source, node -> new Trigger(ParserInstance.get().getCurrentScript(),
			"numeric intrinsic fixture", new SimpleEvent(), ScriptLoader.loadItems(node)));
		var plan = CoreOptimizations.plan(trigger).get(trigger.skatalystItems().getFirst());
		assertNotNull("missing numeric intrinsic plan for " + source, plan);
		assertTrue(plan.handlesOriginalExposure());
		Trigger isolated = new Trigger(null, "isolated intrinsic region", new SimpleEvent(), List.of());
		var entry = BytecodeCompiler.compile(isolated, Map.of(isolated, plan)).entry(isolated);
		assertNotNull(entry);
		return new Fixture(trigger.skatalystItems(), entry);
	}

	private static void seed(Event event, Map<String, Object> values) {
		values.forEach((name, value) -> Variables.setVariable(name, value, event, true));
	}

	private static @Nullable Object get(Event event, String name) {
		return Variables.getVariable(name, event, true);
	}

	private static void walk(@Nullable TriggerItem item, Event event) {
		while (item != null) {
			item = TriggerItem.skatalystStep(item, event);
		}
	}

	private static void assertValue(@Nullable Object expected, @Nullable Object actual) {
		if (expected instanceof Double value) {
			assertTrue(actual instanceof Double);
			assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) actual));
		} else {
			assertEquals(expected, actual);
		}
	}

	private static MethodHandle handle(String name, Class<?> result, Class<?>... parameters) {
		try {
			return MethodHandles.lookup().findStatic(SkatalystNumericIntrinsicTest.class, name, MethodType.methodType(result, parameters));
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static long twice(long value) {
		return value * 2;
	}

	private static double mix(double value, long first, long second) {
		long adjustment = first + second;
		return adjustment == 0 ? value : value + adjustment;
	}

	private record Fixture(List<TriggerItem> items, CompiledCode.Entry entry) {
	}

	private interface RewriteDelta extends Expression<Number> {
		@Override
		default Object[] beforeChange(Expression<?> changed, Object[] delta) {
			beforeChangeCalls++;
			long value = ((Number) delta[0]).longValue();
			return value == 0 ? new Object[0] : new Object[]{value + 3};
		}
	}

	@SkatalystSemantics(eventEscape = NO_ESCAPE, locals = NONE, mayReenter = false, maySuspend = false)
	public static class ExprChangeHook extends SimpleExpression<Number> implements RewriteDelta {
		private Expression<?> operand;

		@Override
		public boolean init(Expression<?>[] expressions, int pattern, Kleenean delayed, ParseResult result) {
			operand = expressions[0];
			return true;
		}

		@Override
		protected Number[] get(Event event) {
			originalCalls++;
			return new Long[]{twice(((Number) operand.getSingle(event)).longValue())};
		}

		@Override
		public boolean isSingle() { return true; }

		@Override
		public Class<Number> getReturnType() { return Number.class; }

		@Override
		@SuppressWarnings("unchecked")
		public Class<? extends Number>[] possibleReturnTypes() { return new Class[]{Long.class}; }

		@Override
		public String toString(@Nullable Event event, boolean debug) { return "skatalyst test hooked"; }
	}

	@SkatalystSemantics(eventEscape = NO_ESCAPE, locals = NONE, mayReenter = false, maySuspend = false)
	public static class ExprTwice extends SimpleExpression<Long> {
		private Expression<?> operand;

		@Override
		public boolean init(Expression<?>[] expressions, int pattern, Kleenean delayed, ParseResult parseResult) {
			operand = expressions[0];
			return true;
		}

		@Override
		protected Long @Nullable [] get(Event event) {
			originalCalls++;
			Number input = (Number) operand.getSingle(event);
			return input == null ? null : new Long[]{twice(input.longValue())};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<Long> getReturnType() {
			return Long.class;
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst test twice";
		}
	}

	@SkatalystSemantics(eventEscape = NO_ESCAPE, locals = NONE, mayReenter = false, maySuspend = false)
	public static class ExprMix extends SimpleExpression<Double> {
		private List<Expression<?>> operands;

		@Override
		public boolean init(Expression<?>[] expressions, int pattern, Kleenean delayed, ParseResult parseResult) {
			operands = List.of(expressions);
			return true;
		}

		@Override
		protected Double @Nullable [] get(Event event) {
			originalCalls++;
			Number value = (Number) operands.get(0).getSingle(event);
			Number first = (Number) operands.get(1).getSingle(event);
			Number second = (Number) operands.get(2).getSingle(event);
			return value == null || first == null || second == null ? null
				: new Double[]{mix(value.doubleValue(), first.longValue(), second.longValue())};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<Double> getReturnType() {
			return Double.class;
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst test mix";
		}
	}
}
