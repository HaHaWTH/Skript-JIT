package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.data.DefaultOperations;
import ch.njol.skript.expressions.ExprLoopIteration;
import ch.njol.skript.expressions.ExprLoopValue;
import ch.njol.skript.expressions.arithmetic.ArithmeticChain;
import ch.njol.skript.expressions.arithmetic.ArithmeticExpressionInfo;
import ch.njol.skript.expressions.arithmetic.ArithmeticGettable;
import ch.njol.skript.expressions.arithmetic.ExprArithmetic;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.KeyProviderExpression;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.util.ConvertedExpression;
import ch.njol.skript.sections.SecLoop;
import io.gunmu.skatalyst.addon.ParsedBinding;
import io.gunmu.skatalyst.addon.ParsedBinding.NumericCall;
import io.gunmu.skatalyst.compiler.BytecodeCompiler.Emitter;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.Slot;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.arithmetic.Operation;
import org.skriptlang.skript.lang.arithmetic.OperationInfo;
import org.skriptlang.skript.lang.arithmetic.Operator;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Primitive arithmetic and admitted addon calls within one numeric region.
 */
sealed interface NumericExpression {

	void emit(Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow);

	NumericKind kind();

	default List<Class<?>> possibleTypes() {
		return List.of(kind().boxed);
	}

	default int nodes() {
		return 1;
	}

	default boolean needsExit() {
		return false;
	}

	default void collectNames(Map<String, Boolean> names) {
	}

	default void collectIntrinsics(Set<NumericCall> calls) {
	}

	static @Nullable NumericExpression parse(Expression<?> expression, Function<String, NumericKind> kinds,
			@Nullable LoopSection loop, @Nullable ParsedBinding binding) {
		return parse(expression, kinds, loop, binding, null);
	}

	private static @Nullable NumericExpression parse(Expression<?> expression, Function<String, NumericKind> kinds,
			@Nullable LoopSection loop, @Nullable ParsedBinding binding, @Nullable Set<Expression<?>> activeCalls) {
		if (expression.getClass() == ConvertedExpression.class) {
			var conversion = (ConvertedExpression<?, ?>) expression;
			var input = parse(conversion.skatalystInput(), kinds, loop, binding, activeCalls);
			return input != null && input.possibleTypes().stream().allMatch(conversion::skatalystIsIdentityFor) ? input : null;
		}
		if (expression.getClass() == ExprArithmetic.class) {
			return parse(((ExprArithmetic<?, ?, ?>) expression).skatalystArithmetic(), kinds, loop, binding, activeCalls);
		}
		String name = CoreOptimizations.scalarLocal(expression);
		if (name != null) {
			Variable<?> variable = (Variable<?>) expression;
			NumericKind kind = kinds.apply(name);
			return variable.skatalystAcceptsRaw(kind.boxed)
				? new Read(name, kind, kind == INTEGRAL && variable.skatalystAcceptsRaw(Integer.class)) : null;
		}
		if (loop instanceof SecLoop counted && expression.getClass() == ExprLoopValue.class
			&& ((ExprLoopValue) expression).skatalystIsCurrentValueOf(counted)) {
			return Iteration.CURRENT;
		}
		if (loop != null && expression.getClass() == ExprLoopIteration.class
			&& ((ExprLoopIteration) expression).skatalystIsIterationOf(loop)) {
			return Iteration.CURRENT;
		}
		if (binding != null) {
			NumericCall call = binding.numericIntrinsic(expression);
			if (call != null) {
				if (!expression.isSingle() || KeyProviderExpression.canReturnKeys(expression)) {
					return null;
				}
				if (activeCalls == null) {
					activeCalls = Collections.newSetFromMap(new IdentityHashMap<>());
				}
				if (activeCalls.size() == NumericRegion.MAX_NODES || !activeCalls.add(expression)) {
					return null;
				}
				try {
					return intrinsic(expression, call, kinds, loop, binding, activeCalls);
				} finally {
					activeCalls.remove(expression);
				}
			}
		}
		Number value = NumericSlots.literal(expression);
		return value == null ? null : new Constant(value);
	}

	private static @Nullable NumericExpression parse(ArithmeticGettable<?> arithmetic, Function<String, NumericKind> kinds,
			@Nullable LoopSection loop, @Nullable ParsedBinding binding, @Nullable Set<Expression<?>> activeCalls) {
		if (arithmetic instanceof ArithmeticExpressionInfo<?> info) {
			return parse(info.expression(), kinds, loop, binding, activeCalls);
		}
		if (arithmetic.getClass() != ArithmeticChain.class) return null;
		var op = ((ArithmeticChain<?, ?, ?>) arithmetic).skatalystOperation();
		NumericExpression left = parse(op.left(), kinds, loop, binding, activeCalls);
		NumericExpression right = parse(op.right(), kinds, loop, binding, activeCalls);
		return binary(op.operator(), op.operationInfo(), arithmetic.getReturnType(), left, right);
	}

