package ch.njol.skript.lang.il;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The IL representation of a parsed Skript syntax element.
 * <p>
 * An IL node only contains the information that is required to reconstruct the
 * object graph by replaying constructor logic.
 */
public sealed interface IlNode {

	/**
	 * A parsed syntax element (an {@link ch.njol.skript.lang.SyntaxElement}) produced by a registered
	 * {@link org.skriptlang.skript.registration.SyntaxInfo}.
	 *
	 * @param className    the binary class name of the syntax element
	 * @param patternIndex the index of the matched pattern in the syntax info's pattern list
	 * @param expr         the (trimmed) input text this element was parsed from
	 * @param mark         the parse {@code mark} of the parse result
	 * @param tags         the parse tags of the parse result, in order
	 * @param regexes      the regex match results of the parse result, in order
	 * @param args         one argument per expression slot of the pattern
	 */
	record Element(
		String className,
		int patternIndex,
		String expr,
		int mark,
		List<String> tags,
		List<Regex> regexes,
		List<Arg> args
	) implements IlNode {
	}

	/**
	 * A serialized regex match result.
	 */
	record Regex(List<String> groups, List<Integer> starts, List<Integer> ends) {
	}

	/**
	 * One expression slot of a pattern.
	 */
	sealed interface Arg {

		/**
		 * The slot was optional and no expression was parsed for it.
		 */
		record Null() implements Arg {
		}

		/**
		 * The slot was filled with a default expression by the parser.
		 */
		record Default(IlExprInfo exprInfo) implements Arg {
		}

		/**
		 * The slot contains a parsed expression.
		 */
		record Value(IlNode node, int time) implements Arg {
		}

	}

	/**
	 * The serializable form of {@link ch.njol.skript.lang.SkriptParser.ExprInfo}.
	 * Class infos are referenced by class name and resolved through the class info registry.
	 */
	record IlExprInfo(
		List<String> classNames,
		List<Boolean> isPlural,
		boolean isOptional,
		int flagMask,
		int time
	) {
	}

	/**
	 * A variable, e.g. {@code {_x}} or {@code {some.global::*}}.
	 */
	record Variable(String name, List<String> typeNames) implements IlNode {
	}

	/**
	 * A string that may contain expressions, e.g. {@code "hello %player%"}.
	 */
	record VarString(String original, String mode) implements IlNode {
	}

	/**
	 * An unparsed literal, e.g. an item literal that a consumer will convert later.
	 */
	record Unparsed(String text) implements IlNode {
	}

	/**
	 * A literal parsed via {@code Classes.parse} into a {@code SimpleLiteral}.
	 */
	record Literal(String text, String attemptedTypeName, int parseContext) implements IlNode {
	}

	/**
	 * A literal parsed with an explicit class info specification, e.g. {@code black (wolf variant)}.
	 */
	record SpecifiedLiteral(String literalText, String classInfoName, int parseContext) implements IlNode {
	}

	/**
	 * A parsed function call expression (an {@code ExprFunctionCall}).
	 */
	record FunctionCall(String text, List<@Nullable String> typeNames, int flags, int parseContext) implements IlNode {
	}

	/**
	 * A parsed list of expressions ({@code ExpressionList} or {@code LiteralList}).
	 */
	record ListExpr(List<IlNode> items, boolean literalList, boolean and) implements IlNode {
	}

	/**
	 * An expression converted to another type through {@code getConvertedExpression}.
	 */
	record Converted(IlNode child, List<String> typeNames) implements IlNode {
	}

}
