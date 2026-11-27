package ch.njol.skript.lang.il;

import ch.njol.skript.Skript;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.docs.Origin;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;
import org.skriptlang.skript.registration.SyntaxRegistry.Key;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static ch.njol.skript.lang.il.IlEnvironment.append;
import static ch.njol.skript.lang.il.IlEnvironment.newDigest;
import static ch.njol.skript.lang.il.IlScriptCache.hex;

/** A parse environment with registrations bound to one consistent registry revision. */
final class IlSyntaxEnvironment {

	private static final List<Key<?>> SYNTAX_KEYS = List.of(SyntaxRegistry.STRUCTURE, SyntaxRegistry.SECTION,
		SyntaxRegistry.STATEMENT, SyntaxRegistry.EFFECT, SyntaxRegistry.CONDITION, SyntaxRegistry.EXPRESSION);
	private static final String PROCESS_ID = UUID.randomUUID().toString();
	private static final AtomicLong REGISTRY_REPLACEMENTS = new AtomicLong();
	private static volatile @Nullable IlSyntaxEnvironment cached;

	private final SyntaxRegistry registry;
	private final long revision;
	private final long replacement;
	private final IlEnvironment.ParseSettings settings;
	private final String fingerprint;
	private final Ordering ordering;
	private final IlRegistration[] definitionsById;
	private final Map<SyntaxInfo<?>, List<Key<?>>> memberships = new IdentityHashMap<>();
	private final Map<SyntaxInfo<?>, IlRegistration> byInfo = new IdentityHashMap<>();
	private final Map<String, IlRegistration> byDescriptor = new HashMap<>();
	private final Map<IlRegistration, Integer> ids = new IdentityHashMap<>();

	/** Each key contains one-based definition IDs in matching order. Opaque registrations use negative IDs. */
	record Ordering(String fingerprint, int[][] keys) { }

	private IlSyntaxEnvironment(SyntaxRegistry registry, SyntaxRegistry.Snapshot snapshot, long replacement,
								IlEnvironment.ParseSettings settings, String nonSyntaxFingerprint) {
		this.registry = registry;
		this.replacement = replacement;
		this.settings = settings;
		revision = snapshot.revision();
		var syntaxes = snapshot.syntaxes();
		for (Key<?> key : SYNTAX_KEYS) {
			for (SyntaxInfo<?> info : syntaxes.get(key)) {
				memberships.computeIfAbsent(info, ignored -> new ArrayList<>()).add(key);
			}
		}

		var definitions = new IdentityHashMap<SyntaxInfo<?>, Definition>();
		var frequencies = new HashMap<String, Integer>();
		var owners = new IdentityHashMap<ClassLoader, String>();
		for (var membership : memberships.entrySet()) {
			SyntaxInfo<?> info = membership.getKey();
			Definition definition = define(info, membership.getValue(), owners);
			definitions.put(info, definition);
			frequencies.merge(definition.descriptor(), 1, Integer::sum);
		}

		List<String> descriptors = frequencies.keySet().stream().sorted().toList();
		var definitionIds = new HashMap<String, Integer>();
		for (int index = 0; index < descriptors.size(); index++) {
			definitionIds.put(descriptors.get(index), index + 1);
		}
		definitionsById = new IlRegistration[descriptors.size()];
		for (var entry : definitions.entrySet()) {
			SyntaxInfo<?> info = entry.getKey();
			Definition definition = entry.getValue();
			int id = definitionIds.get(definition.descriptor());
			IlRegistration representative = definitionsById[id - 1];
			if (representative == null) {
				representative = new IlRegistration(definition.descriptor(), info);
				definitionsById[id - 1] = representative;
			}
			if (definition.bindable() && frequencies.get(definition.descriptor()) == 1) {
				byInfo.put(info, representative);
				byDescriptor.put(representative.descriptor(), representative);
				if (info.usesDefaultFactory()) ids.put(representative, id);
			}
		}

		MessageDigest stable = newDigest();
		append(stable, nonSyntaxFingerprint);
		for (String descriptor : descriptors) {
			append(stable, descriptor);
			append(stable, Integer.toString(frequencies.get(descriptor)));
		}
		fingerprint = hex(stable.digest());

		MessageDigest ordered = newDigest();
		var opaqueIds = new IdentityHashMap<SyntaxInfo<?>, Integer>();
		int[][] orderedKeys = new int[SYNTAX_KEYS.size()][];
		for (int keyIndex = 0; keyIndex < SYNTAX_KEYS.size(); keyIndex++) {
			Key<?> key = SYNTAX_KEYS.get(keyIndex);
			var registrations = syntaxes.get(key);
			append(ordered, key.name());
			append(ordered, Integer.toString(registrations.size()));
			int[] order = orderedKeys[keyIndex] = new int[registrations.size()];
			for (int index = 0; index < order.length; index++) {
				SyntaxInfo<?> info = registrations.get(index);
				String descriptor = definitions.get(info).descriptor();
				int id = definitionIds.get(descriptor);
				boolean opaque = !ids.containsKey(byInfo.get(info));
				order[index] = opaque ? -id : id;
				append(ordered, descriptor);
				if (opaque) {
					int opaqueId = opaqueIds.computeIfAbsent(info, ignored -> opaqueIds.size());
					append(ordered, PROCESS_ID);
					append(ordered, Long.toString(revision));
					append(ordered, Long.toString(replacement));
					append(ordered, Integer.toString(opaqueId));
				}
			}
		}
		ordering = new Ordering(hex(ordered.digest()), orderedKeys);
	}

