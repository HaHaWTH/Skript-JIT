package io.gunmu.skatalyst.compiler;

import ch.njol.skript.lang.util.ConvertedExpression;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.common.function.FunctionArguments;

import java.util.List;
import java.util.function.BooleanSupplier;

@ApiStatus.Internal
public final class DirectFunction {

	public static final int MAX_PARAMETERS = 4;

	private final List<String> parameters;
	private final @Nullable ConvertedExpression<?, ?> conversion;
	private final List<BooleanSupplier> dependencies;
	private final @Nullable Body coreBody;
	private volatile @Nullable Body addonBody;

	DirectFunction(List<String> parameters, Body body, @Nullable ConvertedExpression<?, ?> conversion,
				   List<BooleanSupplier> dependencies) {
		this.parameters = parameters;
		this.conversion = conversion;
		this.dependencies = dependencies;
		coreBody = dependencies.isEmpty() ? body : null;
		addonBody = dependencies.isEmpty() ? null : body;
	}

	public @Nullable Body body() {
		return coreBody == null ? addonBody : coreBody;
	}

	boolean dependenciesActive() {
		for (BooleanSupplier dependency : dependencies) {
			if (!dependency.getAsBoolean()) {
				return false;
			}
		}
		return true;
	}

	void retire() {
		addonBody = null;
	}

	public @Nullable Object invoke(FunctionArguments arguments) {
		Body body = coreBody;
		if (body == null) {
			body = addonBody;
		}
		return body == null ? null : body.invoke(argument(arguments, 0), argument(arguments, 1),
			argument(arguments, 2), argument(arguments, 3));
	}

	public @Nullable Object convertResult(Object value) {
		return conversion == null ? value : conversion.skatalystConvertScalar(value);
	}

	private @Nullable Object argument(FunctionArguments arguments, int index) {
		return index < parameters.size() ? arguments.get(parameters.get(index)) : null;
	}

	public interface Body { // 棍母 is using
		@Nullable Object invoke(@Nullable Object first, @Nullable Object second, @Nullable Object third, @Nullable Object fourth);
	}
}
