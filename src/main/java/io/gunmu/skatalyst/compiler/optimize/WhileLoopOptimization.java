package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.data.DefaultOperations;
import ch.njol.skript.conditions.CondCompare;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.sections.SecConditional;
import ch.njol.skript.sections.SecWhile;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.control.ComparisonOptimizations;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.Slot;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.arithmetic.Operator;
import org.skriptlang.skript.lang.comparator.Relation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind.INTEGRAL;
import static org.objectweb.asm.Opcodes.*;

/** A closed while body of long additions and single-line if/else comparisons. */
final class WhileLoopOptimization {

	private static final Type ITEM = Type.getType(TriggerItem.class);
	private static final Type EVENT = Type.getType(Event.class);
	private static final String WHILE = Type.getInternalName(SecWhile.class);
	private static final int MAX_ITEMS = 24;

	private final List<Statement> body = new ArrayList<>();
	private final Map<TriggerItem, TriggerItem> edges = new LinkedHashMap<>();
	private final Set<String> names = new LinkedHashSet<>();
	private final Set<String> writes = new LinkedHashSet<>();
	private int additions;

	private WhileLoopOptimization() {}

	static @Nullable BytecodeCompiler.Optimization plan(SecWhile loop) {
		var plan = new WhileLoopOptimization();
		LongCondition condition = plan.test(loop.skatalystComparison());
		if (condition == null || !plan.collect(loop)
				|| !INTEGRAL.canStoreDirectly()
				|| !NumericSlots.usesOperation(Operator.ADDITION, Long.class, Long.class, DefaultOperations.ADD_NUMBERS))
			return null;
		return emitter -> plan.emit(loop, condition, emitter);
	}

	private boolean collect(SecWhile loop) {
		List<TriggerItem> items = loop.skatalystItems();
		if (items.isEmpty() || items.size() > MAX_ITEMS)
			return false;
		collectEdges(items);
		edges.put(items.getLast(), loop);
		for (int index = 0; index < items.size(); index++) {
			TriggerItem item = items.get(index);
			if (item.getClass() != SecConditional.class) {
				Add add = addition(item);
				if (add == null)
					return false;
				body.add(add);
				continue;
			}
			SecConditional branch = (SecConditional) item;
			LongCondition test = test(branch.skatalystSimpleIfComparison());
			Arm taken = arm(branch);
			if (test == null || taken == null)
				return false;
			Arm otherwise = null;
			if (index + 1 < items.size() && items.get(index + 1).getClass() == SecConditional.class
					&& ((SecConditional) items.get(index + 1)).skatalystIsElse()) {
				otherwise = arm((SecConditional) items.get(++index));
				if (otherwise == null)
					return false;
			}
			TriggerItem join = index + 1 < items.size() ? items.get(index + 1) : loop;
			body.add(new Branch(test, taken, otherwise, join));
		}
		return true;
	}

	private void collectEdges(List<TriggerItem> items) {
		for (int i = 1; i < items.size(); i++)
			edges.put(items.get(i - 1), items.get(i));
	}

	private @Nullable Arm arm(SecConditional section) {
		List<TriggerItem> items = section.skatalystItems();
		if (section.skatalystExposesLocals() || items.isEmpty())
			return null;
		List<Add> arm = new ArrayList<>();
		for (TriggerItem item : items) {
			Add add = addition(item);
			if (add == null)
				return null;
			arm.add(add);
		}
		// On entry Skript replaces the last edge, even when an addon rewired it.
		collectEdges(items);
		return new Arm(section, arm);
	}

	private @Nullable Add addition(TriggerItem item) {
		if (item.getClass() != EffChange.class || item.skatalystExposesLocals() || ++additions > MAX_ITEMS)
			return null;
		var effect = (EffChange) item;
		var change = effect.skatalystChange();
		if (change.mode() != ChangeMode.ADD || change.source() == null)
			return null;
		String target = CoreOptimizations.scalarLocal(change.target());
		Value source = value(change.source());
		if (target == null || source == null)
			return null;
		names.add(target);
		writes.add(target);
		return new Add(effect, target, source);
	}

	private @Nullable LongCondition test(@Nullable CondCompare compare) {
		if (compare == null)
			return null;
		var binary = compare.skatalystComparison();
		if (binary == null || !ComparisonOptimizations.usesLongComparator(binary))
			return null;
		Value left = value(binary.first());
		Value right = value(binary.second());
		Relation failed = compare.isNegated() ? binary.relation() : binary.relation().getInverse();
		return left != null && right != null ? new LongCondition(left, right, failed) : null;
	}

	private @Nullable Value value(Expression<?> expression) {
		String name = CoreOptimizations.scalarLocal(expression);
		if (name != null && ((Variable<?>) expression).skatalystAcceptsRaw(Long.class)) {
			names.add(name);
			return code -> code.method().visitVarInsn(LLOAD, code.slots().get(name).value());
		}
		if (NumericSlots.literal(expression) instanceof Long number)
			return code -> code.method().visitLdcInsn(number);
		return null;
	}

	private interface Value {
		void load(Code code);
	}

	private record LongCondition(Value left, Value right, Relation failed) {
		void jumpIfFalse(Code code, Label target) {
			left.load(code);
			right.load(code);
			ComparisonOptimizations.jumpIfLongRelation(code.method(), failed, target);
		}
	}

	private interface Statement {
		void emit(Code code);
	}