	static @Nullable IlSyntaxEnvironment current() {
		IlSyntaxEnvironment environment = cached;
		if (environment != null && environment.active()) return environment;
		synchronized (IlSyntaxEnvironment.class) {
			environment = cached;
			if (environment != null && environment.active()) return environment;
			long replacement = REGISTRY_REPLACEMENTS.get();
			SyntaxRegistry registry = Skript.instance().syntaxRegistry();
			var settings = IlEnvironment.ParseSettings.capture();
			var snapshot = registry.syntaxSnapshot(SYNTAX_KEYS);
			if (snapshot == null) return null;
			String nonSyntaxFingerprint = IlEnvironment.nonSyntaxFingerprint(settings);
			if (nonSyntaxFingerprint == null) return null;
			environment = new IlSyntaxEnvironment(registry, snapshot, replacement, settings, nonSyntaxFingerprint);
			if (!environment.active()) return null;
			cached = environment;
			return environment;
		}
	}

	boolean active() {
		return REGISTRY_REPLACEMENTS.get() == replacement && registry.syntaxRevision() == revision
			&& settings.matchesCurrentValues();
	}

	static void registryReplaced() {
		REGISTRY_REPLACEMENTS.incrementAndGet();
		cached = null;
	}

	String fingerprint() {
		return fingerprint;
	}

	Ordering ordering() {
		return ordering;
	}

	@Nullable IlRegistration binding(SyntaxInfo<?> info) {
		return byInfo.get(info);
	}

	@Nullable IlRegistration binding(String descriptor) {
		return byDescriptor.get(descriptor);
	}

	/** Compares possible predecessors, ignoring only candidates rejected by the parser's keyword filter. */
	boolean precedesSame(IlRegistration registration, String expr, Ordering recorded, @Nullable Key<?> sourceKey) {
		Integer selected = ids.get(registration);
		if (selected == null || recorded.keys().length != SYNTAX_KEYS.size()) return false;
		String lower = null;
		List<Key<?>> sources = memberships.get(registration.info());
		if (sourceKey != null && !sources.contains(sourceKey)) return false;
		for (Key<?> key : sources) {
			if (sourceKey != null && !sourceKey.equals(key)) continue;
			int keyIndex = SYNTAX_KEYS.indexOf(key);
			int[] previous = recorded.keys()[keyIndex];
			int[] current = ordering.keys()[keyIndex];
			int oldIndex = 0;
			int newIndex = 0;
			int possibleOldIndex = -1;
			while (true) {
				if (oldIndex == previous.length || newIndex == current.length) return false;
				int oldId = previous[oldIndex];
				int newId = current[newIndex];
				if (oldId > 0 && oldId == newId) {
					if (oldId == selected) break;
					oldIndex++;
					newIndex++;
					continue;
				}
				if (lower == null) lower = expr.toLowerCase(Locale.ENGLISH);
				if (oldId != selected && oldIndex != possibleOldIndex) {
					if (impossible(oldId, lower)) {
						oldIndex++;
						if (oldId == newId) newIndex++;
						continue;
					}
					possibleOldIndex = oldIndex;
				}
				if (oldId == newId || newId == selected || !impossible(newId, lower)) return false;
				newIndex++;
			}
		}
		return true;
	}

	private boolean impossible(int id, String lower) {
		if (id == 0 || id < -definitionsById.length || id > definitionsById.length) return false;
		IlRegistration representative = definitionsById[Math.abs(id) - 1];
		for (int index = 0; index < representative.patternCount(); index++) {
			if (representative.pattern(index).matchesKeywords(lower)) return false;
		}
		return true;
	}

	private record Definition(String descriptor, boolean bindable) { }

	private static Definition define(SyntaxInfo<?> info, List<Key<?>> keys, Map<ClassLoader, String> owners) {
		MessageDigest digest = newDigest();
		append(digest, info.getClass().getName());
		boolean bindable = info.getClass().getClassLoader() == Skript.class.getClassLoader();
		bindable &= appendType(digest, info.type(), owners);
		Origin origin = info.origin();
		append(digest, origin.name());
		if (origin instanceof Origin.AddonOrigin addonOrigin) {
			append(digest, "addon");
			bindable &= appendType(digest, addonOrigin.addon().source(), owners);
		} else {
			append(digest, "unknown");
		}
		append(digest, Integer.toString(keys.size()));
		for (Key<?> key : keys) append(digest, key.name());
		if (info instanceof SyntaxInfo.Expression<?, ?> expression) {
			bindable &= appendType(digest, expression.returnType(), owners);
		} else if (info instanceof SyntaxInfo.Structure<?> structure) {
			append(digest, structure.nodeType().name());
		}
		append(digest, Integer.toString(info.patterns().size()));
		for (String pattern : info.patterns()) append(digest, pattern);
		return new Definition(hex(digest.digest()), bindable);
	}

	private static boolean appendType(MessageDigest digest, Class<?> type, Map<ClassLoader, String> owners) {
		append(digest, type.getName());
		while (type.isArray()) type = type.getComponentType();
		Class<?> declared = type;
		String owner = owners.computeIfAbsent(type.getClassLoader(), ignored -> owner(declared));
		append(digest, owner);
		return !owner.isEmpty();
	}

	private static String owner(Class<?> type) {
		ClassLoader loader = type.getClassLoader();
		if (loader == null || loader == ClassLoader.getPlatformClassLoader()) return "java";
		if (loader == Skript.class.getClassLoader()) return "skript";
		if (loader == Bukkit.class.getClassLoader()) return "server";
		try {
			JavaPlugin plugin = JavaPlugin.getProvidingPlugin(type);
			return "plugin:" + plugin.getName();
		} catch (IllegalArgumentException e) {
			return "";
		}
	}
}
