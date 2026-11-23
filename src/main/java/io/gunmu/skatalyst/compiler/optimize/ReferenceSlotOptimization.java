package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.LiteralString;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.simplification.SimplifiedLiteral;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.slot.Slot;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.objectweb.asm.Opcodes.*;

@ApiStatus.Internal
public final class ReferenceSlotOptimization {

	// Each region adds a guard/load per live input plus a spill per overwritten local to its chunk.
	private static final int MAX_REGION_ITEMS = 16;
	private static final Type OBJECT_TYPE = Type.getType(Object.class);
	private static final String OWNER = Type.getInternalName(ReferenceSlotOptimization.class);
	private static final String VARIABLES = Type.getInternalName(Variables.class);
	private static final String OBJECT = OBJECT_TYPE.getDescriptor();
	private static final String EVENT = Type.getDescriptor(Event.class);
	private static final String STRING = Type.getDescriptor(String.class);
	private static final String LIST = Type.getDescriptor(List.class);

	private ReferenceSlotOptimization() { }

	static void collect(List<TriggerItem> items, Map<TriggerItem, BytecodeCompiler.Optimization> result) {
		List<Assignment> region = new ArrayList<>();
		for (TriggerItem item : items) {
			Assignment assignment = assignment(item);
			if (assignment == null || region.size() == MAX_REGION_ITEMS) {
				planRegion(region, result);
				region.clear();
			}
			if (assignment != null)
				region.add(assignment);
		}
		planRegion(region, result);
	}

	private static @Nullable Assignment assignment(TriggerItem item) {
		if (item.getClass() != EffChange.class)
			return null;
		var change = ((EffChange) item).skatalystChange();
		if (change.mode() != ChangeMode.SET || change.source() == null)
			return null;
		String target = CoreOptimizations.scalarLocal(change.target());
		if (target == null)
			return null;
		String input = CoreOptimizations.scalarLocal(change.source());
		if (input != null)
			return new Assignment(item, target, new Reference(input, (Variable<?>) change.source()));
		Object literal = literal(change.source());
		return literal != null ? new Assignment(item, target, new Constant(literal)) : null;
	}

	private static @Nullable Object literal(Expression<?> source) {
		if (source.getClass() != SimpleLiteral.class && source.getClass() != LiteralString.class
				&& !(source.getClass() == SimplifiedLiteral.class && source.getSource().getClass() == LiteralString.class))
			return null;
		Object[] values = ((Literal<?>) source).getAll();
		return values.length == 1 && canCopyDirectly(values[0]) ? values[0] : null;
	}

	private static void planRegion(List<Assignment> assignments, Map<TriggerItem, BytecodeCompiler.Optimization> result) {
		if (assignments.size() < 2)
			return;

		Map<String, Origin> current = new LinkedHashMap<>();
		Map<String, List<Variable<?>>> inputs = new LinkedHashMap<>();
		Set<String> names = new LinkedHashSet<>();
		Map<String, Assignment> writes = new LinkedHashMap<>();
		for (Assignment assignment : assignments) {
			Origin origin;
			if (assignment.value() instanceof Reference reference) {
				names.add(reference.name());
				origin = current.computeIfAbsent(reference.name(), Input::new);
				if (origin instanceof Input input) {
					inputs.computeIfAbsent(input.name(), name -> new ArrayList<>()).add(reference.view());
				} else {
					Object constant = ((Constant) origin).value();
					if (!reference.view().skatalystAcceptsRaw(constant.getClass()))
						return;
				}
			} else {
				origin = (Constant) assignment.value();
			}
			names.add(assignment.target());
			current.put(assignment.target(), origin);
			// Preserve the order in which new locals were first inserted while attributing each spill to its last write.
			writes.put(assignment.target(), assignment);
		}

		List<LiveInput> liveInputs = new ArrayList<>(inputs.size());
		for (var input : inputs.entrySet())
			liveInputs.add(new LiveInput(input.getKey(), List.copyOf(input.getValue())));
		var plan = new Region(List.copyOf(assignments), List.copyOf(liveInputs), List.copyOf(names), List.copyOf(writes.values()));
		result.put(assignments.getFirst().effect(), plan);
	}

