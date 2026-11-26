package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.effects.EffReturn;
import ch.njol.skript.lang.KeyProviderExpression;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.util.ConvertedExpression;
import io.gunmu.skatalyst.addon.ParsedBinding.NumericCall;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.DirectFunction;
import io.gunmu.skatalyst.compiler.debug.ClassExporter;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.Slot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.common.function.Parameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Compiles a pure scalar return from a script function's positional arguments.
 */
@ApiStatus.Internal
public final class ScalarFunctionOptimization {

	private static final int MAX_OPERATIONS = 24;

	private ScalarFunctionOptimization() {
	}

	public static @Nullable DirectFunction compile(Trigger trigger, ClassExporter.@Nullable Compilation export) {
		var signature = trigger.skatalystSite().functionSignature();
		if (signature == null || signature.returnType() == null || signature.returnType().isArray()
			|| trigger.skatalystItems().size() != 1 || trigger.getActualNext() != null
			|| trigger.skatalystItems().getFirst().getClass() != EffReturn.class) {
			return null;
		}

		EffReturn statement = (EffReturn) trigger.skatalystItems().getFirst();
		var expression = statement.skatalystScalarValue();
		if (!CoreOptimizations.numericCandidate(statement) || expression == null || KeyProviderExpression.canReturnKeys(expression)) {
			return null;
		}
		var conversion = expression.getClass() == ConvertedExpression.class ? (ConvertedExpression<?, ?>) expression : null;
		var input = conversion == null ? expression : conversion.skatalystInput();

		Parameter<?>[] parameters = signature.parameters().all();
		if (parameters.length > DirectFunction.MAX_PARAMETERS) {
			return null;
		}
		List<String> names = new ArrayList<>(parameters.length);
		for (Parameter<?> parameter : parameters) {
			if (!parameter.isSingle() || !parameter.modifiers().isEmpty()) {
				return null;
			}
			names.add(parameter.name());
		}

		Map<String, NumericKind> fixedKinds = new LinkedHashMap<>();
		if (!CoreOptimizations.inferNumericOperands(statement, fixedKinds)) {
			return null;
		}
		List<Variant> variants = new ArrayList<>();
		Set<NumericCall> intrinsics = new LinkedHashSet<>();
		for (NumericKind kind : NumericKind.values()) {
			List<NumericKind> kinds = names.stream().map(name -> fixedKinds.getOrDefault(name, kind)).toList();
			if (kinds.stream().anyMatch(inputKind -> !inputKind.canStoreDirectly())
					|| variants.stream().anyMatch(variant -> variant.inputKinds().equals(kinds))) {
				continue;
			}
			NumericExpression value = NumericExpression.parse(input, name -> fixedKinds.getOrDefault(name, kind), null,
				statement.skatalystBinding());
			if (value == null || value.nodes() > MAX_OPERATIONS || conversion == null
				&& value.possibleTypes().stream().anyMatch(type -> !signature.returnType().isAssignableFrom(type))) {
				continue;
			}
			Map<String, Boolean> reads = new LinkedHashMap<>();
			value.collectNames(reads);
			if (!names.containsAll(reads.keySet())) {
				continue;
			}
			boolean exact = true;
			for (int index = 0; index < parameters.length; index++) {
				exact &= parameters[index].type().isAssignableFrom(kinds.get(index).boxed);
			}
			if (exact && CoreOptimizations.retainNumericIntrinsics(statement, intrinsics, value, null)) {
				variants.add(new Variant(kinds, value));
				if (names.isEmpty()) {
					break;
				}
			}
		}
		return variants.isEmpty() ? null
			: BytecodeCompiler.compileFunction(List.copyOf(names), conversion, emitter -> emit(emitter, names, variants, intrinsics), export);
	}

	private static void emit(BytecodeCompiler.Emitter emitter, List<String> names, List<Variant> variants, Set<NumericCall> intrinsics) {
		MethodVisitor method = emitter.method();
		Label fallback = new Label();
		CoreOptimizations.guardNumericIntrinsics(emitter, intrinsics, fallback);
		for (Variant variant : variants) {
			Label miss = new Label();
			Map<String, Slot> slots = new LinkedHashMap<>();
			for (int index = 0; index < names.size(); index++) {
				String name = names.get(index);
				int argument = index + 1;
				NumericKind kind = variant.inputKinds().get(index);
				String boxed = Type.getInternalName(kind.boxed);
				Slot slot = new Slot(emitter, kind);
				slots.put(name, slot);
				method.visitVarInsn(ALOAD, argument);
				method.visitTypeInsn(INSTANCEOF, boxed);
				method.visitJumpInsn(IFEQ, miss);
				method.visitVarInsn(ALOAD, argument);
				method.visitTypeInsn(CHECKCAST, boxed);
				method.visitMethodInsn(INVOKEVIRTUAL, boxed, kind == NumericKind.INTEGRAL ? "longValue" : "doubleValue",
					Type.getMethodDescriptor(kind.type), false);
				method.visitVarInsn(kind.storeOpcode(), slot.value());
			}
			if (variant.value() instanceof NumericExpression.Read read) {
				method.visitVarInsn(ALOAD, names.indexOf(read.name()) + 1);
			} else if (variant.value() instanceof NumericExpression.Constant(Number value)) {
				emitter.loadConstant(value, Object.class);
			} else {
				variant.value().emit(emitter, slots, -1, fallback);
				NumericKind result = variant.value().kind();
				method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(result.boxed), "valueOf",
					Type.getMethodDescriptor(Type.getType(result.boxed), result.type), false);
			}
			method.visitInsn(ARETURN);
			method.visitLabel(miss);
		}
		method.visitLabel(fallback);
		method.visitInsn(ACONST_NULL);
		method.visitInsn(ARETURN);
	}

	private record Variant(List<NumericKind> inputKinds, NumericExpression value) {
	}
}
