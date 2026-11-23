package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.data.DefaultOperations;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.expressions.ExprLoopIteration;
import ch.njol.skript.expressions.ExprLoopValue;
import ch.njol.skript.expressions.ExprTimes;
import ch.njol.skript.expressions.arithmetic.ArithmeticChain;
import ch.njol.skript.expressions.arithmetic.ArithmeticExpressionInfo;
import ch.njol.skript.expressions.arithmetic.ArithmeticGettable;
import ch.njol.skript.expressions.arithmetic.ExprArithmetic;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.sections.SecLoop;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.Slot;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.arithmetic.Operation;
import org.skriptlang.skript.lang.arithmetic.OperationInfo;
import org.skriptlang.skript.lang.arithmetic.Operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

final class CountedLoopOptimization {

	private static final String EVENT = "Lorg/bukkit/event/Event;";
	private static final String LOOP = Type.getInternalName(SecLoop.class);
	private static final String TIMES = Type.getInternalName(ExprTimes.class);
	private static final List<Class<?>> INTEGRAL_TYPES = List.of(Long.class, Integer.class);

	// Keep the body of a single macro small enough for HotSpot to compile the surrounding dispatch.
	private static final int MAX_ARITHMETIC_NODES = 24;

	private CountedLoopOptimization() { }

	static @Nullable BytecodeCompiler.Optimization plan(SecLoop loop) {
		if (loop.getLoopedExpression().getClass() != ExprTimes.class
				|| loop.skatalystItems().isEmpty()
				|| loop.skatalystItems().size() > MAX_ARITHMETIC_NODES)
			return null;

		List<LoopBody> bodies = new ArrayList<>(NumericKind.values().length);
		for (NumericKind kind : NumericKind.values()) {
			LoopBody body = loopBody(loop, kind);
			if (body != null)
				bodies.add(body);
		}
		return bodies.isEmpty() ? null : new CountedLoop(loop, (ExprTimes) loop.getLoopedExpression(), List.copyOf(bodies));
	}

	private static @Nullable LoopBody loopBody(SecLoop loop, NumericKind kind) {
		if (!kind.canStoreDirectly())
			return null;
		List<Assignment> assignments = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		Set<String> defined = new LinkedHashSet<>();
		Map<String, Boolean> initialReads = new LinkedHashMap<>();
		int nodes = 0;
		for (TriggerItem item : loop.skatalystItems()) {
			if (item.getClass() != EffChange.class)
				return null;
			var change = ((EffChange) item).skatalystChange();
			String name = CoreOptimizations.scalarLocal(change.target());
			if (name == null)
				return null;
			NumberValue value = switch (change.mode()) {
				case SET -> change.source() != null && change.source().getClass() == ExprArithmetic.class
					? numeric(((ExprArithmetic<?, ?, ?>) change.source()).skatalystArithmetic(), kind, loop)
					: numericLeaf(change.source(), loop);
				case ADD, REMOVE -> {
					NumberValue operand = numericLeaf(change.source(), loop);
					Read read = new Read(name, kind, true);
					Operator operator = change.mode() == ChangeMode.ADD ? Operator.ADDITION : Operator.SUBTRACTION;
					yield operand != null && operand.possibleTypes().stream()
							.allMatch(type -> Classes.getSuperClassInfo(type).getCloner() == null)
							&& usesNumericOperation(operator, null, Object.class, read, operand)
						? new BinaryOperation(operator, read, operand)
						: null;
				}
				default -> null;
			};
			if (value == null || !value.possibleTypes().equals(List.of(kind.boxed))
					|| (nodes += value.nodes()) > MAX_ARITHMETIC_NODES)
				return null;
			Map<String, Boolean> reads = new LinkedHashMap<>();
			value.collectNames(reads);
			for (var read : reads.entrySet()) {
				if (!defined.contains(read.getKey()))
					initialReads.merge(read.getKey(), read.getValue(), (first, second) -> first && second);
			}
			names.addAll(reads.keySet());
			names.add(name);
			defined.add(name);
			assignments.add(new Assignment(item, name, value));
		}
		return new LoopBody(kind, List.copyOf(assignments), List.copyOf(names), Map.copyOf(initialReads));
	}

	private static @Nullable NumberValue numeric(ArithmeticGettable<?> arithmetic, NumericKind kind, SecLoop loop) {
		if (arithmetic instanceof ArithmeticExpressionInfo<?> info) {
			Expression<?> expression = info.expression();
			NumberValue leaf = numericLeaf(expression, loop);
			if (leaf != null)
				return leaf;
			String name = CoreOptimizations.scalarLocal(expression);
			if (name == null)
				return null;
			Variable<?> variable = (Variable<?>) expression;
			return variable.skatalystAcceptsRaw(kind.boxed)
				? new Read(name, kind, variable.skatalystAcceptsRaw(Integer.class))
				: null;
		}
		if (!(arithmetic instanceof ArithmeticChain<?, ?, ?> chain) || arithmetic.getClass() != ArithmeticChain.class)
			return null;
		var op = chain.skatalystOperation();
		NumberValue left = numeric(op.left(), kind, loop);
		NumberValue right = numeric(op.right(), kind, loop);
		return left != null && right != null
				&& usesNumericOperation(op.operator(), op.operationInfo(), chain.getReturnType(), left, right)
			? new BinaryOperation(op.operator(), left, right)
			: null;
	}