	private static @Nullable NumericExpression intrinsic(Expression<?> expression, NumericCall call,
			Function<String, NumericKind> kinds, @Nullable LoopSection loop, ParsedBinding binding,
			Set<Expression<?>> activeCalls) {
		var replacement = call.intrinsic();
		if (replacement == null || !call.isActive()) {
			return null;
		}
		var type = replacement.handle().type();
		var result = type.returnType() == long.class ? INTEGRAL : DECIMAL;
		if (!expression.getReturnType().isAssignableFrom(result.boxed)) {
			return null;
		}
		List<NumericExpression> operands = new ArrayList<>(replacement.operands().size());
		int nodes = 1;
		for (int index = 0; index < replacement.operands().size(); index++) {
			var operand = parse(replacement.operands().get(index), kinds, loop, binding, activeCalls);
			var expected = type.parameterType(index) == long.class ? INTEGRAL : DECIMAL;
			if (operand instanceof Read read) {
				operand = new Read(read.name(), read.kind(), false);
			}
			if (operand == null || operand.kind() != expected || !operand.possibleTypes().equals(List.of(expected.boxed))) {
				return null;
			}
			nodes += operand.nodes();
			if (nodes > NumericRegion.MAX_NODES) {
				return null;
			}
			operands.add(operand);
		}
		return new IntrinsicCall(call, replacement.handle(), List.copyOf(operands), result, nodes);
	}

	record Change(NumericExpression source, NumericExpression value) { }

	static @Nullable Change change(ChangeMode mode, String target, @Nullable Expression<?> source,
			Function<String, NumericKind> kinds, @Nullable LoopSection loop, @Nullable ParsedBinding binding) {
		if (source == null || (mode != ChangeMode.SET && mode != ChangeMode.ADD && mode != ChangeMode.REMOVE)) {
			return null;
		}
		NumericExpression delta = parse(source, kinds, loop, binding);
		if (delta == null || delta instanceof IntrinsicCall && !IntrinsicCall.hasDefaultBeforeChange(source)) {
			return null;
		}
		if (mode == ChangeMode.SET) return new Change(delta, delta);
		Operator operator = mode == ChangeMode.ADD ? Operator.ADDITION : Operator.SUBTRACTION;
		NumericExpression value = binary(operator, null, Object.class, new Read(target, kinds.apply(target), true), delta);
		return value == null ? null : new Change(delta, value);
	}

	private static @Nullable NumericExpression binary(Operator operator, @Nullable OperationInfo<?, ?, ?> parsed,
													  Class<?> result, @Nullable NumericExpression left, @Nullable NumericExpression right) {
		if (left == null || right == null) {
			return null;
		}
		Operation<?, ?, ?> builtin;
		if (operator == Operator.ADDITION) {
			builtin = DefaultOperations.ADD_NUMBERS;
		} else if (operator == Operator.SUBTRACTION) {
			builtin = DefaultOperations.SUBTRACT_NUMBERS;
		} else if (operator == Operator.MULTIPLICATION) {
			builtin = DefaultOperations.MULTIPLY_NUMBERS;
		} else if (operator == Operator.DIVISION) {
			builtin = DefaultOperations.DIVIDE_NUMBERS;
		} else {
			return null; // wtf
		}
		if (parsed != null) {
			if (parsed.operation() != builtin) {
				return null;
			}
		} else {
			if (!result.isAssignableFrom(Number.class)) {
				return null;
			}
			for (Class<?> leftType : left.possibleTypes()) {
				for (Class<?> rightType : right.possibleTypes()) {
					if (!NumericSlots.usesOperation(operator, leftType, rightType, builtin)) {
						return null;
					}
				}
			}
		}
		return new Binary(operator, left, right);
	}

	record Constant(Number value) implements NumericExpression {
		@Override
		public void emit(Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			if (kind() == DECIMAL) {
				emitter.method().visitLdcInsn(value.doubleValue());
			} else {
				emitter.method().visitLdcInsn(value.longValue());
			}
		}

		@Override
		public NumericKind kind() {
			return value instanceof Double ? DECIMAL : INTEGRAL;
		}

		@Override
		public List<Class<?>> possibleTypes() {
			return List.of(value.getClass());
		}
	}

	enum Iteration implements NumericExpression {
		CURRENT;

		@Override
		public void emit(Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			emitter.method().visitVarInsn(LLOAD, iteration);
		}

		@Override
		public NumericKind kind() {
			return INTEGRAL;
		}
	}

	record Read(String name, NumericKind kind, boolean acceptsInteger) implements NumericExpression {
		@Override
		public void emit(Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			emitter.method().visitVarInsn(kind.loadOpcode(), slots.get(name).value());
		}

		@Override
		public List<Class<?>> possibleTypes() {
			return kind == INTEGRAL && acceptsInteger ? List.of(Long.class, Integer.class) : List.of(kind.boxed);
		}

		@Override
		public void collectNames(Map<String, Boolean> names) {
			names.merge(name, acceptsInteger, Boolean::logicalAnd);
		}
	}

