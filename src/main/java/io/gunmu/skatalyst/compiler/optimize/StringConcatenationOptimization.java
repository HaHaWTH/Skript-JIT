package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.VariableString;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.StringMode;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

@ApiStatus.Internal
public final class StringConcatenationOptimization {

	private static final String EVENT = Type.getDescriptor(Event.class);
	private static final String EXPRESSION = Type.getDescriptor(Expression.class);
	private static final String OBJECT = Type.getDescriptor(Object.class);
	private static final String STRING = Type.getDescriptor(String.class);
	private static final String MODE = Type.getDescriptor(StringMode.class);
	private static final String OWNER = Type.getInternalName(StringConcatenationOptimization.class);
	private static final Handle CONCAT = new Handle(H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcat",
		"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
	private static final int MAX_CONCAT_PARTS = 32;

	private StringConcatenationOptimization() {
	}

	static @Nullable BytecodeCompiler.Optimization plan(EffChange effect) {
		var change = effect.skatalystChange();
		if (change.mode() != ChangeMode.SET || change.source() == null
			|| change.source().getClass() != VariableString.class) {
			return null;
		}
		VariableString source = (VariableString) change.source();
		if (source.isSimple() || source.getMode() == StringMode.VARIABLE_NAME) {
			return null;
		}
		String name = CoreOptimizations.scalarLocal(change.target());
		if (name == null) return null;
		List<Object> parts = source.skatalystParts();
		if (parts.size() > MAX_CONCAT_PARTS) return null;
		ClassInfo<String> stringClass = Classes.getExactClassInfo(String.class);
		return stringClass == null ? null : new Concatenation(effect, name, source.getMode(), parts, stringClass);
	}

	record Concatenation(EffChange effect, String target, StringMode mode, List<Object> parts, ClassInfo<String> stringClass)
			implements BytecodeCompiler.Optimization {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter) {
			/*
			 * Example with one interpolation.
			 * String text = prefix + formatStringPart(expression, event, mode) + suffix;
			 * Object value = stringClass.clone(text);
			 * Variables.setVariable(target, value, event, true);
			 * next = effect.getActualNext();
			 */
			MethodVisitor method = emitter.method();
			emitter.loadConstant(target, String.class);
			emitter.loadConstant(stringClass, ClassInfo.class);
			for (Object part : parts) {
				if (part instanceof String text) {
					emitter.loadConstant(text, String.class);
				} else {
					emitter.loadConstant(part, Expression.class);
					emitter.loadEvent();
					emitter.loadConstant(mode, StringMode.class);
					method.visitMethodInsn(INVOKESTATIC, OWNER, "formatStringPart",
						"(" + EXPRESSION + EVENT + MODE + ")" + STRING, false);
				}
			}
			method.visitInvokeDynamicInsn("concat", "(" + STRING.repeat(parts.size()) + ")" + STRING, CONCAT);
			method.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassInfo.class), "clone",
				"(" + OBJECT + ")" + OBJECT, false);
			emitter.loadEvent();
			method.visitInsn(ICONST_1);
			method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Variables.class), "setVariable",
				"(" + STRING + OBJECT + EVENT + "Z)V", false);
			emitter.loadActualNext(effect);
		}
	}

	public static String formatStringPart(Expression<?> expression, Event event, StringMode mode) {
		Object[] values = expression.getArray(event);
		// VariableString inspects the first element's type before formatting
		if (values != null && values.length > 0) {
			values[0].getClass();
		}
		return Classes.toString(values, true, mode);
	}
}
