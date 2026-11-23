package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.expressions.ExprTimes;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.sections.SecWhile;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.BytecodeCompiler.Emitter;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

import static io.gunmu.skatalyst.compiler.optimize.NumericRegion.EVENT;
import static io.gunmu.skatalyst.compiler.optimize.NumericRegion.ITEM;
import static org.objectweb.asm.Opcodes.*;

final class NumericLoopOptimization {
	private static final String WHILE = Type.getInternalName(SecWhile.class);
	private static final String LOOP = Type.getInternalName(SecLoop.class);

	private NumericLoopOptimization() {
	}

	static @Nullable BytecodeCompiler.Optimization plan(LoopSection loop) {
		if (loop.skatalystExposesLocals()) {
			return null;
		}
		if (loop instanceof SecLoop counted && counted.getLoopedExpression().getClass() != ExprTimes.class) {
			return null;
		}
		var plans = NumericRegion.plans(loop, loop.skatalystItems(), loop instanceof SecWhile w ? w.skatalystComparison() : null);
		return plans.isEmpty() ? null : emitter -> emit(loop, plans, emitter);
	}

	private static void emit(LoopSection loop, List<NumericRegion> plans, Emitter emitter) {
		MethodVisitor method = emitter.method();
		Label original = new Label();
		Label miss = new Label();
		Label complete = new Label();
		Label done = new Label();
		int iteration = emitter.newLocal(Type.LONG_TYPE);
		int limit = -1;
		if (loop instanceof SecLoop counted) {
			emitter.loadConstant(loop, SecLoop.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, LOOP, "skatalystHasIterator", Type.getMethodDescriptor(Type.BOOLEAN_TYPE, EVENT), false);
			method.visitJumpInsn(IFNE, original);
			limit = emitter.newLocal(Type.LONG_TYPE);
			emitter.loadConstant(counted.getLoopedExpression(), ExprTimes.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ExprTimes.class), "skatalystExclusiveLimit",
				Type.getMethodDescriptor(Type.LONG_TYPE, EVENT), false);
			method.visitVarInsn(LSTORE, limit);
			method.visitInsn(LCONST_1);
			method.visitVarInsn(LSTORE, iteration);
			method.visitVarInsn(LLOAD, limit);
			method.visitInsn(LCONST_1);
			method.visitInsn(LCMP);
			method.visitJumpInsn(IFLE, complete);
		}
		NumericRegion first = plans.getFirst();
		first.edges.forEach((item, next) -> CoreOptimizations.guardSuccessor(emitter, item, next, miss));
		CoreOptimizations.guardScalarWrites(emitter, List.copyOf(first.writes.keySet()), miss);
		for (NumericRegion plan : plans) {
			Label nextKind = new Label();
			Label head = new Label();
			Label advance = new Label();
			Label leave = new Label();
			NumericRegion.Code code = plan.enter(emitter, iteration, nextKind, advance, leave);
			if (loop instanceof SecWhile) {
				emitter.loadConstant(loop, SecWhile.class);
				emitter.loadEvent();
				method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystCompletedIterations", Type.getMethodDescriptor(Type.LONG_TYPE, EVENT), false);
				method.visitVarInsn(LSTORE, iteration);
				Label enter = new Label();
				emitter.loadConstant(loop, SecWhile.class);
				emitter.loadEvent();
				method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystFirstIteration", Type.getMethodDescriptor(Type.BOOLEAN_TYPE, EVENT), false);
				method.visitJumpInsn(IFNE, enter);
				method.visitLabel(head);
				plan.jumpIfFalse(code, leave);
				method.visitLabel(enter);
				increment(method, iteration);
				plan.emitBody(code);
				method.visitLabel(advance);
				method.visitJumpInsn(GOTO, head);
			} else {
				method.visitLabel(head);
				plan.emitBody(code);
				method.visitLabel(advance);
				increment(method, iteration);
				method.visitVarInsn(LLOAD, iteration);
				method.visitVarInsn(LLOAD, limit);
				method.visitInsn(LCMP);
				method.visitJumpInsn(IFLT, head);
			}
			method.visitLabel(leave);
			int cursor = code.exitCursor();
			NumericSlots.emitSpill(emitter, code.slots(), plan.writes);
			method.visitVarInsn(ALOAD, cursor);
			method.visitJumpInsn(IFNULL, complete);
			if (loop instanceof SecWhile) {
				emitter.loadConstant(loop, SecWhile.class);
				emitter.loadEvent();
				method.visitVarInsn(LLOAD, iteration);
				method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystRestoreIterations",
					Type.getMethodDescriptor(Type.VOID_TYPE, EVENT, Type.LONG_TYPE), false);
			} else {
				restoreCounted(emitter, (SecLoop) loop, iteration, limit);
			}
			method.visitVarInsn(ALOAD, cursor);
			method.visitJumpInsn(GOTO, done);
			method.visitLabel(nextKind);
		}
		method.visitLabel(miss);
		if (loop instanceof SecLoop counted) {
			// The times bound has already been evaluated once, including any side effects
			restoreCounted(emitter, counted, iteration, limit);
			emitter.loadConstant(loop.skatalystItems().getFirst(), TriggerItem.class);
			method.visitJumpInsn(GOTO, done);
		}
		method.visitLabel(original);
		emitter.walkOriginal(loop);
		method.visitJumpInsn(GOTO, done);
		method.visitLabel(complete);
		if (loop instanceof SecWhile) {
			emitter.loadConstant(loop, SecWhile.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, WHILE, "skatalystLeaveLoop", Type.getMethodDescriptor(ITEM, EVENT), false);
		} else {
			emitter.loadActualNext(loop);
		}
		method.visitLabel(done);
	}

	private static void increment(MethodVisitor method, int iteration) {
		method.visitVarInsn(LLOAD, iteration);
		method.visitInsn(LCONST_1);
		method.visitInsn(LADD);
		method.visitVarInsn(LSTORE, iteration);
	}

	private static void restoreCounted(Emitter emitter, SecLoop loop, int iteration, int limit) {
		emitter.loadConstant(loop, SecLoop.class);
		emitter.loadEvent();
		emitter.method().visitVarInsn(LLOAD, iteration);
		emitter.method().visitVarInsn(LLOAD, limit);
		emitter.method().visitMethodInsn(INVOKEVIRTUAL, LOOP, "skatalystResumeCounted",
			Type.getMethodDescriptor(Type.VOID_TYPE, EVENT, Type.LONG_TYPE, Type.LONG_TYPE), false);
	}
}
