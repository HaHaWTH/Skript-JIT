package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.objectweb.asm.Opcodes.IF_ACMPNE;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

@ApiStatus.Internal
public final class CoreOptimizations {

	private CoreOptimizations() { }

	public static Map<TriggerItem, BytecodeCompiler.Optimization> plan(TriggerSection trigger) {
		Map<TriggerItem, BytecodeCompiler.Optimization> result = new IdentityHashMap<>();
		collect(trigger, result);
		return Collections.unmodifiableMap(result);
	}

	private static void collect(TriggerSection section, Map<TriggerItem, BytecodeCompiler.Optimization> result) {
		ReferenceSlotOptimization.collect(section.skatalystItems(), result);
		for (TriggerItem item : section.skatalystItems()) {
			BytecodeCompiler.Optimization optimization = null;
			if (item.getClass() == SecLoop.class) {
				optimization = CountedLoopOptimization.plan((SecLoop) item);
			} else if (item.getClass() == EffChange.class) {
				optimization = StringConcatenationOptimization.plan((EffChange) item);
			}
			if (optimization != null)
				result.put(item, optimization);
			if (item instanceof TriggerSection child)
				collect(child, result);
		}
	}

	static @Nullable String scalarLocal(Expression<?> expression) {
		if (expression.getClass() != Variable.class)
			return null;
		Variable<?> variable = (Variable<?>) expression;
		if (!variable.isLocal() || variable.isList() || !variable.getName().isSimple())
			return null;
		String name = variable.getName().toString(null);
		// A slot has the same identity under either variable casing mode.
		return !name.contains(Variable.SEPARATOR) && name.equals(name.toLowerCase(Locale.ENGLISH)) ? name : null;
	}

	static void guardSuccessor(BytecodeCompiler.Emitter emitter, TriggerItem node, TriggerItem expected, Label mismatch) {
		emitter.loadActualNext(node);
		emitter.loadConstant(expected, TriggerItem.class);
		emitter.method().visitJumpInsn(IF_ACMPNE, mismatch);
	}

	static void guardScalarWrites(BytecodeCompiler.Emitter emitter, List<String> names, Label mismatch) {
		emitter.loadEvent();
		emitter.loadConstant(names, List.class);
		emitter.method().visitMethodInsn(INVOKESTATIC, Type.getInternalName(Variables.class), "skatalystCanBatchScalarWrites",
			Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(Event.class), Type.getType(List.class)), false);
		emitter.method().visitJumpInsn(IFEQ, mismatch);
	}
}
