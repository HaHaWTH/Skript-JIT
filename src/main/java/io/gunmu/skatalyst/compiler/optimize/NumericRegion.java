package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.data.DefaultComparators;
import ch.njol.skript.conditions.CondCompare;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.effects.EffContinue;
import ch.njol.skript.effects.EffExit;
import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.sections.SecConditional;
import ch.njol.skript.sections.SecWhile;
import io.gunmu.skatalyst.addon.ParsedBinding.NumericCall;
import io.gunmu.skatalyst.compiler.BytecodeCompiler.Emitter;
import io.gunmu.skatalyst.compiler.control.ComparisonOptimizations;
import io.gunmu.skatalyst.compiler.optimize.NumericExpression.*;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.CloneAssumptions;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind;
import io.gunmu.skatalyst.compiler.optimize.NumericSlots.Slot;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.skriptlang.skript.lang.comparator.Comparators;
import org.skriptlang.skript.lang.comparator.Relation;

import java.util.*;

import static io.gunmu.skatalyst.compiler.optimize.NumericSlots.NumericKind.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A block of scalar changes and closed if/else.
 */
final class NumericRegion {
	static final int LYZK_67CT = 32;
	static final int MAX_NODES = 32;
	private static final int MAX_VARIANTS = 4;
	private static final int MAX_VARIANT_NODES = 2 * MAX_NODES;
	private static final int MAX_MIXED_SEEDS = 6;
	private static final Type OBJECT = Type.getType(Object.class);
	static final Type ITEM = Type.getType(TriggerItem.class);
	static final Type EVENT = Type.getType(org.bukkit.event.Event.class);

	final @Nullable LoopSection loop;
	final Map<String, NumericKind> writes = new LinkedHashMap<>();
	final Map<TriggerItem, TriggerItem> edges = new LinkedHashMap<>();
	final Set<NumericCall> intrinsics = new LinkedHashSet<>();
	final CloneAssumptions cloning = new CloneAssumptions();
	private final Map<String, NumericKind> kinds;
	private final NumericKind defaultKind;
	private final Set<String> names = new LinkedHashSet<>();
	private final Set<String> references = new HashSet<>();
	private final Map<String, Boolean> initialReads = new LinkedHashMap<>();
	private final Set<Class<?>> clonedTypes = new LinkedHashSet<>();
	private final Set<String> possibleIntegerSources = new LinkedHashSet<>();
	private List<Statement> body;
	private @Nullable NumericCondition condition;
	private int nodes;

	private NumericRegion(@Nullable LoopSection loop, NumericKind defaultKind, Map<String, NumericKind> kinds) {
		this.loop = loop;
		this.defaultKind = defaultKind;
		this.kinds = kinds;
	}

	static List<NumericRegion> plans(@Nullable LoopSection loop, List<TriggerItem> items,
			@Nullable CondCompare comparison, @Nullable TriggerItem comparisonOwner) {
		List<EffChange> changes = new ArrayList<>();
		Map<String, NumericKind> fixedKinds = new HashMap<>();
		TriggerItem owner = loop == null ? comparisonOwner : loop;
		if (items.isEmpty() || !collectChanges(items, changes, fixedKinds) || changes.isEmpty()
				|| owner != null && !CoreOptimizations.inferNumericOperands(owner, fixedKinds)) {
			return List.of();
		}
		List<NumericRegion> plans = new ArrayList<>(MAX_VARIANTS);
		int emittedNodes = 0;
		for (NumericKind kind : NumericKind.values()) {
			NumericRegion plan = new NumericRegion(loop, kind, new HashMap<>(fixedKinds));
			if (plan.build(items, changes, comparison, comparisonOwner) && !plan.duplicates(plans)) {
				plans.add(plan);
				emittedNodes += plan.nodes;
			}
		}

		// Flip exactly one incoming slot from a proven plan. Known types of other live inputs
		// must stay fixed, only slots established by the region can be inferred again.
		for (NumericRegion base : List.copyOf(plans)) {
			if (base.initialReads.size() < 2) continue;
			Map<String, NumericKind> incoming = new HashMap<>(fixedKinds);
			base.initialReads.keySet().forEach(name -> incoming.put(name, base.kind(name)));
			int seeds = 0;
			for (String input : base.initialReads.keySet()) {
				if (plans.size() == MAX_VARIANTS || emittedNodes == MAX_VARIANT_NODES) return plans;
				if (fixedKinds.containsKey(input)) continue;
				if (seeds++ == MAX_MIXED_SEEDS) break;
				NumericKind kind = base.kind(input);
				Map<String, NumericKind> assumption = new HashMap<>(incoming);
				assumption.put(input, kind == INTEGRAL ? DECIMAL : INTEGRAL);
				NumericRegion plan = new NumericRegion(loop, base.defaultKind, assumption);
				if (plan.build(items, changes, comparison, comparisonOwner) && emittedNodes + plan.nodes <= MAX_VARIANT_NODES
						&& plan.hasMixedInputs() && !plan.duplicates(plans)) {
					plans.add(plan);
					emittedNodes += plan.nodes;
				}
			}
		}
		return plans;
	}

