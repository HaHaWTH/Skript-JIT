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
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.sections.SecLoop;
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

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The built-in arithmetic tree
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

	static @Nullable NumericExpression parse(Expression<?> expression, NumericKind kind, @Nullable LoopSection loop) {
		return parse(expression, ignored -> kind, loop);
	}

	static @Nullable NumericExpression parse(Expression<?> expression, Function<String, NumericKind> kinds, @Nullable LoopSection loop) {
		if (expression.getClass() == ExprArithmetic.class) {
			return parse(((ExprArithmetic<?, ?, ?>) expression).skatalystArithmetic(), kinds, loop);
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
		Number value = NumericSlots.literal(expression);
		return value == null ? null : new Constant(value);
	}

	private static @Nullable NumericExpression parse(ArithmeticGettable<?> arithmetic, Function<String, NumericKind> kinds,
													 @Nullable LoopSection loop) {
		if (arithmetic instanceof ArithmeticExpressionInfo<?> info) {
			return parse(info.expression(), kinds, loop);
		}
		if (arithmetic.getClass() != ArithmeticChain.class) return null;
		var op = ((ArithmeticChain<?, ?, ?>) arithmetic).skatalystOperation();
		NumericExpression left = parse(op.left(), kinds, loop);
		NumericExpression right = parse(op.right(), kinds, loop);
		return binary(op.operator(), op.operationInfo(), arithmetic.getReturnType(), left, right);
	}

	static @Nullable NumericExpression change(ChangeMode mode, String target, @Nullable Expression<?> source,
											  Function<String, NumericKind> kinds, @Nullable LoopSection loop) {
		if (source == null || (mode != ChangeMode.SET && mode != ChangeMode.ADD && mode != ChangeMode.REMOVE)) {
			return null;
		}
		NumericExpression value = parse(source, kinds, loop);
		if (value == null || mode == ChangeMode.SET) {
			return value;
		}
		for (Class<?> type : value.possibleTypes()) {
			if (Classes.getSuperClassInfo(type).getCloner() != null) {
				return null;
			}
		}
		Operator operator = mode == ChangeMode.ADD ? Operator.ADDITION : Operator.SUBTRACTION;
		return binary(operator, null, Object.class, new Read(target, kinds.apply(target), true), value);
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
		public boolean needsExit() {
			return kind() == INTEGRAL || left.needsExit() || right.needsExit();
		}
	}
}
