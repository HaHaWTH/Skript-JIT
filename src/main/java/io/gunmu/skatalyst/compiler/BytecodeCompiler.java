package io.gunmu.skatalyst.compiler;

import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.util.ConvertedExpression;
import io.gunmu.skatalyst.Skatalyst;
import io.gunmu.skatalyst.compiler.control.BranchOptimizations;
import io.gunmu.skatalyst.compiler.optimize.CoreOptimizations;
import io.gunmu.skatalyst.compiler.optimize.ScalarFunctionOptimization;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

@ApiStatus.Internal
public final class BytecodeCompiler {

	private static final int CHUNK_ITEMS = 32;
	private static final int MAX_CODE_BYTES = 6_000;
	private static final int ENTRY = 1;
	private static final int EVENT = 2;
	private static final int CURRENT = 3;
	private static final int NEXT = 4;
	private static final String HIDDEN_NAME = "io/gunmu/skatalyst/compiler/CompiledTrigger";
	private static final String HANDLE = Type.getInternalName(MethodHandle.class);
	private static final Type TRIGGER_ITEM = Type.getType(TriggerItem.class);
	private static final Type EVENT_TYPE = Type.getType(Event.class);
	private static final String WALK = Type.getMethodDescriptor(TRIGGER_ITEM, TRIGGER_ITEM, EVENT_TYPE);
	private static final String WALK_CHUNK = Type.getMethodDescriptor(TRIGGER_ITEM, Type.INT_TYPE, EVENT_TYPE);
	private static final String WALK_FAILURE = Type.getInternalName(CompiledCode.WalkFailure.class);
	private static final Handle CLASS_DATA = new Handle(H_INVOKESTATIC, Type.getInternalName(MethodHandles.class),
		"classDataAt", Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(MethodHandles.Lookup.class),
		Type.getType(String.class), Type.getType(Class.class), Type.INT_TYPE), false);

	private BytecodeCompiler() {}

	public static CompiledCode compile(Trigger trigger) {
		Map<TriggerItem, Optimization> optimizations = BranchOptimizations.plan(trigger);
		optimizations.putAll(CoreOptimizations.plan(trigger));
		CompiledCode code = compile(trigger, optimizations);
		DirectFunction function = ScalarFunctionOptimization.compile(trigger);
		return function == null ? code : code.withFunction(function);
	}

	public static DirectFunction compileFunction(List<String> parameters, @Nullable ConvertedExpression<?, ?> conversion, Consumer<Emitter> emit) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		writer.visit(V21, ACC_FINAL | ACC_SUPER, "io/gunmu/skatalyst/compiler/CompiledScalarFunction", null,
			"java/lang/Object", new String[]{Type.getInternalName(DirectFunction.Body.class)});
		writeConstructor(writer);
		Type object = Type.getType(Object.class);
		Type[] arguments = new Type[DirectFunction.MAX_PARAMETERS];
		Arrays.fill(arguments, object);
		MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "invoke", Type.getMethodDescriptor(object, arguments), null, null);
		Emitter emitter = new Emitter(method, TriggerItem.skatalystWalkHandle());
		emitter.nextLocal = arguments.length + 1;
		method.visitCode();
		emit.accept(emitter);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return new DirectFunction(parameters, (DirectFunction.Body) link(writer, emitter.constants), conversion);
	}

	public static CompiledCode compile(Trigger trigger, Map<TriggerItem, Optimization> optimizations) {
		List<Item> items = new ArrayList<>();
		Set<TriggerItem> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		collect(trigger, null, null, null, items, visited);

		IdentityHashMap<TriggerItem, CompiledCode.Entry> entries = new IdentityHashMap<>();
		Set<TriggerItem> optimized = Collections.newSetFromMap(new IdentityHashMap<>());
		if (items.size() == 1 && !optimizations.containsKey(trigger))
			return new CompiledCode(entries, optimized);

		MethodHandle walkHandle = TriggerItem.skatalystWalkHandle();
		for (int start = 0; start < items.size(); start += CHUNK_ITEMS) {
			List<Item> chunkItems = items.subList(start, Math.min(start + CHUNK_ITEMS, items.size()));
			installChunk(chunkItems, walkHandle, optimizations, optimized, entries);
		}
		return new CompiledCode(entries, optimized);
	}

	private static void collect(TriggerItem node, @Nullable TriggerItem following, @Nullable TriggerItem exit,
			@Nullable TriggerItem ancestorExit, List<Item> items, Set<TriggerItem> visited) {
		if (!visited.add(node))
			return;

		List<TriggerItem> children = node instanceof TriggerSection section ? section.skatalystItems() : List.of();
		items.add(new Item(node, children.isEmpty() ? null : children.getFirst(), following, exit, ancestorExit));
		TriggerItem childExit = node instanceof LoopSection ? node : following;
		// Branch tails can be retargeted to their enclosing section's continuation.
		TriggerItem childAncestorExit = exit != null && exit != childExit ? exit : ancestorExit;
		for (int index = 0; index < children.size(); index++) {
			TriggerItem childFollowing = index + 1 < children.size() ? children.get(index + 1) : childExit;
			collect(children.get(index), childFollowing, childExit, childAncestorExit, items, visited);
		}
	}

	private static void installChunk(List<Item> items, MethodHandle walkHandle, Map<TriggerItem, Optimization> plans,
			Set<TriggerItem> optimized, IdentityHashMap<TriggerItem, CompiledCode.Entry> entries) {
		EncodedChunk encoded = encodeChunk(items, walkHandle, plans);
		if (encoded.length() > MAX_CODE_BYTES) {
			if (items.size() > 1) {
				int half = items.size() / 2;
				installChunk(items.subList(0, half), walkHandle, plans, optimized, entries);
				installChunk(items.subList(half, items.size()), walkHandle, plans, optimized, entries);
				return;
			}
			encoded = encodeChunk(items, walkHandle, Map.of());
		}
		CompiledCode.Chunk chunk = encoded.link();
		optimized.addAll(encoded.optimized());
		for (int index = 0; index < items.size(); index++) {
			entries.put(items.get(index).node(), new CompiledCode.Entry(chunk, index));
		}
	}

	private static EncodedChunk encodeChunk(List<Item> items, MethodHandle walkHandle,
			Map<TriggerItem, Optimization> optimizations) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		writer.visit(V21, ACC_FINAL | ACC_SUPER, HIDDEN_NAME, null, "java/lang/Object",
			new String[]{Type.getInternalName(CompiledCode.Chunk.class)});
		writeConstructor(writer);

		MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "walk", WALK_CHUNK, null, null);
		Emitter emitter = new Emitter(method, walkHandle);
		Label[] labels = new Label[items.size()];
		Map<TriggerItem, Label> targets = new IdentityHashMap<>();
		for (int index = 0; index < items.size(); index++) {
			Label label = new Label();
			labels[index] = label;
			targets.put(items.get(index).node(), label);
		}

		Label body = new Label();
		Label end = new Label();
		Label handler = new Label();
		Label invalid = new Label();
		List<TriggerItem> optimized = new ArrayList<>();
		List<ColdExit> coldExits = new ArrayList<>();
		method.visitCode();
		// Only Exceptions need the failing node for Skript's reporting path.
		method.visitTryCatchBlock(body, end, handler, "java/lang/Exception");
		method.visitInsn(ACONST_NULL);
		method.visitVarInsn(ASTORE, CURRENT);
		method.visitVarInsn(ILOAD, ENTRY);
		method.visitTableSwitchInsn(0, labels.length - 1, invalid, labels);
		method.visitLabel(body);

		for (int index = 0; index < items.size(); index++) {
			Item item = items.get(index);
			TriggerItem adjacent = index + 1 < items.size() ? items.get(index + 1).node() : null;
			method.visitLabel(labels[index]);
			emitter.start(item.node());
			Optimization optimization = optimizations.get(item.node());
			if (optimization != null) {
				optimization.emit(emitter);
				optimized.add(item.node());
			} else {
				emitter.walkCurrent();
			}

			if (emitter.fixedSuccessor) {
				if (adjacent == null || adjacent != emitter.successor)
					returnOrJump(emitter, targets, emitter.successor);
				continue;
			}

			// Lexical successors predict fast jumps. The actual cursor remains authoritative.
			TriggerItem regionExit = lexicalSuccessor(items, emitter.regionTail);
			method.visitVarInsn(ASTORE, NEXT);
			if (adjacent != null && (item.expects(adjacent) || adjacent == regionExit)) {
				Label cold = new Label();
				method.visitVarInsn(ALOAD, NEXT);
				emitter.loadConstant(adjacent, TriggerItem.class);
				method.visitJumpInsn(IF_ACMPNE, cold);
				coldExits.add(new ColdExit(cold, item, adjacent, regionExit));
			} else {
				emitExits(emitter, targets, item, null, regionExit);
			}
		}
		for (ColdExit exit : coldExits) {
			method.visitLabel(exit.label());
			emitExits(emitter, targets, exit.item(), exit.adjacent(), exit.regionExit());
		}

		method.visitLabel(end);
		method.visitLabel(invalid);
		method.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
		method.visitInsn(DUP);
		method.visitLdcInsn("Invalid Skatalyst entry");
		method.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>",
			"(Ljava/lang/String;)V", false);
		method.visitInsn(ATHROW);

		method.visitLabel(handler);
		method.visitVarInsn(ASTORE, NEXT);
		method.visitTypeInsn(NEW, WALK_FAILURE);
		method.visitInsn(DUP);
		method.visitVarInsn(ALOAD, CURRENT);
		method.visitVarInsn(ALOAD, NEXT);
		method.visitMethodInsn(INVOKESPECIAL, WALK_FAILURE, "<init>",
			Type.getMethodDescriptor(Type.VOID_TYPE, TRIGGER_ITEM, Type.getType(Throwable.class)), false);
		method.visitInsn(ATHROW);
		Label methodEnd = new Label();
		method.visitLabel(methodEnd);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return new EncodedChunk(writer, emitter.constants, optimized, methodEnd.getOffset());
	}

	private record EncodedChunk(ClassWriter writer, List<Object> constants, List<TriggerItem> optimized, int length) {
		private CompiledCode.Chunk link() {
			return (CompiledCode.Chunk) BytecodeCompiler.link(writer, constants);
		}
	}

	private static Object link(ClassWriter writer, List<Object> constants) {
		try {
			MethodHandles.Lookup hidden = MethodHandles.lookup().defineHiddenClassWithClassData(
				writer.toByteArray(), List.copyOf(constants), true);
			return hidden.lookupClass().getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Could not link Skatalyst bytecode", exception);
		}
	}

	private static void writeConstructor(ClassWriter writer) {
		MethodVisitor method = writer.visitMethod(0, "<init>", "()V", null, null);
		method.visitCode();
		method.visitVarInsn(ALOAD, 0);
		method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		method.visitInsn(RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
	}

	private static void branchTo(Emitter emitter, Map<TriggerItem, Label> targets, @Nullable TriggerItem node) {
		Label target = targets.get(node);
		if (target == null)
			return;
		emitter.method.visitVarInsn(ALOAD, NEXT);
		emitter.loadConstant(node, TriggerItem.class);
		emitter.method.visitJumpInsn(IF_ACMPEQ, target);
	}

	private static @Nullable TriggerItem lexicalSuccessor(List<Item> items, @Nullable TriggerItem tail) {
		if (tail != null) {
			for (Item item : items) {
				if (item.node() == tail)
					return item.following();
			}
		}
		return null;
	}

	private static void emitExits(Emitter emitter, Map<TriggerItem, Label> targets, Item item,
			@Nullable TriggerItem adjacent, @Nullable TriggerItem regionExit) {
		if (item.firstChild() != adjacent)
			branchTo(emitter, targets, item.firstChild());
		if (item.following() != adjacent && item.following() != item.firstChild())
			branchTo(emitter, targets, item.following());
		if (item.exit() != adjacent && item.exit() != item.following() && item.exit() != item.firstChild())
			branchTo(emitter, targets, item.exit());
		TriggerItem ancestor = item.ancestorExit();
		if (ancestor != adjacent && ancestor != item.exit() && ancestor != item.following() && ancestor != item.firstChild())
			branchTo(emitter, targets, ancestor);
		if (regionExit != adjacent && !item.expects(regionExit))
			branchTo(emitter, targets, regionExit);
		emitter.method.visitVarInsn(ALOAD, NEXT);
		emitter.method.visitInsn(ARETURN);
	}

	private static void returnOrJump(Emitter emitter, Map<TriggerItem, Label> targets, @Nullable TriggerItem node) {
		Label target = targets.get(node);
		if (target != null) {
			emitter.method.visitJumpInsn(GOTO, target);
			return;
		}
		if (node == null) {
			emitter.method.visitInsn(ACONST_NULL);
		} else {
			emitter.loadConstant(node, TriggerItem.class);
		}
		emitter.method.visitInsn(ARETURN);
	}

	private record Item(TriggerItem node, @Nullable TriggerItem firstChild, @Nullable TriggerItem following,
						@Nullable TriggerItem exit, @Nullable TriggerItem ancestorExit) {
		private boolean expects(TriggerItem item) {
			return item == firstChild || item == following || item == exit || item == ancestorExit;
		}
	}

	private record ColdExit(Label label, Item item, TriggerItem adjacent, @Nullable TriggerItem regionExit) {}

	@FunctionalInterface
	public interface Optimization {
		/** Emits a region that pushes its actual successor, or fixes its successor with {@link Emitter#finishAt}. */
		void emit(Emitter emitter);
	}

	public static final class Emitter {

		private final MethodVisitor method;
		private final MethodHandle walkHandle;
		private final List<Object> constants = new ArrayList<>();
		private final Map<Object, Integer> constantIndices = new IdentityHashMap<>();
		private int nextLocal;
		private boolean fixedSuccessor;
		private @Nullable TriggerItem successor;
		private @Nullable TriggerItem regionTail;

		private Emitter(MethodVisitor method, MethodHandle walkHandle) {
			this.method = method;
			this.walkHandle = walkHandle;
		}

		private void start(TriggerItem item) {
			nextLocal = NEXT + 1;
			fixedSuccessor = false;
			successor = null;
			regionTail = null;
			setCurrent(item);
			if (item.skatalystExposesLocals()) {
				loadEvent();
				method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Skatalyst.class), "exposeLocals",
					Type.getMethodDescriptor(Type.VOID_TYPE, EVENT_TYPE), false);
			}
		}

		public MethodVisitor method() {
			return method;
		}

		public void loadEvent() {
			method.visitVarInsn(ALOAD, EVENT);
		}

		public void loadConstant(Object value, Class<?> type) {
			int index = constantIndices.computeIfAbsent(value, ignored -> {
				int nextIndex = constants.size();
				constants.add(value);
				return nextIndex;
			});
			method.visitLdcInsn(new ConstantDynamic("_", Type.getDescriptor(type), CLASS_DATA, index));
		}

		public void setCurrent(TriggerItem item) {
			loadConstant(item, TriggerItem.class);
			method.visitVarInsn(ASTORE, CURRENT);
		}

		public void walkOriginal(TriggerItem item) {
			loadConstant(walkHandle, MethodHandle.class);
			loadConstant(item, TriggerItem.class);
			invokeWalk();
		}

		private void walkCurrent() {
			loadConstant(walkHandle, MethodHandle.class);
			method.visitVarInsn(ALOAD, CURRENT);
			invokeWalk();
		}

		private void invokeWalk() {
			loadEvent();
			method.visitMethodInsn(INVOKEVIRTUAL, HANDLE, "invokeExact", WALK, false);
		}

		public void finishAt(@Nullable TriggerItem node) {
			fixedSuccessor = true;
			successor = node;
		}

		public void loadActualNext(TriggerItem item) {
			loadConstant(item, TriggerItem.class);
			method.visitMethodInsn(INVOKEVIRTUAL, TRIGGER_ITEM.getInternalName(), "getActualNext",
				Type.getMethodDescriptor(TRIGGER_ITEM), false);
		}

		/** Loads the live successor of a compiled region ending at {@code tail}. */
		public void loadRegionSuccessor(TriggerItem tail) {
			regionTail = tail;
			loadActualNext(tail);
		}

		public int newLocal(Type type) {
			int slot = nextLocal;
			nextLocal += type.getSize();
			return slot;
		}

		public void pushInt(int value) {
			if (value >= -1 && value <= 5) {
				method.visitInsn(ICONST_0 + value);
			} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
				method.visitIntInsn(BIPUSH, value);
			} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
				method.visitIntInsn(SIPUSH, value);
			} else {
				method.visitLdcInsn(value);
			}
		}
	}
}
