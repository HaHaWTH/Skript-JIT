package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.arithmetic.Arithmetics;
import org.skriptlang.skript.lang.arithmetic.Operation;
import org.skriptlang.skript.lang.arithmetic.Operator;

import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

final class NumericSlots {

	private static final Type OBJECT = Type.getType(Object.class);
	private static final Type STRING = Type.getType(String.class);
	private static final Type EVENT = Type.getType(Event.class);
	private static final String VARIABLES = Type.getInternalName(Variables.class);

	private NumericSlots() {}

	enum NumericKind {
		INTEGRAL(Type.LONG_TYPE, Long.class, "longValue"),
		DECIMAL(Type.DOUBLE_TYPE, Double.class, "doubleValue");

		final Type type;
		final Class<?> boxed;
		private final String unbox;

		NumericKind(Type type, Class<?> boxed, String unbox) {
			this.type = type;
			this.boxed = boxed;
			this.unbox = unbox;
		}

		boolean canStoreDirectly() {
			var info = Classes.getSuperClassInfo(boxed);
			return info.getSerializeAs() == null && info.getCloner() == null;
		}

		int loadOpcode() {
			return type.getOpcode(ILOAD);
		}

		int storeOpcode() {
			return type.getOpcode(ISTORE);
		}
	}

	record Slot(int value, int dirty) {
		Slot(BytecodeCompiler.Emitter emitter, NumericKind kind) {
			this(emitter.newLocal(kind.type), emitter.newLocal(Type.INT_TYPE));
		}

		void markDirty(MethodVisitor method, boolean written) {
			method.visitInsn(written ? ICONST_1 : ICONST_0);
			method.visitVarInsn(ISTORE, dirty);
		}
	}

	static @Nullable Number literal(@Nullable Expression<?> expression) {
		if (expression == null || expression.getClass() != SimpleLiteral.class)
			return null;
		Object[] values = ((SimpleLiteral<?>) expression).getAll();
		return values.length == 1 && (values[0] instanceof Long || values[0] instanceof Integer || values[0] instanceof Double)
			? (Number) values[0] : null;
	}

	static boolean usesOperation(Operator operator, Class<?> left, Class<?> right, Operation<?, ?, ?> expected) {
		var actual = Arithmetics.getOperationInfo(operator, left, right);
		return actual != null && actual.operation() == expected;
	}

	static void guardIntegralResult(MethodVisitor method, int left, int right, int result, Label overflow) {
		// Both the default addition and subtraction use this promotion predicate.
		method.visitVarInsn(LLOAD, left);
		method.visitVarInsn(LLOAD, result);
		method.visitInsn(LXOR);
		method.visitVarInsn(LLOAD, right);
		method.visitVarInsn(LLOAD, result);
		method.visitInsn(LXOR);
		method.visitInsn(LAND);
		method.visitInsn(LCONST_0);
		method.visitInsn(LCMP);
		method.visitJumpInsn(IFLT, overflow);
	}

	static void emitRead(BytecodeCompiler.Emitter emitter, String name, NumericKind kind, boolean acceptsInteger,
			Slot slot, int initial, Label miss) {
		MethodVisitor method = emitter.method();
		emitter.loadConstant(name, String.class);
		emitter.loadEvent();
		method.visitInsn(ICONST_1);
		method.visitMethodInsn(INVOKESTATIC, VARIABLES, "getVariable",
			Type.getMethodDescriptor(OBJECT, STRING, EVENT, Type.BOOLEAN_TYPE), false);
		method.visitVarInsn(ASTORE, initial);
		method.visitVarInsn(ALOAD, initial);
		method.visitTypeInsn(INSTANCEOF, Type.getInternalName(kind.boxed));
		if (kind == NumericKind.INTEGRAL && acceptsInteger) {
			Label integral = new Label();
			method.visitJumpInsn(IFNE, integral);
			method.visitVarInsn(ALOAD, initial);
			method.visitTypeInsn(INSTANCEOF, "java/lang/Integer");
			method.visitJumpInsn(IFEQ, miss);
			method.visitLabel(integral);
		} else {
			method.visitJumpInsn(IFEQ, miss);
		}
		method.visitVarInsn(ALOAD, initial);
		method.visitTypeInsn(CHECKCAST, "java/lang/Number");
		method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", kind.unbox, "()" + kind.type.getDescriptor(), false);
		method.visitVarInsn(kind.storeOpcode(), slot.value());
	}

	static void emitSpill(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, NumericKind kind) {
		MethodVisitor method = emitter.method();
		for (var entry : slots.entrySet()) {
			Slot slot = entry.getValue();
			Label clean = new Label();
			method.visitVarInsn(ILOAD, slot.dirty());
			method.visitJumpInsn(IFEQ, clean);
			emitter.loadConstant(entry.getKey(), String.class);
			method.visitVarInsn(kind.loadOpcode(), slot.value());
			method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(kind.boxed), "valueOf",
				Type.getMethodDescriptor(Type.getType(kind.boxed), kind.type), false);
			emitter.loadEvent();
			method.visitInsn(ICONST_1);
			method.visitMethodInsn(INVOKESTATIC, VARIABLES, "setVariable",
				Type.getMethodDescriptor(Type.VOID_TYPE, STRING, OBJECT, EVENT, Type.BOOLEAN_TYPE), false);
			method.visitLabel(clean);
		}
	}
}
