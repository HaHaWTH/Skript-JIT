package io.gunmu.skatalyst.compiler;

import ch.njol.skript.lang.util.ConvertedExpression;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.common.function.FunctionArguments;

import java.util.List;

/** A fixed-arity scalar entry point. A null result requests ordinary function evaluation. */
@ApiStatus.Internal
public record DirectFunction(List<String> parameters, Body body, @Nullable ConvertedExpression<?, ?> conversion) {

	public static final int MAX_PARAMETERS = 4;

	public @Nullable Object invoke(FunctionArguments arguments) {
		return body.invoke(argument(arguments, 0), argument(arguments, 1), argument(arguments, 2), argument(arguments, 3));
	}

	public @Nullable Object convertResult(Object value) {
		return conversion == null ? value : conversion.skatalystConvertScalar(value);
	}

	private @Nullable Object argument(FunctionArguments arguments, int index) {
		return index < parameters.size() ? arguments.get(parameters.get(index)) : null;
	}

	public interface Body {
		@Nullable Object invoke(@Nullable Object first, @Nullable Object second, @Nullable Object third, @Nullable Object fourth);
	}
}
