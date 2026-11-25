package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.sections.SecWhile;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.addon.ParsedBinding.NumericCall;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.IF_ACMPNE;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

@ApiStatus.Internal
public final class CoreOptimizations {

	private CoreOptimizations() {
	}

	public static Map<TriggerItem, BytecodeCompiler.Optimization> plan(TriggerSection trigger) {
		Map<TriggerItem, BytecodeCompiler.Optimization> result = new IdentityHashMap<>();
		collect(trigger, result);
		return Collections.unmodifiableMap(result);
	}

	private static void collect(TriggerSection section, Map<TriggerItem, BytecodeCompiler.Optimization> result) {
		ReferenceSlotOptimization.collect(section.skatalystItems(), result);
		ScalarArithmeticOptimization.collect(section.skatalystItems(), result);
		for (TriggerItem item : section.skatalystItems()) {
			BytecodeCompiler.Optimization optimization = null;
			if (item.getClass() == SecLoop.class) {
				optimization = NumericLoopOptimization.plan((SecLoop) item);
				if (optimization == null) {
					optimization = CollectionLoopOptimization.plan((SecLoop) item);
				}
			} else if (item.getClass() == SecWhile.class) {
				optimization = NumericLoopOptimization.plan((SecWhile) item);
			} else if (item.getClass() == EffChange.class) {
				optimization = StringConcatenationOptimization.plan((EffChange) item);
			}
			if (optimization != null) {
				result.put(item, optimization);
			}
			if (item instanceof TriggerSection child) {
				collect(child, result);
			}
		}
	}

	static @Nullable String scalarLocal(Expression<?> expression) {
		if (expression.getClass() != Variable.class) return null;
		Variable<?> variable = (Variable<?>) expression;
		if (!variable.isLocal() || variable.isList() || !variable.getName().isSimple()) {
			return null;
		}
		String name = variable.getName().toString(null);
		// A slot has the same identity under either variable casing mode
		return !name.contains(Variable.SEPARATOR) && name.equals(name.toLowerCase(Locale.ENGLISH)) ? name : null;
	}

	static boolean numericCandidate(TriggerItem item) {
		return !item.skatalystExposesLocals() || hasNumericIntrinsics(item);
	}

	static boolean hasNumericIntrinsics(TriggerItem item) {
		var binding = item.skatalystBinding();
		return binding != null && binding.hasNumericIntrinsics();
	}

	static boolean inferNumericOperands(TriggerItem item, Map<String, NumericKind> kinds) {
		var binding = item.skatalystBinding();
		if (binding == null) {
			return true;
		}
		for (NumericCall call : binding.numericIntrinsics()) {
			var intrinsic = call.intrinsic();
			if (intrinsic == null) {
				continue;
			}
			var type = intrinsic.handle().type();
			for (int index = 0; index < intrinsic.operands().size(); index++) {
				String name = scalarLocal(intrinsic.operands().get(index));
				if (name != null) {
					NumericKind kind = type.parameterType(index) == long.class ? NumericKind.INTEGRAL : NumericKind.DECIMAL;
					NumericKind previous = kinds.putIfAbsent(name, kind);
					if (previous != null && previous != kind) {
						return false;
					}
				}
			}
		}
		return true;
	}

	static boolean retainNumericIntrinsics(TriggerItem item, Set<NumericCall> calls, NumericExpression first,
			@Nullable NumericExpression second) {
		var binding = item.skatalystBinding();
		if (binding == null || !binding.hasNumericIntrinsics()) {
			return !item.skatalystExposesLocals();
		}
		Set<NumericCall> used = new LinkedHashSet<>();
		first.collectIntrinsics(used);
		if (second != null) {
			second.collectIntrinsics(used);
		}
		if (item.skatalystExposesLocals() && (used.isEmpty() || !binding.coversExternalSyntax(used))) {
			return false;
		}
		calls.addAll(used);
		return true;
	}

	static Set<NumericCall> numericIntrinsics(List<NumericRegion> plans) {
		Set<NumericCall> calls = new LinkedHashSet<>();
		for (NumericRegion plan : plans) {
			calls.addAll(plan.intrinsics);
		}
		return calls;
	}

	static void guardNumericIntrinsics(BytecodeCompiler.Emitter emitter, Set<NumericCall> calls, Label inactive) {
		for (NumericCall call : calls) {
			BooleanSupplier active = call::isActive;
			emitter.dependOn(active);
			emitter.loadConstant(active, BooleanSupplier.class);
			emitter.method().visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(BooleanSupplier.class), "getAsBoolean", "()Z", true);
			emitter.method().visitJumpInsn(IFEQ, inactive);
		}
	}

	static void guardSuccessor(BytecodeCompiler.Emitter emitter, TriggerItem node, TriggerItem expected, Label mismatch) {
		emitter.loadActualNext(node);
		emitter.loadConstant(expected, TriggerItem.class);
		emitter.method().visitJumpInsn(IF_ACMPNE, mismatch);
	}

	static void guardScalarWrites(BytecodeCompiler.Emitter emitter, List<String> names, Label mismatch) {
		emitter.loadEvent();
		emitter.loadScalarWriteNames(names);
		emitter.method().visitMethodInsn(INVOKESTATIC, Type.getInternalName(Variables.class), "skatalystCanBatchScalarWrites",
			Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(Event.class), Type.getType(List.class)), false);
		emitter.method().visitJumpInsn(IFEQ, mismatch);
	}
}
