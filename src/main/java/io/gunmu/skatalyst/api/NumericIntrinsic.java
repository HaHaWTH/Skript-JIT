package io.gunmu.skatalyst.api;

import ch.njol.skript.lang.Expression;

import java.lang.invoke.MethodHandle;
import java.util.List;

/**
 * A direct numeric implementation of one parsed expression. Operands are evaluated in list order.
 * The handle must be total, deterministic and replay-safe for the admitted operands, with the same primitive result as
 * the expression, including signed zero and NaN. The original expression still handles missing or differently typed
 * inputs. Only an expression whose observable result can be represented by boxing the primitive result is eligible.
 */
public record NumericIntrinsic(MethodHandle handle, List<Expression<?>> operands) {

	public NumericIntrinsic {
		operands = List.copyOf(operands);
		var type = handle.type();
		if (operands.size() > 3 || operands.size() != type.parameterCount() || !supported(type.returnType())
			|| type.parameterList().stream().anyMatch(parameter -> !supported(parameter))) {
			throw new IllegalArgumentException("Numeric intrinsic requires 0–3 exact long or double parameters and a long or double result");
		}
	}

	private static boolean supported(Class<?> type) {
		return type == long.class || type == double.class;
	}
}