	private boolean build(List<TriggerItem> items, List<EffChange> changes, @Nullable CondCompare comparison,
			@Nullable TriggerItem comparisonOwner) {
		inferKinds(changes);
		Set<String> defined = new HashSet<>();
		if (loop instanceof SecWhile && (condition = test(comparison, loop, defined)) == null) return false;
		body = block(items, defined, loop, loop != null);
		if (body == null) return false;
		if (loop == null && comparisonOwner != null && (condition = test(comparison, comparisonOwner, defined)) == null) return false;
		if (nodes > MAX_NODES) return false;
		for (String name : possibleIntegerSources) {
			if (Boolean.TRUE.equals(initialReads.get(name))) {
				clonedTypes.add(Integer.class);
				break;
			}
		}
		return clonedTypes.stream().allMatch(cloning::requireNoCloner);
	}

	private boolean duplicates(List<NumericRegion> plans) {
		return plans.stream().anyMatch(plan -> names.equals(plan.names)
			&& names.stream().allMatch(name -> kind(name) == plan.kind(name)));
	}

	private boolean hasMixedInputs() {
		return initialReads.keySet().stream().map(this::kind).distinct().limit(2).count() == 2;
	}

	private static boolean collectChanges(List<TriggerItem> items, List<EffChange> changes, Map<String, NumericKind> fixedKinds) {
		if (items.size() > MAX_NODES) return false;
		for (TriggerItem item : items) {
			if (changes.size() >= MAX_NODES || !CoreOptimizations.numericCandidate(item)
					|| !CoreOptimizations.inferNumericOperands(item, fixedKinds)) {
				return false;
			}
			if (item.getClass() == EffChange.class) {
				changes.add((EffChange) item);
			} else if (item.getClass() == SecConditional.class) {
				if (!collectChanges(((SecConditional) item).skatalystItems(), changes, fixedKinds)) return false;
			} else if (item.skatalystExposesLocals() || item.getClass() != EffContinue.class && item.getClass() != EffExit.class) {
				return false;
			}
		}
		return true;
	}

	private NumericKind kind(String name) {
		return kinds.getOrDefault(name, defaultKind);
	}

	private void inferKinds(List<EffChange> changes) {
		// A literal, loop counter or decimal operation can establish a slot independently
		// of the other dynamic inputs (e.g. an integral index and a decimal accumulator).
		int previous;
		do {
			previous = kinds.size();
			for (EffChange effect : changes) {
				var change = effect.skatalystChange();
				String target = CoreOptimizations.scalarLocal(change.target());
				if (target == null || kinds.containsKey(target)) continue;
				var lowered = NumericExpression.change(change.mode(), target, change.source(), this::kind, loop,
					effect.skatalystBinding());
				if (lowered == null) continue;
				NumericExpression value = lowered.value();
				if (value instanceof Constant || value instanceof Iteration || value instanceof IntrinsicCall || value.kind() != defaultKind) {
					kinds.put(target, value.kind());
				}
			}
		} while (kinds.size() != previous);
	}

