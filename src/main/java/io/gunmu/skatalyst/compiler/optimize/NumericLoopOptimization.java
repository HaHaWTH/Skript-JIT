package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.expressions.ExprTimes;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.sections.SecWhile;
import io.gunmu.skatalyst.addon.ParsedBinding.NumericCall;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.BytecodeCompiler.Emitter;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Set;

import static io.gunmu.skatalyst.compiler.optimize.NumericRegion.EVENT;
import static io.gunmu.skatalyst.compiler.optimize.NumericRegion.ITEM;
import static org.objectweb.asm.Opcodes.*;

final class NumericLoopOptimization {
	private static final String WHILE = Type.getInternalName(SecWhile.class);
	private static final String LOOP = Type.getInternalName(SecLoop.class);

	private NumericLoopOptimization() {
	}

	static @Nullable BytecodeCompiler.Optimization plan(LoopSection loop) {
		if (loop instanceof SecLoop counted && (counted.skatalystExposesLocals()
				|| counted.getLoopedExpression().getClass() != ExprTimes.class)) {
			return null;
		}
		var plans = NumericRegion.plans(loop, loop.skatalystItems(), loop instanceof SecWhile w ? w.skatalystComparison() : null, null);
		if (plans.isEmpty()) {
			return null;
		}
		Set<NumericCall> intrinsics = CoreOptimizations.numericIntrinsics(plans);
		BytecodeCompiler.Optimization optimization = emitter -> emit(loop, plans, intrinsics, emitter);
		return intrinsics.isEmpty() ? optimization : BytecodeCompiler.Optimization.ownedExposure(optimization);
	}

	private static void emit(LoopSection loop, List<NumericRegion> plans, Set<NumericCall> intrinsics, Emitter emitter) {
		MethodVisitor method = emitter.method();
		Label original = new Label();
		Label miss = new Label();
		Label complete = new Label();
		Label done = new Label();
		CoreOptimizations.guardNumericIntrinsics(emitter, intrinsics, original);
		int iteration = emitter.newLocal(Type.LONG_TYPE);
		int limit = -1;
		if (loop instanceof SecLoop counted) {
			/*
			 * if (counted.skatalystHasIterator(event)) {
			 *     next = TriggerItem.skatalystStep(counted, event);
			 *     break dispatch;
			 * }
			 * long limit = times.skatalystExclusiveLimit(event);
			 * long iteration = 1L;
			 * if (iteration >= limit) {
			 *     next = counted.getActualNext();
			 *     break dispatch;
			 * }
			 */
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
		var inputs = NumericRegion.sharedInputs(plans, emitter);
		for (NumericRegion plan : plans) {
			Label nextKind = new Label();
			Label head = new Label();
			Label advance = loop instanceof SecWhile ? head : new Label();
			Label leave = new Label();
			NumericRegion.Code code = plan.enter(emitter, inputs, plan == first, iteration, nextKind, advance, leave);
			plan.cloning.guard(emitter, miss);
			if (loop instanceof SecWhile) {
				/*
				 * long iteration = whileLoop.skatalystCompletedIterations(event);
				 * for (boolean first = whileLoop.skatalystFirstIteration(event);
				 *      first || condition; first = false) {
				 *     ++iteration;
				 *     // Execute the emitted loop body.
				 * }
				 * A continue jumps straight to the condition. Only normal fallthrough needs a back edge here.
				 */
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
				if (plan.emitBody(code)) {
					method.visitJumpInsn(GOTO, head);
				}
			} else {
				/*
				 * do {
				 *     // Execute the emitted body for the current iteration.
				 * } while (++iteration < limit);
				 * Both normal fallthrough and continue reach the increment; exit jumps past it.
				 */
				method.visitLabel(head);
				boolean fallsThrough = plan.emitBody(code);
				if (fallsThrough || plan.hasContinue) {
					method.visitLabel(advance);
					increment(method, iteration);
					method.visitVarInsn(LLOAD, iteration);
					method.visitVarInsn(LLOAD, limit);
					method.visitInsn(LCMP);
					method.visitJumpInsn(IFLT, head);
				}
			}
			method.visitLabel(leave);
			int cursor = code.exitCursor();
			NumericSlots.emitSpill(emitter, code.slots(), plan.writes);
			/*
			 * After writing back dirty locals, the counted path continues like this.
			 * if (cursor == null) {
			 *     next = counted.getActualNext();
			 * } else {
			 *     counted.skatalystResumeCounted(event, iteration, limit);
			 *     next = cursor;
			 * }
			 * A while loop uses its own leave and restore methods at these two exits.
			 */
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
			if (plan == first && inputs != null) {
				inputs.readRemainingAfterMiss(emitter);
			}
		}
		method.visitLabel(miss);
		if (loop instanceof SecLoop counted) {
			/*
			 * counted.skatalystResumeCounted(event, iteration, limit);
			 * next = firstBodyItem;
			 * // The original body consumes the already evaluated bound.
			 */
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