	private static @Nullable NumberValue numericLeaf(@Nullable Expression<?> expression, SecLoop loop) {
		if (expression == null)
			return null;
		if (expression.getClass() == ExprLoopValue.class && ((ExprLoopValue) expression).skatalystIsCurrentValueOf(loop))
			return Iteration.CURRENT;
		if (expression.getClass() == ExprLoopIteration.class && ((ExprLoopIteration) expression).skatalystIsIterationOf(loop))
			return Iteration.CURRENT;
		Number value = NumericSlots.literal(expression);
		return value == null ? null : new Constant(value);
	}

	private static boolean usesNumericOperation(Operator operator, @Nullable OperationInfo<?, ?, ?> parsed, Class<?> result,
			NumberValue left, NumberValue right) {
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
			return false;
		}
		if (parsed != null)
			return parsed.operation() == builtin;
		if (!result.isAssignableFrom(Number.class))
			return false;
		for (Class<?> leftType : left.possibleTypes()) {
			for (Class<?> rightType : right.possibleTypes()) {
				if (!NumericSlots.usesOperation(operator, leftType, rightType, builtin))
					return false;
			}
		}
		return true;
	}

	private sealed interface NumberValue permits Constant, Read, Iteration, BinaryOperation {
		void emit(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow);

		NumericKind kind();

		List<Class<?>> possibleTypes();

		default int nodes() {
			return 1;
		}

		default void collectNames(Map<String, Boolean> names) { }

		default boolean needsExit() {
			return false;
		}
	}

	private record Constant(Number value) implements NumberValue {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			if (kind() == NumericKind.DECIMAL) {
				emitter.method().visitLdcInsn(value.doubleValue());
			} else {
				emitter.method().visitLdcInsn(value.longValue());
			}
		}

		@Override
		public NumericKind kind() {
			return value instanceof Double ? NumericKind.DECIMAL : NumericKind.INTEGRAL;
		}

		@Override
		public List<Class<?>> possibleTypes() {
			return List.of(value.getClass());
		}
	}

	private enum Iteration implements NumberValue {
		CURRENT;

		@Override
		public void emit(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			emitter.method().visitVarInsn(LLOAD, iteration);
		}

		@Override
		public NumericKind kind() {
			return NumericKind.INTEGRAL;
		}

		@Override
		public List<Class<?>> possibleTypes() {
			return List.of(Long.class);
		}
	}

	private record Read(String name, NumericKind kind, boolean acceptsInteger) implements NumberValue {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			emitter.method().visitVarInsn(kind.loadOpcode(), slots.get(name).value());
		}

		@Override
		public List<Class<?>> possibleTypes() {
			return kind == NumericKind.INTEGRAL && acceptsInteger ? INTEGRAL_TYPES : List.of(kind.boxed);
		}

		@Override
		public void collectNames(Map<String, Boolean> names) {
			names.merge(name, acceptsInteger, (previous, current) -> previous && current);
		}
	}

	private record BinaryOperation(Operator operator, NumberValue left, NumberValue right) implements NumberValue {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, int iteration, Label overflow) {
			MethodVisitor method = emitter.method();
			NumericKind kind = kind();
			int baseOpcode = operator == Operator.ADDITION ? IADD : operator == Operator.SUBTRACTION ? ISUB
				: operator == Operator.MULTIPLICATION ? IMUL : IDIV;
			int leftLocal = emitter.newLocal(kind.type);
			int rightLocal = emitter.newLocal(kind.type);
			emitOperand(left, emitter, slots, iteration, overflow, kind);
			method.visitVarInsn(kind.storeOpcode(), leftLocal);
			emitOperand(right, emitter, slots, iteration, overflow, kind);
			method.visitVarInsn(kind.storeOpcode(), rightLocal);
			method.visitVarInsn(kind.loadOpcode(), leftLocal);
			method.visitVarInsn(kind.loadOpcode(), rightLocal);
			method.visitInsn(kind.type.getOpcode(baseOpcode));
			if (kind == NumericKind.DECIMAL)
				return;

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

		private static void emitOperand(NumberValue operand, BytecodeCompiler.Emitter emitter, Map<String, Slot> slots,
				int iteration, Label overflow, NumericKind kind) {
			operand.emit(emitter, slots, iteration, overflow);
			if (operand.kind() != kind)
				emitter.method().visitInsn(L2D);
		}

		@Override
		public NumericKind kind() {
			return operator == Operator.DIVISION || left.kind() == NumericKind.DECIMAL || right.kind() == NumericKind.DECIMAL
				? NumericKind.DECIMAL : NumericKind.INTEGRAL;
		}

		@Override
		public List<Class<?>> possibleTypes() {
			return List.of(kind().boxed);
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
			return kind() == NumericKind.INTEGRAL || left.needsExit() || right.needsExit();
		}
	}

	private record Assignment(TriggerItem effect, String name, NumberValue value) { }

	private record CountedLoop(SecLoop loop, ExprTimes times, List<LoopBody> bodies)
			implements BytecodeCompiler.Optimization {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter) {
			MethodVisitor method = emitter.method();
			Label active = new Label();
			Label empty = new Label();
			Label fallbackBody = new Label();
			Label done = new Label();

			emitter.loadConstant(loop, SecLoop.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, LOOP, "skatalystHasIterator", "(" + EVENT + ")Z", false);
			method.visitJumpInsn(IFNE, active);
			List<Assignment> assignments = bodies.getFirst().assignments();
			for (int index = 1; index < assignments.size(); index++) {
				CoreOptimizations.guardSuccessor(emitter, assignments.get(index - 1).effect(),
					assignments.get(index).effect(), active);
			}
			CoreOptimizations.guardSuccessor(emitter, assignments.getLast().effect(), loop, active);

			int limit = emitter.newLocal(Type.LONG_TYPE);
			int iteration = emitter.newLocal(Type.LONG_TYPE);
			emitter.loadConstant(times, ExprTimes.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, TIMES, "skatalystExclusiveLimit", "(" + EVENT + ")J", false);
			method.visitVarInsn(LSTORE, limit);
			method.visitInsn(LCONST_1);
			method.visitVarInsn(LSTORE, iteration);
			method.visitVarInsn(LLOAD, limit);
			method.visitInsn(LCONST_1);
			method.visitInsn(LCMP);
			method.visitJumpInsn(IFLE, empty);

			List<String> writes = assignments.stream().map(Assignment::name).distinct().toList();
			CoreOptimizations.guardScalarWrites(emitter, writes, fallbackBody);
			for (LoopBody body : bodies) {
				Label miss = new Label();
				body.emit(emitter, this, iteration, limit, miss, done);
				method.visitLabel(miss);
			}
			method.visitLabel(fallbackBody);
			emitResume(emitter, iteration, limit, assignments.getFirst().effect());
			method.visitJumpInsn(GOTO, done);

			method.visitLabel(empty);
			emitter.loadActualNext(loop);
			method.visitJumpInsn(GOTO, done);

			method.visitLabel(active);
			emitter.walkOriginal(loop);
			method.visitLabel(done);
		}

		private void emitResume(BytecodeCompiler.Emitter emitter, int iteration, int limit, TriggerItem item) {
			emitter.loadConstant(loop, SecLoop.class);
			emitter.loadEvent();
			emitter.method().visitVarInsn(LLOAD, iteration);
			emitter.method().visitVarInsn(LLOAD, limit);
			emitter.method().visitMethodInsn(INVOKEVIRTUAL, LOOP, "skatalystResumeCounted", "(" + EVENT + "JJ)V", false);
			emitter.loadConstant(item, TriggerItem.class);
		}
	}

	private record LoopBody(NumericKind kind, List<Assignment> assignments, List<String> names,
			Map<String, Boolean> initialReads) {
		private void emit(BytecodeCompiler.Emitter emitter, CountedLoop loop, int iteration, int limit, Label miss, Label done) {
			MethodVisitor method = emitter.method();
			Label head = new Label();
			Map<String, Slot> slots = new LinkedHashMap<>();
			int initial = emitter.newLocal(Type.getType(Object.class));
			for (String name : names) {
				var slot = new Slot(emitter, kind);
				slots.put(name, slot);
				Boolean acceptsInteger = initialReads.get(name);
				if (acceptsInteger != null) {
					NumericSlots.emitRead(emitter, name, kind, acceptsInteger, slot, initial, miss);
				} else {
					method.visitInsn(kind == NumericKind.INTEGRAL ? LCONST_0 : DCONST_0);
					method.visitVarInsn(kind.storeOpcode(), slot.value());
				}
				slot.markDirty(method, false);
			}

			method.visitLabel(head);
			Map<Label, TriggerItem> overflowExits = new LinkedHashMap<>();
			for (Assignment assignment : assignments) {
				Label overflow = new Label();
				if (assignment.value().needsExit())
					overflowExits.put(overflow, assignment.effect());
				assignment.value().emit(emitter, slots, iteration, overflow);
				Slot slot = slots.get(assignment.name());
				method.visitVarInsn(kind.storeOpcode(), slot.value());
				slot.markDirty(method, true);
			}
			method.visitVarInsn(LLOAD, iteration);
			method.visitInsn(LCONST_1);
			method.visitInsn(LADD);
			method.visitVarInsn(LSTORE, iteration);
			method.visitVarInsn(LLOAD, iteration);
			method.visitVarInsn(LLOAD, limit);
			method.visitInsn(LCMP);
			method.visitJumpInsn(IFLT, head);

			NumericSlots.emitSpill(emitter, slots, kind);
			emitter.loadActualNext(loop.loop());
			method.visitJumpInsn(GOTO, done);

			for (var exit : overflowExits.entrySet()) {
				method.visitLabel(exit.getKey());
				NumericSlots.emitSpill(emitter, slots, kind);
				loop.emitResume(emitter, iteration, limit, exit.getValue());
				method.visitJumpInsn(GOTO, done);
			}
		}
	}

}