	record IntrinsicCall(NumericCall binding, MethodHandle handle, List<NumericExpression> operands, NumericKind kind,
			int nodes) implements NumericExpression {
		private static final ClassValue<Boolean> DEFAULT_BEFORE_CHANGE = new ClassValue<>() {
			@Override
			protected Boolean computeValue(Class<?> type) {
				try {
					return type.getMethod("beforeChange", Expression.class, Object[].class).getDeclaringClass() == Expression.class;
				} catch (NoSuchMethodException missingExpressionMethod) {
					throw new AssertionError(missingExpressionMethod);
				}
			}
		};

		private static boolean hasDefaultBeforeChange(Expression<?> source) {
			// EffChange dispatches the hook once on the outer RHS. An exact ConvertedExpression forwards it.
			while (source.getClass() == ConvertedExpression.class) {
				source = ((ConvertedExpression<?, ?>) source).skatalystInput();
			}
			return DEFAULT_BEFORE_CHANGE.get(source.getClass());
		}

		@Override
		public void emit(Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			MethodVisitor method = emitter.method();
			int[] arguments = new int[operands.size()];
			for (int index = 0; index < operands.size(); index++) {
				NumericExpression operand = operands.get(index);
				arguments[index] = emitter.newLocal(operand.kind().type);
				operand.emit(emitter, slots, iteration, overflow);
				method.visitVarInsn(operand.kind().storeOpcode(), arguments[index]);
			}
			// An operand may side-exit, so the invocation stack must still be empty while evaluating it.
			emitter.loadConstant(handle, MethodHandle.class);
			for (int index = 0; index < operands.size(); index++) {
				method.visitVarInsn(operands.get(index).kind().loadOpcode(), arguments[index]);
			}
			method.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodHandle.class), "invokeExact",
				handle.type().toMethodDescriptorString(), false);
		}

		@Override
		public boolean needsExit() {
			return operands.stream().anyMatch(NumericExpression::needsExit);
		}

		@Override
		public void collectNames(Map<String, Boolean> names) {
			for (NumericExpression operand : operands) {
				operand.collectNames(names);
			}
		}

		@Override
		public void collectIntrinsics(Set<NumericCall> calls) {
			calls.add(binding);
			for (NumericExpression operand : operands) {
				operand.collectIntrinsics(calls);
			}
		}
	}

	record Binary(Operator operator, NumericExpression left, NumericExpression right) implements NumericExpression {
		@Override
		public void emit(Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			MethodVisitor method = emitter.method();
			NumericKind kind = kind();
			int opcode = operator == Operator.ADDITION ? IADD : operator == Operator.SUBTRACTION ? ISUB
				: operator == Operator.MULTIPLICATION ? IMUL : IDIV;
			int leftLocal = emitter.newLocal(kind.type);
			int rightLocal = emitter.newLocal(kind.type);
			emitOperand(left, emitter, slots, iteration, overflow, kind);
			method.visitVarInsn(kind.storeOpcode(), leftLocal);
			emitOperand(right, emitter, slots, iteration, overflow, kind);
			method.visitVarInsn(kind.storeOpcode(), rightLocal);
			method.visitVarInsn(kind.loadOpcode(), leftLocal);
			method.visitVarInsn(kind.loadOpcode(), rightLocal);
			method.visitInsn(kind.type.getOpcode(opcode));
			if (kind == DECIMAL) return;
			int result = emitter.newLocal(Type.LONG_TYPE);
			method.visitVarInsn(LSTORE, result);
			if (operator == Operator.MULTIPLICATION) {
				method.visitVarInsn(LLOAD, leftLocal);
				method.visitVarInsn(LLOAD, rightLocal);
				method.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "multiplyHigh", "(JJ)J", false);
				method.visitVarInsn(LLOAD, result);
				emitter.pushInt(Long.SIZE - 1);
				method.visitInsn(LSHR);
				method.visitInsn(LCMP);
				method.visitJumpInsn(IFNE, overflow);
			} else {
				NumericSlots.guardIntegralResult(method, leftLocal, rightLocal, result, overflow);
			}
			method.visitVarInsn(LLOAD, result);
		}

		private static void emitOperand(NumericExpression value, Emitter emitter, Map<String, Slot> slots,
										int iteration, Label overflow, NumericKind kind) {
			value.emit(emitter, slots, iteration, overflow);
			if (value.kind() != kind) {
				emitter.method().visitInsn(L2D);
			}
		}

		@Override
		public NumericKind kind() {
			return operator == Operator.DIVISION || left.kind() == DECIMAL || right.kind() == DECIMAL ? DECIMAL : INTEGRAL;
		}

		@Override
		public int nodes() {
			return 1 + left.nodes() + right.nodes();
		}

		@Override
		public void collectNames(Map<String, Boolean> names) {
			left.collectNames(names);
			right.collectNames(names);
		}

		@Override
		public void collectIntrinsics(Set<NumericCall> calls) {
			left.collectIntrinsics(calls);
			right.collectIntrinsics(calls);
		}

		@Override
		public boolean needsExit() {
			return kind() == INTEGRAL || left.needsExit() || right.needsExit();
		}
	}
}
