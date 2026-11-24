package io.gunmu.skatalyst.addon;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.SyntaxElement;
import ch.njol.skript.lang.TriggerItem;
import io.gunmu.skatalyst.addon.AddonRegistry.Activity;
import io.gunmu.skatalyst.addon.AddonRegistry.Match;
import io.gunmu.skatalyst.addon.compat.SkBeeProfile;
import io.gunmu.skatalyst.api.NumericIntrinsic;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.registration.SyntaxInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The addon syntax actually selected while parsing an individual executable item.
 */
@ApiStatus.Internal
public final class ParsedBinding {

	private final Activity @Nullable [] original;
	private final Map<Expression<?>, NumericCall> numeric;
	private final boolean fullyReplaceable;

	private ParsedBinding(Activity @Nullable [] original, Map<Expression<?>, NumericCall> numeric, boolean fullyReplaceable) {
		this.original = original;
		this.numeric = Collections.unmodifiableMap(numeric);
		this.fullyReplaceable = fullyReplaceable;
	}

	public boolean hasOriginalContract() {
		return original != null;
	}

	public boolean preservesLocals() {
		if (original == null) {
			return false;
		}
		for (Activity dependency : original) {
			if (!dependency.isActive()) {
				return false;
			}
		}
		return true;
	}

	public boolean hasNumericIntrinsics() {
		return !numeric.isEmpty();
	}

	public Collection<NumericCall> numericIntrinsics() {
		return numeric.values();
	}

	public @Nullable NumericCall numericIntrinsic(Expression<?> expression) {
		return numeric.get(expression);
	}

	public boolean coversExternalSyntax(Collection<NumericCall> used) {
		return fullyReplaceable && used.containsAll(numeric.values());
	}

	public static final class NumericCall {
		private volatile @Nullable NumericIntrinsic intrinsic;
		private final Activity provider;
		private final Activity syntax;

		private NumericCall(NumericIntrinsic intrinsic, Activity provider, Activity syntax) {
			this.intrinsic = intrinsic;
			this.provider = provider;
			this.syntax = syntax;
			provider.attach(this);
			syntax.attach(this);
		}

		public @Nullable NumericIntrinsic intrinsic() {
			return intrinsic;
		}

		void detach() {
			intrinsic = null;
		}

		public boolean isActive() {
			return provider.isActive() && syntax.isActive();
		}
	}

	/**
	 * Candidate observations are collected during parsing and committed only for the final executable item.
	 */
	public static final class Collector {
		private final List<Observation> observations = new ArrayList<>();

		public int mark() {
			return observations.size();
		}

		public void restore(int mark) {
			if (mark < observations.size()) {
				observations.subList(mark, observations.size()).clear();
			}
		}

		public boolean observeUnknown(Class<?> implementation) {
			if (!AddonRegistry.isExternal(implementation)) {
				return false;
			}
			observeUnknown();
			return true;
		}

		public void observeUnknown() {
			observations.add(Observation.UNKNOWN);
		}

		public boolean observe(SyntaxInfo<?> info, SyntaxElement selected, SyntaxElement element,
							   int pattern, ParseResult parseResult) {
			if (!AddonRegistry.isExternal(info.type()) && !AddonRegistry.isExternal(element.getClass())) {
				return false;
			}
			Match match = selected == element ? AddonRegistry.lookup(info, element) : null;
			observations.add(match == null ? Observation.UNKNOWN : new Observation(match, element, pattern, parseResult));
			return true;
		}

		public @Nullable ParsedBinding finish(TriggerItem item) {
			if (observations.isEmpty()) {
				return null;
			}
			var dependencies = new LinkedHashSet<Activity>();
			Map<Expression<?>, NumericCall> numeric = new IdentityHashMap<>();
			boolean preservesLocals = true;
			boolean fullyReplaceable = true;
			boolean hasRoot = false;
			for (Observation observation : observations) {
				hasRoot |= observation.element == item;
				Match match = observation.match;
				if (match == null) {
					preservesLocals = false;
					fullyReplaceable = false;
					continue;
				}
				if (match.preservesLocals() || (match.skBeeGetter() && observation.isSkBeeGetter(item))) {
					dependencies.add(match.provider());
					dependencies.add(match.syntax());
				} else {
					preservesLocals = false;
				}
				if (observation.element instanceof Expression<?> expression) {
					NumericIntrinsic intrinsic = match.intrinsic(expression);
					if (intrinsic != null) {
						numeric.put(expression, new NumericCall(intrinsic, match.provider(), match.syntax()));
						continue;
					}
				}
				fullyReplaceable = false;
			}
			if (AddonRegistry.isExternal(item.getClass()) && !hasRoot) {
				preservesLocals = false;
				fullyReplaceable = false;
			}
			if (!preservesLocals && numeric.isEmpty()) {
				return null;
			}
			return new ParsedBinding(preservesLocals ? dependencies.toArray(Activity[]::new) : null, numeric, fullyReplaceable);
		}
	}

	private record Observation(
		@Nullable Match match, @Nullable SyntaxElement element, int pattern, @Nullable ParseResult parseResult) {
		private static final Observation UNKNOWN = new Observation(null, null, -1, null);

		private boolean isSkBeeGetter(TriggerItem item) {
			assert parseResult != null && element != null;
			return SkBeeProfile.isGetter(item, element, pattern, parseResult);
		}
	}
}
