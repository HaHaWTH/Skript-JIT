package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Cloner;
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

import java.util.ArrayList;
import java.util.List;
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

		boolean writesWithoutConversion() {
			// The builtin boxed kinds have serializers, so serializeAs cannot be set after registration.
			return Classes.getSuperClassInfo(boxed).getSerializeAs() == null;
		}

		int loadOpcode() {
			return type.getOpcode(ILOAD);
		}

		int storeOpcode() {
			return type.getOpcode(ISTORE);
		}
	}

	static final class CloneAssumptions {
		private final List<ClassInfo<?>> infos = new ArrayList<>();

		boolean requireNoCloner(Class<?> type) {
			ClassInfo<?> info = Classes.getSuperClassInfo(type);
			if (info.getCloner() != null) return false;
			if (infos.stream().noneMatch(existing -> existing == info)) {
				infos.add(info);
			}
			return true;
		}

		void guard(BytecodeCompiler.Emitter emitter, Label miss) {
			/*
			 * Each ClassInfo is the one selected for an actual RHS box when the variant was planned.
			 * if (info.getCloner() != null) {
			 *     break miss; // Execute the original path before committing any writes.
			 * }
			 */
			for (ClassInfo<?> info : infos) {
				emitter.loadConstant(info, ClassInfo.class);
				emitter.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassInfo.class), "getCloner",
					Type.getMethodDescriptor(Type.getType(Cloner.class)), false);
				emitter.method().visitJumpInsn(IFNONNULL, miss);
			}
		}
	}

	record Slot(int value, int dirty, int reference) {
		Slot(int value, int dirty) {
			this(value, dirty, -1);
		}

		Slot(BytecodeCompiler.Emitter emitter, NumericKind kind) {
			this(emitter.newLocal(kind.type), emitter.newLocal(Type.INT_TYPE));
		}

		Slot(BytecodeCompiler.Emitter emitter, NumericKind kind, boolean keepReference) {
			this(emitter.newLocal(kind.type), emitter.newLocal(Type.INT_TYPE),
				keepReference ? emitter.newLocal(OBJECT) : -1);
		}

		void loadBox(MethodVisitor method, NumericKind kind) {
			/*
			 * Example for a long slot that retains its boxed reference.
			 * if (boxed == null) {
			 *     boxed = Long.valueOf(value);
			 * }
			 * // Leave boxed on the operand stack.
			 */
			Label ready = new Label();
			if (reference >= 0) {
				method.visitVarInsn(ALOAD, reference);
				method.visitInsn(DUP);
				method.visitJumpInsn(IFNONNULL, ready);
				method.visitInsn(POP);
			}
			method.visitVarInsn(kind.loadOpcode(), value);
			method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(kind.boxed), "valueOf",
				Type.getMethodDescriptor(Type.getType(kind.boxed), kind.type), false);
			if (reference >= 0) {
				method.visitInsn(DUP);
				method.visitVarInsn(ASTORE, reference);
				method.visitLabel(ready);
			}
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
		/*
		 * The default addition and subtraction use this promotion predicate.
		 * if (((left ^ result) & (right ^ result)) < 0L) {
		 *     break overflow;
		 * }
		 */
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

	static void emitReadRaw(BytecodeCompiler.Emitter emitter, String name, int initial) {
		// Object raw = Variables.getVariable(name, event, true);
		MethodVisitor method = emitter.method();
		emitter.loadLocalName(name);
		emitter.loadEvent();
		method.visitInsn(ICONST_1);
		method.visitMethodInsn(INVOKESTATIC, VARIABLES, "getVariable",
			Type.getMethodDescriptor(OBJECT, STRING, EVENT, Type.BOOLEAN_TYPE), false);
		method.visitVarInsn(ASTORE, initial);
	}

	static void emitUnbox(MethodVisitor method, NumericKind kind, boolean acceptsInteger, Slot slot, int initial, Label miss) {
		/*
		 * Example for a long slot that accepts both integral boxes and retains its reference.
		 * if (!(raw instanceof Long) && !(raw instanceof Integer)) {
		 *     break variant; // Follow the caller's miss label.
		 * }
		 * long value = ((Number) raw).longValue();
		 * Object boxed = raw;
		 */
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
		if (slot.reference() >= 0) {
			method.visitVarInsn(ALOAD, initial);
			method.visitVarInsn(ASTORE, slot.reference());
		}
	}

	static void emitSpill(BytecodeCompiler.Emitter emitter, Map<String, Slot> slots, Map<String, NumericKind> writes) {
		/*
		 * One write block per assigned slot. This example keeps the box for a long slot.
		 * if (dirty) {
		 *     if (boxed == null) {
		 *         boxed = Long.valueOf(value);
		 *     }
		 *     Variables.setVariable(name, boxed, event, true);
		 * }
		 */
		MethodVisitor method = emitter.method();
		for (var entry : writes.entrySet()) {
			Slot slot = slots.get(entry.getKey());
			Label clean = new Label();
			method.visitVarInsn(ILOAD, slot.dirty());
			method.visitJumpInsn(IFEQ, clean);
			emitter.loadLocalName(entry.getKey());
			slot.loadBox(method, entry.getValue());
			emitter.loadEvent();
			method.visitInsn(ICONST_1);
			method.visitMethodInsn(INVOKESTATIC, VARIABLES, "setVariable",
				Type.getMethodDescriptor(Type.VOID_TYPE, STRING, OBJECT, EVENT, Type.BOOLEAN_TYPE), false);
			method.visitLabel(clean);
		}
	}
}
