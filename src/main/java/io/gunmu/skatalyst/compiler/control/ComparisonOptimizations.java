package io.gunmu.skatalyst.compiler.control;

import ch.njol.skript.classes.data.DefaultComparators;
import ch.njol.skript.conditions.CondCompare;
import ch.njol.skript.expressions.arithmetic.ArithmeticGettable;
import ch.njol.skript.expressions.arithmetic.ExprArithmetic;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.util.SimpleLiteral;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.comparator.Comparators;
import org.skriptlang.skript.lang.comparator.Comparator;
import org.skriptlang.skript.lang.comparator.Relation;

import static org.objectweb.asm.Opcodes.*;

@ApiStatus.Internal
public final class ComparisonOptimizations {

	private static final Type OBJECT = Type.getType(Object.class);
	private static final Type EVENT = Type.getType(Event.class);
	private static final Type NUMBER = Type.getType(Number.class);
	private static final Type RELATION = Type.getType(Relation.class);
	private static final String COMPARE = Type.getInternalName(CondCompare.class);
	private static final String READ_VALUE = Type.getMethodDescriptor(OBJECT, EVENT);

	private ComparisonOptimizations() {}

	static @Nullable Comparison plan(@Nullable CondCompare condition) {
		if (condition == null)
			return null;
		CondCompare.BinaryComparison binary = condition.skatalystComparison();
		if (binary == null)
			return null;
		Operand first = operand(binary.first());
		Operand second = operand(binary.second());
		if (first == null || second == null)
			return null;
		boolean fixedNumber = binary.comparator() == DefaultComparators.NUMBERS;
		boolean dynamic = binary.comparator() == null;
		boolean longs = usesLongComparator(binary);
		boolean doubles = dynamic && usesNumberComparator(Double.class);
		if (!longs && !doubles)
			return null;
		return new Comparison(condition, first, second, binary.relation(), condition.isNegated(), fixedNumber, longs, doubles);
	}

	private static boolean usesNumberComparator(Class<? extends Number> type) {
		Comparator<?, ?> comparator = Comparators.getComparator(type, type);
		return comparator == DefaultComparators.NUMBERS;
	}

	public static boolean usesLongComparator(CondCompare.BinaryComparison binary) {
		return binary.comparator() == DefaultComparators.NUMBERS
			|| binary.comparator() == null && usesNumberComparator(Long.class);
	}

	/** Branches on two primitive longs using the built-in comparator's wrapping subtraction. */
	public static void jumpIfLongRelation(MethodVisitor method, Relation relation, Label target) {
		method.visitInsn(LSUB);
		method.visitInsn(LCONST_0);
		method.visitInsn(LCMP);
		method.visitJumpInsn(switch (relation) {
			case EQUAL -> IFEQ;
			case NOT_EQUAL -> IFNE;
			case GREATER -> IFGT;
			case GREATER_OR_EQUAL -> IFGE;
			case SMALLER -> IFLT;
			case SMALLER_OR_EQUAL -> IFLE;
		}, target);
	}