	private record Add(EffChange effect, String target, Value source) implements Statement {
		@Override
		public void emit(Code code) {
			MethodVisitor method = code.method();
			Slot slot = code.slots().get(target);
			source.load(code);
			method.visitVarInsn(LSTORE, code.operand());
			method.visitVarInsn(LLOAD, slot.value());
			method.visitVarInsn(LLOAD, code.operand());
			method.visitInsn(LADD);
			method.visitVarInsn(LSTORE, code.result());
			Label overflow = new Label();
			code.exits().put(overflow, effect);
			NumericSlots.guardIntegralResult(method, slot.value(), code.operand(), code.result(), overflow);
			method.visitVarInsn(LLOAD, code.result());
			method.visitVarInsn(LSTORE, slot.value());
			slot.markDirty(method, true);
		}
	}

	private record Arm(SecConditional section, List<Add> additions) {
		void emit(Code code, TriggerItem join) {
			BytecodeCompiler.Emitter emitter = code.emitter();
			MethodVisitor method = code.method();
			// Only taken arms patch their tail. Most visits already have the desired edge.
			Label ready = new Label();
			emitter.loadActualNext(additions.getLast().effect());
			emitter.loadConstant(join, TriggerItem.class);
			method.visitJumpInsn(IF_ACMPEQ, ready);
			emitter.loadConstant(section, SecConditional.class);
			method.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(SecConditional.class),
				"skatalystEnterBranch", Type.getMethodDescriptor(ITEM), false);
			method.visitInsn(POP);
			method.visitLabel(ready);
			for (Add add : additions)
				add.emit(code);
		}
	}

	private record Branch(LongCondition test, Arm taken, @Nullable Arm otherwise, TriggerItem join) implements Statement {
		@Override
		public void emit(Code code) {
			Label skip = new Label();
			test.jumpIfFalse(code, skip);
			taken.emit(code, join);
			if (otherwise != null) {
				Label end = new Label();
				code.method().visitJumpInsn(GOTO, end);
				code.method().visitLabel(skip);
				otherwise.emit(code, join);
				code.method().visitLabel(end);
			} else {
				code.method().visitLabel(skip);
			}
		}
	}

	private record Code(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, int operand, int result,
						Map<Label, EffChange> exits) {
		MethodVisitor method() {
			return emitter.method();
		}
	}

	private void emit(SecWhile loop, LongCondition condition, BytecodeCompiler.Emitter emitter) {
		MethodVisitor method = emitter.method();
		Label miss = new Label();
		Label head = new Label();
		Label enter = new Label();
		Label leave = new Label();
		Label spill = new Label();
		Label resume = new Label();
		Label done = new Label();
		edges.forEach((item, next) -> CoreOptimizations.guardSuccessor(emitter, item, next, miss));
		CoreOptimizations.guardScalarWrites(emitter, List.copyOf(writes), miss);

		Map<String, Slot> slots = new LinkedHashMap<>();
		int initial = emitter.newLocal(Type.getType(Object.class));
		for (String name : names) {
			Slot slot = new Slot(emitter, INTEGRAL);
			slots.put(name, slot);
			NumericSlots.emitRead(emitter, name, INTEGRAL, false, slot, initial, miss);
			slot.markDirty(method, false);
		}
		Code code = new Code(emitter, slots, emitter.newLocal(Type.LONG_TYPE), emitter.newLocal(Type.LONG_TYPE),
			new LinkedHashMap<>());
		int entered = emitter.newLocal(Type.LONG_TYPE);
		method.visitInsn(LCONST_0);
		method.visitVarInsn(LSTORE, entered);
		emitter.loadConstant(loop, SecWhile.class);
		emitter.loadEvent();
		method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystFirstIteration",
			Type.getMethodDescriptor(Type.BOOLEAN_TYPE, EVENT), false);
		method.visitJumpInsn(IFNE, enter);
		method.visitLabel(head);
		condition.jumpIfFalse(code, leave);
		method.visitLabel(enter);
		method.visitVarInsn(LLOAD, entered);
		method.visitInsn(LCONST_1);
		method.visitInsn(LADD);
		method.visitVarInsn(LSTORE, entered);
		for (Statement statement : body)
			statement.emit(code);
		method.visitJumpInsn(GOTO, head);

		for (var exit : code.exits().entrySet()) {
			method.visitLabel(exit.getKey());
			emitter.loadConstant(exit.getValue(), TriggerItem.class);
			method.visitJumpInsn(GOTO, spill);
		}
		method.visitLabel(leave);
		method.visitInsn(ACONST_NULL);
		method.visitLabel(spill);
		int cursor = emitter.newLocal(ITEM);
		method.visitVarInsn(ASTORE, cursor);
		NumericSlots.emitSpill(emitter, slots, INTEGRAL);
		method.visitVarInsn(ALOAD, cursor);
		method.visitJumpInsn(IFNONNULL, resume);
		emitter.loadConstant(loop, SecWhile.class);
		emitter.loadEvent();
		method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystLeaveLoop", Type.getMethodDescriptor(ITEM, EVENT), false);
		method.visitJumpInsn(GOTO, done);

		method.visitLabel(resume);
		emitter.loadConstant(loop, SecWhile.class);
		emitter.loadEvent();
		method.visitVarInsn(LLOAD, entered);
		method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystAdvanceIterations",
			Type.getMethodDescriptor(Type.VOID_TYPE, EVENT, Type.LONG_TYPE), false);
		method.visitVarInsn(ALOAD, cursor);
		method.visitJumpInsn(GOTO, done);

		method.visitLabel(miss);
		emitter.walkOriginal(loop);
		method.visitLabel(done);
	}
}
