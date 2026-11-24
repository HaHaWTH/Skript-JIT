package io.gunmu.skatalyst.addon.compat;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.expressions.base.PropertyExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.LiteralString;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.SyntaxElement;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.VariableString;
import ch.njol.skript.lang.simplification.SimplifiedLiteral;
import ch.njol.skript.lang.util.SimpleLiteral;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class SkBeeProfile {

	private static final String NBT = "com.shanebeestudios.skbee.elements.nbt.expressions.ExprNbtCompound";

	private SkBeeProfile() {
	}

	public static @Nullable Class<? extends Expression<?>> syntax(Plugin plugin) {
		try {
			ClassLoader loader = plugin.getClass().getClassLoader();
			Class<?> type = Class.forName(NBT, false, loader);
			if (type.getClassLoader() != loader || !PropertyExpression.class.isAssignableFrom(type)) {
				return null;
			}
			@SuppressWarnings("unchecked")
			Class<? extends Expression<?>> result = (Class<? extends Expression<?>>) type.asSubclass(Expression.class);
			return result;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return null;
		}
	}

	public static boolean isGetter(TriggerItem item, SyntaxElement expression, int pattern, ParseResult parse) {
		if (pattern != 0 || !parse.tags.isEmpty() || parse.exprs.length != 1 || item.getClass() != EffChange.class) {
			return false;
		}
		EffChange.SkatalystChange change = ((EffChange) item).skatalystChange();
		if (change.mode() != ChangeMode.SET || change.source() != expression || !(change.target() instanceof Variable<?> variable)
			|| !variable.isLocal() || variable.isList() || !variable.getName().isSimple()) {
			return false;
		}
		// PropertyExpression.simplify may replace the parsed input with an evaluated literal.
		Expression<?> input = ((PropertyExpression<?, ?>) expression).getExpr();
		if (input == null) {
			return false;
		}
		Class<?> type = input.getClass();
		return (type == LiteralString.class || type == SimpleLiteral.class || type == SimplifiedLiteral.class
			|| (type == VariableString.class && ((VariableString) input).isSimple()))
			&& input.isSingle() && input.getReturnType() == String.class;
	}
}