	private static @Nullable Operand operand(Expression<?> expression) {
		if (expression.getClass() == SimpleLiteral.class) {
			Object[] values = ((SimpleLiteral<?>) expression).getAll();
			if (values.length == 1 && values[0] instanceof Number number)
				return emitter -> emitter.loadConstant(number, Object.class);
		} else if (expression.getClass() == Variable.class && !((Variable<?>) expression).isList()) {
			return emitter -> {
				emitter.loadConstant(expression, Variable.class);
				emitter.loadEvent();
				emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(Variable.class), "getSingle",
					READ_VALUE, false);
			};
		} else if (expression.getClass() == ExprArithmetic.class) {
			ArithmeticGettable<?> arithmetic = ((ExprArithmetic<?, ?, ?>) expression).skatalystArithmetic();
			return emitter -> {
				emitter.loadConstant(arithmetic, ArithmeticGettable.class);
				emitter.loadEvent();
				emitter.method().visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(ArithmeticGettable.class), "get",
					READ_VALUE, true);
			};
		}
		return null;
	}

	private interface Operand {
		void emit(BytecodeCompiler.Emitter emitter);
	}

	record Comparison(CondCompare condition, Operand first, Operand second, Relation relation, boolean negated,
					  boolean fixedNumber, boolean longs, boolean doubles) {
		void emit(BytecodeCompiler.Emitter emitter) {
			MethodVisitor method = emitter.method();
			Label absent = new Label();
			Label result = new Label();
			int left = emitter.newLocal(OBJECT);
			int right = emitter.newLocal(OBJECT);
			readOperand(emitter, first, left, absent);
			readOperand(emitter, second, right, absent);

			if (!fixedNumber) {
				Label different = new Label();
				method.visitVarInsn(ALOAD, left);
				method.visitVarInsn(ALOAD, right);
				method.visitJumpInsn(IF_ACMPNE, different);
				emitter.pushInt(relation.isImpliedBy(Relation.EQUAL) ? 1 : 0);
				method.visitJumpInsn(GOTO, result);
				method.visitLabel(different);
			}
			if (longs) {
				Label other = new Label();
				emitTypeTest(method, left, right, Long.class, other);
				emitLongRelation(emitter, left, right);
				method.visitJumpInsn(GOTO, result);
				method.visitLabel(other);
			}
			if (fixedNumber) {
				emitBuiltinNumber(emitter, left, right);
			} else {
				if (doubles) {
					Label other = new Label();
					emitTypeTest(method, left, right, Double.class, other);
					emitBuiltinNumber(emitter, left, right);
					method.visitJumpInsn(GOTO, result);
					method.visitLabel(other);
				}
				emitter.loadConstant(condition, CondCompare.class);
				method.visitVarInsn(ALOAD, left);
				method.visitVarInsn(ALOAD, right);
				method.visitMethodInsn(INVOKEVIRTUAL, COMPARE, "skatalystTestValues",
					Type.getMethodDescriptor(Type.BOOLEAN_TYPE, OBJECT, OBJECT), false);
			}
			method.visitJumpInsn(GOTO, result);
			method.visitLabel(absent);
			method.visitInsn(ICONST_0);
			method.visitLabel(result);
			if (negated) {
				method.visitInsn(ICONST_1);
				method.visitInsn(IXOR);
			}
		}

		private static void readOperand(BytecodeCompiler.Emitter emitter, Operand operand, int local, Label absent) {
			operand.emit(emitter);
			emitter.method().visitVarInsn(ASTORE, local);
			emitter.method().visitVarInsn(ALOAD, local);
			emitter.method().visitJumpInsn(IFNULL, absent);
		}

		private static void emitTypeTest(MethodVisitor method, int left, int right, Class<?> valueType, Label other) {
			String owner = Type.getInternalName(valueType);
			method.visitVarInsn(ALOAD, left);
			method.visitTypeInsn(INSTANCEOF, owner);
			method.visitJumpInsn(IFEQ, other);
			method.visitVarInsn(ALOAD, right);
			method.visitTypeInsn(INSTANCEOF, owner);
			method.visitJumpInsn(IFEQ, other);
		}

		private void emitLongRelation(BytecodeCompiler.Emitter emitter, int left, int right) {
			MethodVisitor method = emitter.method();
			emitLongValue(method, left);
			emitLongValue(method, right);
			Label pass = new Label();
			Label done = new Label();
			jumpIfLongRelation(method, relation, pass);
			method.visitInsn(ICONST_0);
			method.visitJumpInsn(GOTO, done);
			method.visitLabel(pass);
			method.visitInsn(ICONST_1);
			method.visitLabel(done);
		}

		private static void emitLongValue(MethodVisitor method, int local) {
			method.visitVarInsn(ALOAD, local);
			method.visitTypeInsn(CHECKCAST, "java/lang/Long");
			method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
		}

		private void emitBuiltinNumber(BytecodeCompiler.Emitter emitter, int left, int right) {
			MethodVisitor method = emitter.method();
			emitter.loadConstant(relation, Relation.class);
			method.visitVarInsn(ALOAD, left);
			method.visitTypeInsn(CHECKCAST, NUMBER.getInternalName());
			method.visitVarInsn(ALOAD, right);
			method.visitTypeInsn(CHECKCAST, NUMBER.getInternalName());
			method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(DefaultComparators.class), "compareNumbers",
				Type.getMethodDescriptor(RELATION, NUMBER, NUMBER), false);
			method.visitMethodInsn(INVOKEVIRTUAL, RELATION.getInternalName(), "isImpliedBy",
				Type.getMethodDescriptor(Type.BOOLEAN_TYPE, RELATION), false);
		}
	}
}
