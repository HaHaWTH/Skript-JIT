package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.expressions.ExprLoopValue;
import ch.njol.skript.expressions.ExprWorlds;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionList;
import ch.njol.skript.lang.LiteralList;
import ch.njol.skript.lang.LiteralString;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.simplification.SimplifiedLiteral;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.Skatalyst;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps the iterator and current value of a closed copy loop in JVM locals
 */
final class CollectionLoopOptimization {

	private static final Type EVENT = Type.getType(Event.class);
	private static final Type ITERATOR = Type.getType(Iterator.class);
	private static final Type OBJECT = Type.getType(Object.class);
	private static final Type STRING = Type.getType(String.class);
	private static final String LOOP = Type.getInternalName(SecLoop.class);
	private static final int MAX_COPIES = 16;

	private CollectionLoopOptimization() {
	}

	static @Nullable BytecodeCompiler.Optimization plan(SecLoop loop) {
		Expression<?> source = loop.getLoopedExpression();
		List<TriggerItem> items = loop.skatalystItems();
		if (loop.skatalystExposesLocals() || items.isEmpty() || items.size() > MAX_COPIES
			|| !(source.getClass() == ExprWorlds.class || isPlainLiteral(source))) {
			return null;
		}
		List<Copy> copies = new ArrayList<>(items.size());
		for (TriggerItem item : items) {
			if (item.getClass() != EffChange.class || item.skatalystExposesLocals()) {
				return null;
			}
			var change = ((EffChange) item).skatalystChange();
			if (change.mode() != ChangeMode.SET || change.source() == null
				|| change.source().getClass() != ExprLoopValue.class
				|| !((ExprLoopValue) change.source()).skatalystIsCurrentValueOf(loop)) {
				return null;
			}
			String name = CoreOptimizations.scalarLocal(change.target());
			if (name == null) {
				return null;
			}
			copies.add(new Copy(item, name));
		}
		return new Region(loop, source, List.copyOf(copies));
	}

	private static boolean isPlainLiteral(Expression<?> expression) {
		if (expression.getClass() == LiteralList.class || expression.getClass() == ExpressionList.class) {
			if (!expression.getAnd()) {
				return false;
			}
			for (Expression<?> operand : ((ExpressionList<?>) expression).getExpressions()) {
				if (!isPlainLiteral(operand)) {
					return false;
				}
			}
			return true;
		}
		Class<?> type = expression.getReturnType();
		return (expression.getClass() == SimpleLiteral.class || expression.getClass() == LiteralString.class
			|| expression.getClass() == SimplifiedLiteral.class)
			&& (type == String.class || type == Long.class || type == Double.class
			|| type == Integer.class || type == Boolean.class || type == Number.class);
	}

	private record Copy(TriggerItem item, String name) {
	}

	private record Region(SecLoop loop, Expression<?> source, List<Copy> copies) implements BytecodeCompiler.Optimization {
		@Override
		public void emit(BytecodeCompiler.Emitter emitter) {
			MethodVisitor method = emitter.method();
			Label original = new Label();
			Label head = new Label();
			Label done = new Label();
			Label empty = new Label();
			emitter.loadConstant(loop, SecLoop.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, LOOP, "skatalystHasIterator", Type.getMethodDescriptor(Type.BOOLEAN_TYPE, EVENT), false);
			method.visitJumpInsn(IFNE, original);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Skatalyst.class), "localsEscaped",
				Type.getMethodDescriptor(Type.BOOLEAN_TYPE, EVENT), false);
			method.visitJumpInsn(IFNE, original);
			for (int i = 1; i < copies.size(); i++) {
				CoreOptimizations.guardSuccessor(emitter, copies.get(i - 1).item(), copies.get(i).item(), original);
			}
			CoreOptimizations.guardSuccessor(emitter, copies.getLast().item(), loop, original);

			int iterator = emitter.newLocal(ITERATOR);
			int current = emitter.newLocal(OBJECT);
			emitter.loadConstant(source, Expression.class);
			emitter.loadEvent();
			method.visitMethodInsn(INVOKEINTERFACE, Type.getInternalName(Expression.class), "iterator",
				Type.getMethodDescriptor(ITERATOR, EVENT), true);
			method.visitVarInsn(ASTORE, iterator);

			method.visitLabel(head);
			emitter.setCurrent(loop);
			method.visitVarInsn(ALOAD, iterator);
			method.visitMethodInsn(INVOKEINTERFACE, ITERATOR.getInternalName(), "hasNext", "()Z", true);
			method.visitJumpInsn(IFEQ, empty);
			method.visitVarInsn(ALOAD, iterator);
			method.visitMethodInsn(INVOKEINTERFACE, ITERATOR.getInternalName(), "next", "()Ljava/lang/Object;", true);
			method.visitVarInsn(ASTORE, current);
			for (Copy copy : copies) {
				emitter.setCurrent(copy.item());
				emitter.loadConstant(copy.name(), String.class);
				method.visitVarInsn(ALOAD, current);
				method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Classes.class), "clone", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
				emitter.loadEvent();
				method.visitInsn(ICONST_1);
				method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Variables.class), "setVariable",
					Type.getMethodDescriptor(Type.VOID_TYPE, STRING, OBJECT, EVENT, Type.BOOLEAN_TYPE), false);
			}
			method.visitJumpInsn(GOTO, head);
			method.visitLabel(empty);
			emitter.loadActualNext(loop);
			method.visitJumpInsn(GOTO, done);
			method.visitLabel(original);
			emitter.walkOriginal(loop);
			method.visitLabel(done);
		}
	}
}
