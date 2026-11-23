package io.gunmu.skatalyst.compiler;

import ch.njol.skript.lang.TriggerItem;
import org.bukkit.event.Event;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Set;

@ApiStatus.Internal
public final class CompiledCode {

	private final IdentityHashMap<TriggerItem, Entry> entries;
	private final Set<TriggerItem> optimized;
	private final @Nullable DirectFunction function;

	CompiledCode(IdentityHashMap<TriggerItem, Entry> entries, Set<TriggerItem> optimized) {
		this(entries, optimized, null);
	}

	private CompiledCode(IdentityHashMap<TriggerItem, Entry> entries, Set<TriggerItem> optimized, @Nullable DirectFunction function) {
		this.entries = entries;
		this.optimized = optimized;
		this.function = function;
	}

	CompiledCode withFunction(DirectFunction function) {
		return new CompiledCode(entries, optimized, function);
	}

	public @Nullable DirectFunction directFunction() {
		return function;
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public @Nullable Entry entry(TriggerItem item) {
		return entries.get(item);
	}

	public boolean optimized(TriggerItem item) {
		return optimized.contains(item);
	}

	public record Entry(Chunk chunk, int id) {
		public @Nullable TriggerItem walk(Event event) {
			return chunk.walk(id, event);
		}
	}

	public interface Chunk {
		@Nullable TriggerItem walk(int entry, Event event);
	}

	public static final class WalkFailure extends RuntimeException {

		private final TriggerItem item;

		WalkFailure(TriggerItem item, Throwable failure) {
			super(null, failure, false, false);
			this.item = item;
		}

		public TriggerItem item() {
			return item;
		}

		public Throwable failure() {
			return getCause();
		}
	}
}
