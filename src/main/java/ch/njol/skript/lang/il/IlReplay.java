package ch.njol.skript.lang.il;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionList;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.LiteralList;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.SyntaxElement;
import ch.njol.skript.lang.UnparsedLiteral;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.VariableString;
import ch.njol.skript.lang.function.ExprFunctionCall;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.parser.ParsingStack;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.log.ParseLogHandler;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.StringMode;
import ch.njol.util.Kleenean;
import com.google.common.collect.Iterables;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.common.function.FunctionReference;
import org.skriptlang.skript.registration.SyntaxInfo;

import java.util.List;
import java.util.regex.MatchResult;

/**
 * Turns an IL tree back into live Skript objects.
 * <p>
 * Recreates the exact same object graph by instantiating the recorded syntax classes
 * and running their normal {@code init} methods.
 * Any inconsistency (missing class, failed init, ...) results in {@code null}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class IlReplay {

	private IlReplay() { }

	/**
	 * Rebuilds the syntax element described by a recorded IL tree.
	 */
	static @Nullable SyntaxElement build(IlNode node, SkriptParser parser) {
		if (!(node instanceof IlNode.Element element))
			return failed();
		return buildElement(element, parser);
	}

	private static @Nullable SyntaxElement buildElement(IlNode.Element element, SkriptParser parser) {
		SyntaxInfo<?> info = IlManager.infoFor(element.className());
		int index = element.patternIndex();
		if (info == null || index < 0 || index >= info.patterns().size()) return failed();
		var pattern = SkriptParser.getSyntaxPattern(Iterables.get(info.patterns(), index));
		if (pattern.countTypes() != element.args().size()) return failed();

		ParserInstance context = ParserInstance.get();
		ParsingStack stack = context.getParsingStack();
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			Expression<?>[] expressions = new Expression<?>[element.args().size()];
			stack.push(new ParsingStack.Element(info, index));
			try {
				// TypePatternElement matches the rest of the pattern before parsing its own operand.
				for (int i = expressions.length - 1; i >= 0; i--) {
					if (!(element.args().get(i) instanceof IlNode.Arg.Value value)) continue;
					Expression<?> expression = buildExpression(value.node(), parser);
					if (expression == null) return failed();
					if (value.time() != 0 && (expression instanceof Literal<?>
							|| context.getHasDelayBefore() == Kleenean.TRUE || !expression.setTime(value.time()))) {
						return failed();
					}
					expressions[i] = expression;
				}
			} finally {
				stack.pop();
			}

			// Defaults and the selected element initialize with only their enclosing syntax on the stack.
			ParseResult result = new ParseResult(element.expr(), expressions);
			result.source = pattern;
			result.mark = element.mark();
			result.tags.addAll(element.tags());
			for (IlNode.Regex regex : element.regexes()) {
				result.regexes.add(new IlMatchResult(regex));
			}
			return parser.initializeSyntax(info, index, result, log);
		}
	}

	private static @Nullable Expression<?> buildExpression(IlNode node, SkriptParser parser) {
		if (node instanceof IlNode.Element element)
			return buildElement(element, parser) instanceof Expression<?> expression ? expression : null;
		if (node instanceof IlNode.Variable(String name, List<String> typeNames)) {
			Class<?>[] types = resolveClasses(typeNames);
			if (types == null)
				return failed();
			return Variable.newInstance(name, (Class[]) types);
		}
		if (node instanceof IlNode.VarString(String original, String mode1)) {
			StringMode mode;
			try {
				mode = StringMode.valueOf(mode1);
			} catch (IllegalArgumentException e) {
				return failed();
			}
			return VariableString.newInstance(original, mode);
		}
		if (node instanceof IlNode.Unparsed(String text))
			return new UnparsedLiteral(text);
		if (node instanceof IlNode.Literal(String text, String attemptedTypeName, int parseContext)) {
			Class<?> type = resolveClass(attemptedTypeName);
			if (type == null)
				return failed();
			Object value = Classes.parse(text, (Class) type, parseContext(parseContext));
			if (value == null)
				return failed();
			return new SimpleLiteral<>(value, false, new UnparsedLiteral(text));
		}
		if (node instanceof IlNode.SpecifiedLiteral specifiedLiteral) {
			Expression<?> literal = buildSpecifiedLiteral(specifiedLiteral);
			if (literal == null)
				return failed();
			return literal;
		}
		if (node instanceof IlNode.FunctionCall(String text, List<String> typeNames, int flags, int parseContext)) {
			FunctionReference<?> reference = new SkriptParser(text,
				flags, parseContext(parseContext)).parseFunctionReference();
			if (reference == null)
				return failed();
			Class<?>[] types = resolveClasses(typeNames);
			if (types == null)
				return failed();
			return new ExprFunctionCall<>(reference, (Class[]) types);
		}
		if (node instanceof IlNode.ListExpr listExpr)
			return buildList(listExpr, parser);
		if (node instanceof IlNode.Converted(IlNode child1, List<String> typeNames)) {
			Expression<?> child = buildExpression(child1, parser);
			if (child == null)
				return failed();
			Class<?>[] types = resolveClasses(typeNames);
			if (types == null)
				return failed();
			return ((Expression) child).getConvertedExpression(types);
		}
		return failed();
	}

	private static @Nullable Expression<?> buildSpecifiedLiteral(IlNode.SpecifiedLiteral node) {
		ParseContext context = parseContext(node.parseContext());
		ClassInfo<?> classInfo = Classes.parse(node.classInfoName(), ClassInfo.class, context);
		if (classInfo == null)
			return failed();
		Parser<?> parser = classInfo.getParser();
		if (parser == null || !parser.canParse(context))
			return failed();
		Object value = parser.parse(node.literalText(), context);
		if (value == null)
			return failed();
		return new SimpleLiteral<>(value, false, new UnparsedLiteral(node.literalText()));
	}

	private static @Nullable Expression<?> buildList(IlNode.ListExpr listExpr, SkriptParser parser) {
		Expression<?>[] expressions = new Expression[listExpr.items().size()];
		Class<?>[] returnTypes = new Class[expressions.length];
		for (int i = 0; i < expressions.length; i++) {
			Expression<?> expression = buildExpression(listExpr.items().get(i), parser);
			if (expression == null)
				return failed();
			expressions[i] = expression;
			returnTypes[i] = expression.getReturnType();
		}
		Class<?> superType = Classes.getSuperClassInfo(returnTypes).getC();
		if (listExpr.literalList()) {
			Literal<?>[] literals = new Literal[expressions.length];
			for (int i = 0; i < expressions.length; i++) {
				if (!(expressions[i] instanceof Literal<?> literal))
					return failed();
				literals[i] = literal;
			}
			return new LiteralList(literals, (Class) superType, returnTypes, listExpr.and());
		}
		return new ExpressionList(expressions, (Class) superType, returnTypes, listExpr.and());
	}

	private static ParseContext parseContext(int ordinal) {
		ParseContext[] values = ParseContext.values();
		if (ordinal < 0 || ordinal >= values.length)
			return ParseContext.DEFAULT;
		return values[ordinal];
	}

	private static @Nullable Class<?> resolveClass(String className) {
		try {
			return Class.forName(className, false, IlReplay.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return failed();
		}
	}

	private static Class<?> @Nullable [] resolveClasses(List<@Nullable String> classNames) {
		Class<?>[] classes = new Class[classNames.size()];
		for (int i = 0; i < classes.length; i++) {
			String className = classNames.get(i);
			if (className == null)
				continue;
			Class<?> type = resolveClass(className);
			if (type == null)
				return failed();
			classes[i] = type;
		}
		return classes;
	}

	private static <T> T failed() {
		StackTraceElement caller = new Throwable().getStackTrace()[1];
		IlManager.debug("IL replay failed at " + caller.getMethodName() + ":" + caller.getLineNumber());
		return null;
	}
	/**
	 * A synthetic regex match result reconstructed from stored data.
	 */
	private record IlMatchResult(IlNode.Regex regex) implements MatchResult {

		@Override
		public int groupCount() {
			return regex.groups().size() - 1;
		}

		@Override
		public String group() {
			return group(0);
		}

		@Override
		public String group(int group) {
			if (group < 0 || group >= regex.groups().size())
				throw new IndexOutOfBoundsException("No group " + group);
			return regex.groups().get(group);
		}

		@Override
		public int start() {
			return start(0);
		}

		@Override
		public int start(int group) {
			if (group < 0 || group >= regex.starts().size())
				throw new IndexOutOfBoundsException("No group " + group);
			return regex.starts().get(group);
		}

		@Override
		public int end() {
			return end(0);
		}

		@Override
		public int end(int group) {
			if (group < 0 || group >= regex.ends().size())
				throw new IndexOutOfBoundsException("No group " + group);
			return regex.ends().get(group);
		}

	}

}
