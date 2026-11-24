package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.conditions.CondCompare;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.expressions.arithmetic.ExprArithmetic;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.sections.SecConditional;
import io.gunmu.skatalyst.addon.ParsedBinding.NumericCall;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.BytecodeCompiler.Emitter;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.gunmu.skatalyst.compiler.optimize.NumericRegion.EVENT;
import static io.gunmu.skatalyst.compiler.optimize.NumericRegion.ITEM;
import static org.objectweb.asm.Opcodes.*;

final class ScalarArithmeticOptimization {
	private ScalarArithmeticOptimization() {
	}

	static void collect(List<TriggerItem> items, Map<TriggerItem, BytecodeCompiler.Optimization> result) {
		int start = 0;
		while (start < items.size()) {
			int end = start;
			while (end < items.size() && end - start < NumericRegion.MAX_NODES && candidate(items.get(end))) {
				end++;
			}
			// Retain the longest prefix compatible with one set of primitive slots.
			while (end > start) {
				List<TriggerItem> changes = items.subList(start, end);
				if (changes.stream().noneMatch(ScalarArithmeticOptimization::arithmetic)) {
					break;
				}
				TriggerItem following = end < items.size() ? items.get(end) : null;
				CondCompare compare = comparison(following);
				var plans = NumericRegion.plans(null, changes, compare, compare == null ? null : following);
				if (plans.isEmpty() && compare != null) {
					plans = NumericRegion.plans(null, changes, null, null);
					compare = null;
				}
				if (!plans.isEmpty()) {
					TriggerItem first = changes.getFirst();
					TriggerItem last = changes.getLast();
					result.put(first, plan(first, last, plans, compare == null ? null : following));
					start = end - 1;
					break;
				}
				end--;
			}
			start++;
		}
	}

	private static boolean candidate(TriggerItem item) {
		if (item.getClass() != EffChange.class || !CoreOptimizations.numericCandidate(item)) {
			return false;
		}
		var change = ((EffChange) item).skatalystChange();
		if (CoreOptimizations.scalarLocal(change.target()) == null || change.source() == null) {
			return false;
		}
		return switch (change.mode()) {
			case SET, ADD, REMOVE -> change.source().getClass() == ExprArithmetic.class
				|| NumericSlots.literal(change.source()) != null || CoreOptimizations.scalarLocal(change.source()) != null
				|| CoreOptimizations.hasNumericIntrinsics(item);
			default -> false;
		};
	}

	private static boolean arithmetic(TriggerItem item) {
		var change = ((EffChange) item).skatalystChange();
		return switch (change.mode()) {
			case ADD, REMOVE -> true;
			default -> change.source().getClass() == ExprArithmetic.class || CoreOptimizations.hasNumericIntrinsics(item);
		};
	}

	private static @Nullable CondCompare comparison(@Nullable TriggerItem following) {
		if (following == null || !CoreOptimizations.numericCandidate(following)) return null;
		if (following.getClass() == CondCompare.class) return (CondCompare) following;
		return following.getClass() == SecConditional.class ? ((SecConditional) following).skatalystSimpleIfComparison() : null;
	}

	private static BytecodeCompiler.Optimization plan(TriggerItem first, TriggerItem last, List<NumericRegion> plans, @Nullable TriggerItem comparison) {
		Set<NumericCall> intrinsics = CoreOptimizations.numericIntrinsics(plans);
		BytecodeCompiler.Optimization optimization = emitter -> {
			MethodVisitor method = emitter.method();
			Label miss = new Label();
			Label done = new Label();
			CoreOptimizations.guardNumericIntrinsics(emitter, intrinsics, miss);
			NumericRegion region = plans.getFirst();
			region.edges.forEach((item, next) -> CoreOptimizations.guardSuccessor(emitter, item, next, miss));
			if (comparison != null) {
				CoreOptimizations.guardSuccessor(emitter, last, comparison, miss);
			}
			CoreOptimizations.guardScalarWrites(emitter, List.copyOf(region.writes.keySet()), miss);
			for (NumericRegion variant : plans) {
				Label nextKind = new Label();
				var code = variant.enter(emitter, -1, nextKind, null, null);
				variant.emitBody(code);
				int cursor = code.exitCursor();
				NumericSlots.emitSpill(emitter, code.slots(), variant.writes);
				Label finished = new Label();
				method.visitVarInsn(ALOAD, cursor);
				method.visitJumpInsn(IFNULL, finished);
				// Re-entering this region at its own first overflowing change would retry it forever.
				method.visitVarInsn(ALOAD, cursor);
				emitter.loadConstant(first, TriggerItem.class);
				method.visitJumpInsn(IF_ACMPEQ, miss);
				method.visitVarInsn(ALOAD, cursor);
				method.visitJumpInsn(GOTO, done);
				method.visitLabel(finished);
				if (comparison == null) {
					emitter.loadRegionSuccessor(last);
				} else {
					Label failed = new Label();
					variant.jumpIfFalse(code, failed);
					continuation(emitter, comparison, true);
					method.visitJumpInsn(GOTO, done);
					method.visitLabel(failed);
					continuation(emitter, comparison, false);
				}
				method.visitJumpInsn(GOTO, done);
				method.visitLabel(nextKind);
			}
			method.visitLabel(miss);
			emitter.walkOriginal(first);
			method.visitLabel(done);
		};
		return intrinsics.isEmpty() ? optimization : BytecodeCompiler.Optimization.ownedExposure(optimization);
	}

	private static void continuation(Emitter emitter, TriggerItem comparison, boolean passed) {
		if (comparison instanceof CondCompare) {
			emitter.loadConstant(comparison, CondCompare.class);
			emitter.loadEvent();
			emitter.pushInt(passed ? 1 : 0);
			emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(CondCompare.class), "skatalystContinuation",
				Type.getMethodDescriptor(ITEM, EVENT, Type.BOOLEAN_TYPE), false);
		} else if (passed) {
			emitter.loadConstant(comparison, SecConditional.class);
			emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(SecConditional.class), "skatalystEnterBranch",
				Type.getMethodDescriptor(ITEM), false);
		} else {
			emitter.loadActualNext(comparison);
		}
	}
}