	private @Nullable List<Statement> block(List<TriggerItem> items, Set<String> defined, @Nullable TriggerItem join, boolean guardTail) {
		List<Statement> statements = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			TriggerItem item = items.get(i);
			if (++nodes > MAX_NODES) return null;
			collectEdge(items, i, join, guardTail);
			if (item.getClass() == EffChange.class) {
				var change = ((EffChange) item).skatalystChange();
				String target = CoreOptimizations.scalarLocal(change.target());
				if (target == null) return null;
				var lowered = NumericExpression.change(change.mode(), target, change.source(), this::kind, loop,
					item.skatalystBinding());
				if (lowered == null) return null;
				NumericExpression value = lowered.value();
				NumericKind kind = kind(target);
				if (value.kind() != kind || !kind.writesWithoutConversion()
					|| value instanceof Constant(Number value1) && value1 instanceof Integer) {
					return null;
				}
				if (!CoreOptimizations.retainNumericIntrinsics(item, intrinsics, value, null)) return null;
				nodes += value.nodes() - 1;
				collectClonedSource(lowered.source(), defined);
				read(value, defined, value instanceof Read);
				if (value instanceof Constant || value instanceof Read) {
					references.add(target);
				}
				if (value instanceof Read read) {
					references.add(read.name());
				}
				names.add(target);
				defined.add(target);
				writes.put(target, kind);
				statements.add(new Assignment(item, target, value));
			} else if (item.getClass() == SecConditional.class && loop != null) {
				int first = i;
				while (i + 1 < items.size() && items.get(i + 1).getClass() == SecConditional.class) {
					SecConditional next = (SecConditional) items.get(i + 1);
					if (!next.skatalystIsElse() && next.skatalystElseIfComparison() == null) {
						break;
					}
					collectEdge(items, ++i, join, guardTail);
					nodes++;
					if (next.skatalystIsElse()) {
						break;
					}
				}
				TriggerItem after = i + 1 < items.size() ? items.get(i + 1) : join;
				List<Arm> arms = new ArrayList<>();
				Set<String> common = null;
				for (int index = first; index <= i; index++) {
					SecConditional section = (SecConditional) items.get(index);
					NumericCondition test = null;
					if (index == first || !section.skatalystIsElse()) {
						test = test(index == first ? section.skatalystSimpleIfComparison() : section.skatalystElseIfComparison(), section, defined);
						if (test == null) return null;
					} else if (section.skatalystExposesLocals()) {
						return null;
					}
					Set<String> armDefined = new HashSet<>(defined);
					List<Statement> body = block(section.skatalystItems(), armDefined, after, false);
					if (body == null) {
						return null;
					}
					arms.add(new Arm(test, section, body, after));
					if (common == null) {
						common = armDefined;
					} else {
						common.retainAll(armDefined);
					}
				}
				if (arms.getLast().test == null) defined.addAll(common);
				statements.add(new Branch(arms));
			} else if (loop != null && item.getClass() == EffContinue.class && ((EffContinue) item).skatalystLoop() == loop) {
				statements.add(code -> code.method().visitJumpInsn(GOTO, code.advance));
			} else if (loop != null && item.getClass() == EffExit.class && ((EffExit) item).skatalystTarget() == loop) {
				statements.add(code -> code.method().visitJumpInsn(GOTO, code.leave));
			} else {
				return null;
			}
		}
		return statements;
	}

	private void collectClonedSource(NumericExpression source, Set<String> defined) {
		if (source instanceof Read read) {
			clonedTypes.add(read.kind().boxed);
			// Incoming integral reads may admit Integerand writes performed by this region produce Long.
			if (read.acceptsInteger() && !defined.contains(read.name())) {
				possibleIntegerSources.add(read.name());
			}
		} else {
			clonedTypes.addAll(source.possibleTypes());
		}
	}

	private void collectEdge(List<TriggerItem> items, int i, @Nullable TriggerItem join, boolean guardTail) {
		if (i + 1 < items.size()) {
			edges.put(items.get(i), items.get(i + 1));
		} else if (guardTail) {
			edges.put(items.get(i), join);
		}

	}

	private void read(NumericExpression value, Set<String> defined, boolean exact) {
		Map<String, Boolean> reads = new LinkedHashMap<>();
		value.collectNames(reads);
		names.addAll(reads.keySet());
		reads.forEach((name, integer) -> {
			if (!defined.contains(name)) {
				initialReads.merge(name, integer && !exact, Boolean::logicalAnd);
			}
		});
	}

	private @Nullable NumericCondition test(@Nullable CondCompare compare, TriggerItem owner, Set<String> defined) {
		var binary = compare == null ? null : compare.skatalystComparison();
		if (binary == null) return null;

		NumericExpression left = NumericExpression.parse(binary.first(), this::kind, loop, owner.skatalystBinding());
		NumericExpression right = NumericExpression.parse(binary.second(), this::kind, loop, owner.skatalystBinding());
		if (left == null || right == null || left instanceof Binary || right instanceof Binary || left.needsExit() || right.needsExit()) {
			return null;
		}
		Class<?> first = comparisonType(left);
		Class<?> second = comparisonType(right);
		boolean dynamic = binary.comparator() == null;
		if ((dynamic ? Comparators.getComparator(first, second) : binary.comparator()) != DefaultComparators.NUMBERS) {
			return null;
		}
		boolean identity = dynamic && first == Double.class && second == Double.class;
		if (identity) {
			if (left instanceof IntrinsicCall || right instanceof IntrinsicCall) {
				return null;
			}
			if (left instanceof Read read) {
				references.add(read.name());
			}
			if (right instanceof Read read) {
				references.add(read.name());
			}
		}
		if (!CoreOptimizations.retainNumericIntrinsics(owner, intrinsics, left, right)) return null;
		read(left, defined, true);
		read(right, defined, true);
		Relation failed = compare.isNegated() ? binary.relation() : binary.relation().getInverse();
		return new NumericCondition(left, right, failed, first == Long.class && second == Long.class, identity);
	}

	private static Class<?> comparisonType(NumericExpression value) {
		return value instanceof Constant constant ? constant.value().getClass() : value.kind().boxed;
	}

	static @Nullable SharedInputs sharedInputs(List<NumericRegion> plans, Emitter emitter) {
		if (plans.size() == 1) return null;
		Map<String, Integer> values = new LinkedHashMap<>();
		Map<String, Label> captured = new HashMap<>();
		for (NumericRegion plan : plans) {
			for (String name : plan.names) {
				if (plan.initialReads.containsKey(name) && !values.containsKey(name)) {
					values.put(name, emitter.newLocal(OBJECT));
					if (plan == plans.getFirst()) {
						captured.put(name, new Label());
					}
				}
			}
		}
		return new SharedInputs(values, captured);
	}

	record SharedInputs(Map<String, Integer> values, Map<String, Label> captured) {
		void readRemainingAfterMiss(Emitter emitter) {
			/*
			 * For two guarded inputs, the first miss label precedes the read below.
			 * The second miss label follows it because that guard already read rawB.
			 *
			 * missingB: {
			 *     rawB = Variables.getVariable("b", event, true);
			 * }
			 * // Try the next variant with rawA and rawB already set.
			 */
			boolean reachedMiss = false;
			for (var input : values.entrySet()) {
				if (reachedMiss) {
					NumericSlots.emitReadRaw(emitter, input.getKey(), input.getValue());
				}
				Label missed = captured.get(input.getKey());
				if (missed != null) {
					emitter.method().visitLabel(missed);
					reachedMiss = true;
				}
			}
		}
	}

	Code enter(Emitter emitter, @Nullable SharedInputs inputs, boolean firstVariant,
			int iteration, Label miss, Label advance, Label leave) {
		Map<String, Slot> slots = new LinkedHashMap<>();
		MethodVisitor method = emitter.method();
		int temporary = inputs == null ? emitter.newLocal(OBJECT) : -1;
		for (String name : names) {
			NumericKind kind = kind(name);
			Slot slot = new Slot(emitter, kind, references.contains(name));
			slots.put(name, slot);
			Boolean integer = initialReads.get(name);
			if (integer != null) {
				int local = inputs == null ? temporary : inputs.values.get(name);
				if (firstVariant) {
					NumericSlots.emitReadRaw(emitter, name, local);
				}
				Label guardMiss = inputs != null && firstVariant ? inputs.captured.get(name) : miss;
				NumericSlots.emitUnbox(method, kind, integer, slot, local, guardMiss);
			} else {
				method.visitInsn(kind == INTEGRAL ? LCONST_0 : DCONST_0);
				method.visitVarInsn(kind.storeOpcode(), slot.value());
				if (slot.reference() >= 0) {
					method.visitInsn(ACONST_NULL);
					method.visitVarInsn(ASTORE, slot.reference());
				}
			}
			if (writes.containsKey(name)) slot.markDirty(method, false);
		}
		return new Code(emitter, slots, iteration, advance, leave, new LinkedHashMap<>());
	}

	void emitBody(Code code) {
		for (Statement statement : body) {
			statement.emit(code);
		}
	}

	void jumpIfFalse(Code code, Label failed) {
		assert condition != null;
		condition.jumpIfFalse(code, failed);
	}

	record Code(Emitter emitter, Map<String, Slot> slots, int iteration, Label advance, Label leave, Map<Label, TriggerItem> exits) {
		MethodVisitor method() {
			return emitter.method();
		}

		void value(NumericExpression expression) {
			expression.emit(emitter, slots, iteration, null);
		}

		void reference(NumericExpression value) {
			if (value instanceof Constant constant) {
				emitter.loadConstant(constant.value(), Object.class);
			} else {
				Read read = (Read) value;
				slots.get(read.name()).loadBox(method(), read.kind());
			}
		}

		int exitCursor() {
			/*
			 * TriggerItem cursor = null; // Normal completion, including exit loop.
			 * // Each side-exit label instead stores its exact original TriggerItem.
			 * // Both paths join before the caller writes back dirty slots.
			 */
			MethodVisitor method = method();
			Label spill = new Label();
			method.visitInsn(ACONST_NULL);
			method.visitJumpInsn(GOTO, spill);
			for (var exit : exits.entrySet()) {
				method.visitLabel(exit.getKey());
				emitter.loadConstant(exit.getValue(), TriggerItem.class);
				method.visitJumpInsn(GOTO, spill);
			}
			method.visitLabel(spill);
			int cursor = emitter.newLocal(ITEM);
			method.visitVarInsn(ASTORE, cursor);
			return cursor;
		}
	}

	private interface Statement {
		void emit(Code code);
	}

	private record Assignment(TriggerItem effect, String target, NumericExpression value) implements Statement {
		@Override
		public void emit(Code code) {
			Slot slot = code.slots.get(target);
			MethodVisitor method = code.method();
			Label overflow = new Label();
			if (value.needsExit()) code.exits.put(overflow, effect);
			/*
			 * Example for a slot with a retained box.
			 * value = evaluatedRhs; // A failing arithmetic check exits before this store.
			 * boxed = directReadOrLiteral ? sourceReference : null;
			 * dirty = true;
			 */
			value.emit(code.emitter, code.slots, code.iteration, overflow);
			method.visitVarInsn(value.kind().storeOpcode(), slot.value());
			if (slot.reference() >= 0) {
				if (value instanceof Read || value instanceof Constant) {
					code.reference(value);
				} else {
					method.visitInsn(ACONST_NULL);
				}
				method.visitVarInsn(ASTORE, slot.reference());
			}
			slot.markDirty(method, true);
		}
	}

	private record NumericCondition(NumericExpression left, NumericExpression right, Relation failed,
									boolean longs, boolean identity) {
		void jumpIfFalse(Code code, Label target) {
			MethodVisitor method = code.method();
			if (longs) {
				code.value(left);
				code.value(right);
				ComparisonOptimizations.jumpIfLongRelation(method, failed, target);
				return;
			}
			/*
			 * Example for two Double operands when the comparison observes boxed identity.
			 * Relation actual = Double.isNaN(leftValue) && leftBox == rightBox
			 *     ? Relation.EQUAL : DefaultComparators.compareDoubles(leftValue, rightValue);
			 * if (failed.isImpliedBy(actual)) {
			 *     break condition;
			 * }
			 */
			Label done = new Label();
			if (identity) {
				// Only NaN distinguishes boxed identity from the numeric comparator
				Label ordinary = new Label();
				code.value(left);
				method.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "isNaN", "(D)Z", false);
				method.visitJumpInsn(IFEQ, ordinary);
				code.reference(left);
				code.reference(right);
				method.visitJumpInsn(IF_ACMPEQ, failed.isImpliedBy(Relation.EQUAL) ? target : done);
				method.visitLabel(ordinary);
			}
			Type relation = Type.getType(Relation.class);
			code.emitter.loadConstant(failed, Relation.class);
			code.value(left);
			if (left.kind() == INTEGRAL) {
				method.visitInsn(L2D);
			}
			code.value(right);
			if (right.kind() == INTEGRAL) {
				method.visitInsn(L2D);
			}
			method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(DefaultComparators.class), "compareDoubles",
				Type.getMethodDescriptor(relation, Type.DOUBLE_TYPE, Type.DOUBLE_TYPE), false);
			method.visitMethodInsn(INVOKEVIRTUAL, relation.getInternalName(), "isImpliedBy",
				Type.getMethodDescriptor(Type.BOOLEAN_TYPE, relation), false);
			method.visitJumpInsn(IFNE, target);
			method.visitLabel(done);
		}
	}

	private record Arm(@Nullable NumericCondition test, SecConditional section, List<Statement> items,
	                   TriggerItem join) {
		void emit(Code code) {
			if (items.isEmpty()) return;
			TriggerItem tail = section.skatalystItems().getLast();
			Label ready = new Label();
			/*
			 * if (tail instanceof TriggerSection || tail.getActualNext() != join) {
			 *     section.skatalystEnterBranch();
			 * }
			 * The compiler knows when the tail is a section and emits the enter call directly.
			 * Entering the branch also retargets any child items of a section tail.
			 */
			if (!(tail instanceof TriggerSection)) {
				code.emitter.loadActualNext(tail);
				code.emitter.loadConstant(join, TriggerItem.class);
				code.method().visitJumpInsn(IF_ACMPEQ, ready);
			}
			code.emitter.loadConstant(section, SecConditional.class);
			code.method().visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(SecConditional.class),
				"skatalystEnterBranch", Type.getMethodDescriptor(ITEM), false);
			code.method().visitInsn(POP);
			code.method().visitLabel(ready);
			for (Statement statement : items) {
				statement.emit(code);
			}
		}
	}

	private record Branch(List<Arm> arms) implements Statement {
		@Override
		public void emit(Code code) {
			Label done = new Label();
			for (Arm arm : arms) {
				Label skip = new Label();
				if (arm.test != null) {
					arm.test.jumpIfFalse(code, skip);
				}
				arm.emit(code);
				if (arm != arms.getLast()) {
					code.method().visitJumpInsn(GOTO, done);
				}
				code.method().visitLabel(skip);
			}
			code.method().visitLabel(done);
		}
	}
}
