package ch.njol.skript.lang.il;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.lang.DefaultExpression;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionList;
import ch.njol.skript.lang.LiteralList;
import ch.njol.skript.lang.SkriptParser.ExprInfo;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.patterns.TypePatternElement;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;

/**
 * Converts live parse results into IL nodes while a {@link IlManager.Session} is active.
 * <p>
 * The recorder only has to build the data model.
 */
final class IlRecorder {

	private IlRecorder() {
	}

	/**
	 * Registers a leaf expression created by the parser, so the enclosing element can refer to it.
	 */
	static void register(Object object, IlNode node) {
		IlManager.Session session = IlManager.currentSession();
		if (session != null && !session.uncacheable)
			session.nodes.put(object, node);
	}

	static @Nullable IlNode nodeFor(Object object) {
		IlManager.Session session = IlManager.currentSession();
		return session == null ? null : session.nodes.get(object);
	}

	/**
	 * Builds the IL node for a successfully initialized syntax element.
	 *
	 * @return the node, or {@code null} if this element cannot be represented.
	 */
	static @Nullable IlNode buildElementNode(IlRegistration registration, int patternIndex, ParseResult parseResult) {
		if (parseResult.source == null)
			return null;
		List<TypePatternElement> slotTypes = parseResult.source.getElements(TypePatternElement.class);
		if (slotTypes.size() != parseResult.exprs.length)
			return null;

		List<IlNode.Arg> args = new ArrayList<>(parseResult.exprs.length);
		for (int i = 0; i < parseResult.exprs.length; i++) {
			Expression<?> expr = parseResult.exprs[i];
			TypePatternElement slotType = slotTypes.get(i);
			if (expr == null) {
				args.add(new IlNode.Arg.Null());
			} else {
				IlNode node = nodeFor(expr);
				if (node == null)
					node = classify(expr);
				if (node != null) {
					args.add(new IlNode.Arg.Value(node, slotType.getTime()));
				} else if (expr instanceof DefaultExpression<?>) {
					args.add(new IlNode.Arg.Default(toIlExprInfo(slotType.getExprInfo())));
				} else {
					return null;
				}
			}
		}

		List<IlNode.Regex> regexes = new ArrayList<>(parseResult.regexes.size());
		for (MatchResult matchResult : parseResult.regexes) {
			int groupCount = matchResult.groupCount();
			List<String> groups = new ArrayList<>(groupCount + 1);
			List<Integer> starts = new ArrayList<>(groupCount + 1);
			List<Integer> ends = new ArrayList<>(groupCount + 1);
			for (int group = 0; group <= groupCount; group++) {
				groups.add(String.valueOf(matchResult.group(group)));
				starts.add(safePosition(matchResult, group, true));
				ends.add(safePosition(matchResult, group, false));
			}
			regexes.add(new IlNode.Regex(groups, starts, ends));
		}

		return new IlNode.Element(
			registration,
			patternIndex,
			parseResult.expr,
			parseResult.mark,
			new ArrayList<>(parseResult.tags),
			regexes,
			args
		);
	}

	private static int safePosition(MatchResult matchResult, int group, boolean start) {
		try {
			return start ? matchResult.start(group) : matchResult.end(group);
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * Fallback classification for expressions whose creation site is not instrumented.
	 * Only used for objects that can be recognized without knowing their source text.
	 */
	private static @Nullable IlNode classify(Object object) {
		if (object instanceof ExpressionList<?> list) {
			Expression<?>[] expressions = list.getExpressions();
			List<IlNode> items = new ArrayList<>(expressions.length);
			for (Expression<?> expression : expressions) {
				IlNode node = nodeFor(expression);
				if (node == null)
					return null;
				items.add(node);
			}
			return new IlNode.ListExpr(items, object instanceof LiteralList<?>, list.getAnd());
		}
		return null;
	}

	static IlNode.IlExprInfo toIlExprInfo(ExprInfo info) {
		List<String> classNames = new ArrayList<>(info.classes.length);
		List<Boolean> isPlural = new ArrayList<>(info.isPlural.length);
		for (int i = 0; i < info.classes.length; i++) {
			ClassInfo<?> classInfo = info.classes[i];
			classNames.add(classInfo == null ? null : classInfo.getCodeName());
			isPlural.add(info.isPlural[i]);
		}
		return new IlNode.IlExprInfo(classNames, isPlural, info.isOptional, info.flagMask, info.time);
	}

}
