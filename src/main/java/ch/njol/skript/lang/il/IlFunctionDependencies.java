package ch.njol.skript.lang.il;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.VariableString;
import ch.njol.skript.lang.function.FunctionRegistry;
import ch.njol.skript.lang.function.Signature;
import ch.njol.skript.lang.util.SimpleLiteral;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.common.function.Parameter;
import org.skriptlang.skript.common.function.Parameter.Modifier;
import org.skriptlang.skript.common.function.Parameter.Modifier.RangedModifier;
import org.skriptlang.skript.common.function.ScriptParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Stable evidence of the candidates visible to one function lookup. */
@ApiStatus.Internal
public final class IlFunctionDependencies {

	private IlFunctionDependencies() {
	}

	public record Lookup(@Nullable String namespace, String name, String fingerprint) {
	}

	public static @Nullable Lookup describe(@Nullable String namespace, String name, Set<Signature<?>> signatures) {
		List<String> candidates = new ArrayList<>(signatures.size());
		for (Signature<?> signature : signatures) {
			String fingerprint = fingerprint(signature);
			if (fingerprint == null) return null;
			candidates.add(fingerprint);
		}
		candidates.sort(String::compareTo);
		Hasher hash = Hashing.sha256().newHasher().putInt(candidates.size());
		for (String candidate : candidates) {
			putString(hash, candidate);
		}
		return new Lookup(namespace, name, hash.hash().toString());
	}

	public static boolean matches(Lookup lookup) {
		Set<Signature<?>> signatures = FunctionRegistry.getRegistry().getSignatures(lookup.namespace(), lookup.name());
		return lookup.equals(describe(lookup.namespace(), lookup.name(), signatures));
	}

	private static @Nullable String fingerprint(Signature<?> signature) {
		if (signature.getClass() != Signature.class || signature.contract() != null) return null;

		Hasher hash = Hashing.sha256().newHasher();
		String provider = signature.namespace();
		boolean defaultFunction = false;
		hash.putBoolean(provider != null);
		if (provider == null) {
			var origin = FunctionRegistry.getRegistry().nativeOrigin(signature);
			if (origin == null) return null;
			putString(hash, origin.addonName());
			putString(hash, origin.implementation());
			defaultFunction = origin.defaultFunction();
			hash.putBoolean(defaultFunction);
		} else {
			putString(hash, provider);
		}
		putString(hash, signature.getName());
		hash.putBoolean(signature.isLocal()).putBoolean(signature.isSingle());
		if (!putType(hash, signature.returnType())) return null;

		Parameter<?>[] parameters = signature.parameters().all();
		var named = signature.parameters().sequencedMap();
		if (named.size() != parameters.length) return null;
		hash.putInt(parameters.length);
		int index = 0;
		for (var entry : named.entrySet()) {
			Parameter<?> parameter = entry.getValue();
			if (parameter != parameters[index++] || !entry.getKey().equals(parameter.name())) return null;
			if (!putParameter(hash, parameter, defaultFunction)) return null;
		}
		return hash.hash().toString();
	}

	private static boolean putParameter(Hasher hash, Parameter<?> parameter, boolean defaultFunction) {
		putString(hash, parameter.name());
		if (!putType(hash, parameter.type())) return false;
		hash.putBoolean(parameter.isSingle());

		RangedModifier<?> range = null;
		boolean optional = false;
		boolean keyed = false;
		for (Modifier modifier : parameter.modifiers()) {
			if (modifier == Modifier.OPTIONAL) {
				optional = true;
			} else if (modifier == Modifier.KEYED) {
				keyed = true;
			} else if (modifier instanceof RangedModifier<?> bounds) {
				range = bounds;
			} else {
				return false;
			}
		}
		hash.putBoolean(optional).putBoolean(keyed).putBoolean(range != null);
		if (range != null && (!putValue(hash, range.getMin()) || !putValue(hash, range.getMax()))) return false;

		Expression<?> defaultValue;
		if (parameter instanceof ScriptParameter<?> script) {
			defaultValue = script.defaultValue();
		} else if (parameter instanceof ch.njol.skript.lang.function.Parameter<?> legacy) {
			defaultValue = legacy.getDefaultExpression();
		} else if (defaultFunction) {
			// The sealed DefaultFunction builder creates parameters without default expressions.
			defaultValue = null;
		} else {
			return false;
		}
		return putDefault(hash, defaultValue);
	}

	private static boolean putDefault(Hasher hash, @Nullable Expression<?> expression) {
		if (expression == null) {
			hash.putByte((byte) 0);
			return true;
		}
		if (expression.getClass() == VariableString.class && ((VariableString) expression).isSimple()) {
			VariableString string = (VariableString) expression;
			hash.putByte((byte) 1);
			putString(hash, string.getMode().name());
			putString(hash, string.toString(null));
			return true;
		}
		if (expression.getClass() != SimpleLiteral.class) return false;
		SimpleLiteral<?> literal = (SimpleLiteral<?>) expression;
		hash.putByte((byte) 2);
		if (!putType(hash, literal.getReturnType())) return false;
		Object[] values = literal.getArray();
		hash.putBoolean(literal.getAnd()).putBoolean(literal.isDefault()).putInt(values.length);
		for (Object value : values) {
			if (!putValue(hash, value)) return false;
		}
		return true;
	}

	private static boolean putValue(Hasher hash, Object value) {
		putString(hash, value.getClass().getName());
		switch (value) {
			case String string -> putString(hash, string);
			case Boolean bool -> hash.putBoolean(bool);
			case Character character -> hash.putChar(character);
			case Byte number -> hash.putByte(number);
			case Short number -> hash.putShort(number);
			case Integer number -> hash.putInt(number);
			case Long number -> hash.putLong(number);
			case Float number -> hash.putInt(Float.floatToRawIntBits(number));
			case Double number -> hash.putLong(Double.doubleToRawLongBits(number));
			default -> { return false; }
		}
		return true;
	}

	private static boolean putType(Hasher hash, @Nullable Class<?> type) {
		if (type == null) {
			hash.putByte((byte) 0);
			return true;
		}
		ClassLoader loader = type.getClassLoader();
		if (loader == null) {
			hash.putByte((byte) 1);
		} else if (loader == IlFunctionDependencies.class.getClassLoader()) {
			hash.putByte((byte) 2);
		} else if (loader == Bukkit.class.getClassLoader()) {
			hash.putByte((byte) 3);
		} else {
			return false;
		}
		putString(hash, type.getName());
		return true;
	}

	private static void putString(Hasher hash, @Nullable String value) {
		hash.putInt(value == null ? -1 : value.length());
		if (value != null) hash.putUnencodedChars(value);
	}
}
