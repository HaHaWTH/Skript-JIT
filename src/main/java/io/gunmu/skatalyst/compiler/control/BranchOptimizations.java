package io.gunmu.skatalyst.compiler.control;

import ch.njol.skript.conditions.CondCompare;
import ch.njol.skript.effects.EffContinue;
import ch.njol.skript.effects.EffExit;
import ch.njol.skript.effects.EffReturn;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.sections.SecConditional;
import ch.njol.skript.sections.SecWhile;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

@ApiStatus.Internal
public final class BranchOptimizations {

	private static final Type EVENT = Type.getType(Event.class);
	private static final Type ITEM = Type.getType(TriggerItem.class);

	private BranchOptimizations() {
	}

	public static Map<TriggerItem, BytecodeCompiler.Optimization> plan(TriggerSection trigger) {
		Map<TriggerItem, BytecodeCompiler.Optimization> plans = new IdentityHashMap<>();
		collect(trigger, plans);
		return plans;
	}

	private static void collect(TriggerSection section, Map<TriggerItem, BytecodeCompiler.Optimization> plans) {
		for (TriggerItem node : section.skatalystItems()) {
			BytecodeCompiler.Optimization optimization = planNode(node);
			if (optimization != null) {
				plans.put(node, optimization);
			}
			if (node instanceof TriggerSection child) {
				collect(child, plans);
			}
		}
	}

	private static @Nullable BytecodeCompiler.Optimization planNode(TriggerItem node) {
		Class<?> type = node.getClass();
		if (type == SecConditional.class) {
			var comparison = ComparisonOptimizations.plan(((SecConditional) node).skatalystComparison());
			return emitter -> emitConditional(emitter, node, comparison);
		}
		if (type == SecWhile.class) {
			var comparison = ComparisonOptimizations.plan(((SecWhile) node).skatalystComparison());
			return emitter -> emitWhile(emitter, node, comparison);
		}
		if (type == CondCompare.class) {
			var comparison = ComparisonOptimizations.plan((CondCompare) node);
			return comparison == null ? null : emitter -> emitConditionStatement(emitter, (CondCompare) node, comparison);
		}
		if (type == EffReturn.class) {
			return emitter -> invokeWithEvent(emitter, node, "skatalystReturn", ITEM);
		}
		if (type == EffExit.class) {
			return emitter -> invokeWithEvent(emitter, node, "skatalystExit", ITEM);
		}
		if (type == EffContinue.class) {
			LoopSection loop = ((EffContinue) node).skatalystLoop();
			return emitter -> {
				invokeWithEvent(emitter, node, "skatalystLeaveSections", Type.VOID_TYPE);
				emitter.finishAt(loop);
			};
		}
		return null;
	}

	private static void emitConditional(BytecodeCompiler.Emitter emitter, TriggerItem node, @Nullable ComparisonOptimizations.Comparison comparison) {
		Label skip = new Label();
		Label done = new Label();
		if (comparison == null) {
			invokeWithEvent(emitter, node, "skatalystCondition", Type.BOOLEAN_TYPE);
		} else {
			comparison.emit(emitter);
		}
		emitter.method().visitJumpInsn(IFEQ, skip);
		emitter.loadConstant(node, SecConditional.class);
		emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(SecConditional.class),
			"skatalystEnterBranch", Type.getMethodDescriptor(ITEM), false);
		emitter.method().visitJumpInsn(GOTO, done);
		emitter.method().visitLabel(skip);
		emitter.loadActualNext(node);
		emitter.method().visitLabel(done);
	}

	private static void emitWhile(BytecodeCompiler.Emitter emitter, TriggerItem node, @Nullable ComparisonOptimizations.Comparison comparison) {
		Label enter = new Label();
		Label exit = new Label();
		Label done = new Label();
		if (comparison == null) {
			invokeWithEvent(emitter, node, "skatalystCondition", Type.BOOLEAN_TYPE);
		} else {
			invokeWithEvent(emitter, node, "skatalystFirstIteration", Type.BOOLEAN_TYPE);
			emitter.method().visitJumpInsn(IFNE, enter);
			comparison.emit(emitter);
		}
		emitter.method().visitJumpInsn(IFEQ, exit);
		emitter.method().visitLabel(enter);
		invokeWithEvent(emitter, node, "skatalystEnterLoop", ITEM);
		emitter.method().visitJumpInsn(GOTO, done);
		emitter.method().visitLabel(exit);
		invokeWithEvent(emitter, node, "skatalystLeaveLoop", ITEM);
		emitter.method().visitLabel(done);
	}

	private static void emitConditionStatement(BytecodeCompiler.Emitter emitter, CondCompare node, ComparisonOptimizations.Comparison comparison) {
		int result = emitter.newLocal(Type.BOOLEAN_TYPE);
		comparison.emit(emitter);
		emitter.method().visitVarInsn(ISTORE, result);
		emitter.loadConstant(node, CondCompare.class);
		emitter.loadEvent();
		emitter.method().visitVarInsn(ILOAD, result);
		emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(CondCompare.class), "skatalystContinuation",
			Type.getMethodDescriptor(ITEM, EVENT, Type.BOOLEAN_TYPE), false);
	}

	private static void invokeWithEvent(BytecodeCompiler.Emitter emitter, TriggerItem node, String name, Type returnType) {
		Class<?> type = node.getClass();
		emitter.loadConstant(node, type);
		emitter.loadEvent();
		emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(type), name,
			Type.getMethodDescriptor(returnType, EVENT), false);
	}
}