	/** Tests the original getters, beforeChange clone and serialized representation at a region's entry. */
	public static boolean acceptsInput(@Nullable Object value, List<Variable<?>> views) {
		if (!canCopyDirectly(value))
			return false;
		for (Variable<?> view : views) {
			if (!view.skatalystAcceptsRaw(value.getClass()))
				return false;
		}
		return true;
	}

	private static boolean canCopyDirectly(@Nullable Object value) {
		if (value == null || value instanceof Slot || value instanceof Player
				|| value instanceof TreeMap<?, ?> || value.getClass().isArray())
			return false;
		ClassInfo<?> info = Classes.getSuperClassInfo(value.getClass());
		return info.getClass() == ClassInfo.class && info.getCloner() == null && info.getSerializeAs() == null;
	}

	private sealed interface Operand permits Constant, Reference { }

	private sealed interface Origin permits Constant, Input { }

	private record Constant(Object value) implements Operand, Origin { }

	private record Reference(String name, Variable<?> view) implements Operand { }

	private record Input(String name) implements Origin { }

	private record Assignment(TriggerItem effect, String target, Operand value) { }

	private record LiveInput(String name, List<Variable<?>> views) { }

	private record Region(List<Assignment> assignments, List<LiveInput> inputs, List<String> names, List<Assignment> writes)
			implements BytecodeCompiler.Optimization {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter) {
			MethodVisitor method = emitter.method();
			Label miss = new Label();
			Label done = new Label();
			for (int index = 1; index < assignments.size(); index++) {
				CoreOptimizations.guardSuccessor(emitter, assignments.get(index - 1).effect(),
					assignments.get(index).effect(), miss);
			}
			CoreOptimizations.guardScalarWrites(emitter, writes.stream().map(Assignment::target).toList(), miss);
			Map<String, Integer> slots = new LinkedHashMap<>();
			for (String name : names)
				slots.put(name, emitter.newLocal(OBJECT_TYPE));
			for (LiveInput input : inputs) {
				int slot = slots.get(input.name());
				emitter.loadConstant(input.name(), String.class);
				emitter.loadEvent();
				method.visitInsn(ICONST_1);
				method.visitMethodInsn(INVOKESTATIC, VARIABLES, "getVariable", "(" + STRING + EVENT + "Z)" + OBJECT, false);
				method.visitVarInsn(ASTORE, slot);
				method.visitVarInsn(ALOAD, slot);
				emitter.loadConstant(input.views(), List.class);
				method.visitMethodInsn(INVOKESTATIC, OWNER, "acceptsInput", "(" + OBJECT + LIST + ")Z", false);
				method.visitJumpInsn(IFEQ, miss);
			}

			for (Assignment assignment : assignments) {
				if (assignment.value() instanceof Constant constant) {
					emitter.loadConstant(constant.value(), Object.class);
				} else {
					method.visitVarInsn(ALOAD, slots.get(((Reference) assignment.value()).name()));
				}
				method.visitVarInsn(ASTORE, slots.get(assignment.target()));
			}
			for (Assignment assignment : writes) {
				emitter.setCurrent(assignment.effect());
				emitter.loadConstant(assignment.target(), String.class);
				method.visitVarInsn(ALOAD, slots.get(assignment.target()));
				emitter.loadEvent();
				method.visitInsn(ICONST_1);
				method.visitMethodInsn(INVOKESTATIC, VARIABLES, "setVariable", "(" + STRING + OBJECT + EVENT + "Z)V", false);
			}
			emitter.loadRegionSuccessor(assignments.getLast().effect());
			method.visitJumpInsn(GOTO, done);
			method.visitLabel(miss);
			emitter.walkOriginal(assignments.getFirst().effect());
			method.visitLabel(done);
		}
	}
}
