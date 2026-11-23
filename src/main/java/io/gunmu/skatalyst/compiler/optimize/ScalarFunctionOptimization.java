package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.effects.EffReturn;
import ch.njol.skript.lang.KeyProviderExpression;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.util.ConvertedExpression;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.DirectFunction;
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
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Compiles a pure scalar return from a script function's positional arguments.
 */
@ApiStatus.Internal
public final class ScalarFunctionOptimization {

	private static final int MAX_OPERATIONS = 24;

	private ScalarFunctionOptimization() {
	}

	public static @Nullable DirectFunction compile(Trigger trigger) {
		var signature = trigger.skatalystSite().functionSignature();
		if (signature == null || signature.returnType() == null || signature.returnType().isArray()
			|| trigger.skatalystItems().size() != 1 || trigger.getActualNext() != null
			|| trigger.skatalystItems().getFirst().getClass() != EffReturn.class)
			return null;

		EffReturn statement = (EffReturn) trigger.skatalystItems().getFirst();
		var expression = statement.skatalystScalarValue();
		if (statement.skatalystExposesLocals() || expression == null || KeyProviderExpression.canReturnKeys(expression))
			return null;
		var conversion = expression.getClass() == ConvertedExpression.class ? (ConvertedExpression<?, ?>) expression : null;
		var input = conversion == null ? expression : conversion.skatalystInput();

		Parameter<?>[] parameters = signature.parameters().all();
		if (parameters.length > DirectFunction.MAX_PARAMETERS)
			return null;
		List<String> names = new ArrayList<>(parameters.length);
		for (Parameter<?> parameter : parameters) {
			if (!parameter.isSingle() || !parameter.modifiers().isEmpty())
				return null;
			names.add(parameter.name());
		}

		List<Variant> variants = new ArrayList<>();
		for (NumericKind kind : NumericKind.values()) {
			if (!kind.canStoreDirectly())
				continue;
			NumericExpression value = NumericExpression.parse(input, kind, null);
			if (value == null || value.nodes() > MAX_OPERATIONS || conversion == null
				&& value.possibleTypes().stream().anyMatch(type -> !signature.returnType().isAssignableFrom(type)))
				continue;
			Map<String, Boolean> reads = new LinkedHashMap<>();
			value.collectNames(reads);
			if (!names.containsAll(reads.keySet()))
				continue;
			boolean exact = true;
			for (Parameter<?> parameter : parameters)
				exact &= parameter.type().isAssignableFrom(kind.boxed);
			if (exact)
				variants.add(new Variant(kind, value));
			if (names.isEmpty() && exact)
				break;
		}
		return variants.isEmpty() ? null
			: BytecodeCompiler.compileFunction(List.copyOf(names), conversion, emitter -> emit(emitter, names, variants));
	}

	private static void emit(BytecodeCompiler.Emitter emitter, List<String> names, List<Variant> variants) {
		MethodVisitor method = emitter.method();
		Label fallback = new Label();
		for (Variant variant : variants) {
			Label miss = new Label();
			NumericKind kind = variant.inputKind();
			String boxed = Type.getInternalName(kind.boxed);
			Map<String, Slot> slots = new LinkedHashMap<>();
			for (String name : names) {
				int argument = names.indexOf(name) + 1;
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

	private record Variant(NumericKind inputKind, NumericExpression value) {
	}
}
