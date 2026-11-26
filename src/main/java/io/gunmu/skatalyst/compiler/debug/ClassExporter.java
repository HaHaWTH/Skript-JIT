package io.gunmu.skatalyst.compiler.debug;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.config.Config;
import ch.njol.skript.lang.ReturnableTrigger;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Optional diagnostic export of the hidden classes linked by Skatalyst.
 */
@ApiStatus.Internal
public final class ClassExporter {

	static final long DISABLED = -1L;
	private static final ClassExporter DEFAULT = new ClassExporter(
		Path.of(".skatalyst.out"), Boolean.getBoolean("skatalyst.debug.export"));
	private static final Set<Class<?>> BOXED_CONSTANTS = Set.of(
		Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, Character.class);
	private static final Class<?> IMMUTABLE_LIST = List.of().getClass();
	private static final Class<?> SMALL_IMMUTABLE_LIST = List.of(0).getClass();

	private final Path root;
	private volatile long exportGeneration;
	private long nextCompilation;

	ClassExporter(Path root, boolean enabled) {
		this.root = root;
		exportGeneration = enabled ? 0 : DISABLED;
	}

	/**
	 * Captures the export generation when a compilation is requested.
	 */
	public static long generation() {
		return DEFAULT.currentGeneration();
	}

	public static @Nullable Compilation begin(Trigger trigger, long generation) {
		return DEFAULT.open(trigger, generation);
	}

	/**
	 * Clears the previous export and retires its in-flight compilations.
	 */
	public static void reset() {
		DEFAULT.resetExports();
	}

	long currentGeneration() {
		return exportGeneration;
	}

	@Nullable Compilation open(Trigger trigger, long generation) {
		if (generation == DISABLED) {
			return null;
		}
		synchronized (this) {
			if (generation != exportGeneration) {
				return null;
			}
			Path directory = root.resolve(String.format(Locale.ROOT, "%06d", nextCompilation++));
			return new Compilation(this, generation, directory, trigger);
		}
	}

	void resetExports() {
		if (exportGeneration == DISABLED) {
			return;
		}
		synchronized (this) {
			exportGeneration++;
			try {
				if (!Files.notExists(root, NOFOLLOW_LINKS)) {
					requireDirectory(root);
					deleteDirectory(root);
				}
			} catch (IOException | DirectoryIteratorException | SecurityException failure) {
				warn("clear " + root, failure);
			}
		}
	}

	private synchronized void export(Compilation compilation, byte[] classBytes, List<Object> classData, boolean scalarFunction) {
		if (compilation.generation != exportGeneration) {
			return;
		}
		String stem = scalarFunction ? "scalar-function" : String.format(Locale.ROOT, "chunk-%02d", compilation.nextChunk++);
		try {
			if (Files.notExists(root, NOFOLLOW_LINKS)) {
				Files.createDirectory(root);
			}
			requireDirectory(root);
			if (compilation.directoryCreated) {
				requireDirectory(compilation.directory);
			} else {
				Files.createDirectory(compilation.directory);
				compilation.directoryCreated = true;
			}
			writePair(compilation.directory, stem, classBytes, compilation.describe(classData));
		} catch (IOException | SecurityException failure) {
			warn("write " + compilation.directory.resolve(stem), failure);
		}
	}

	private static void writePair(Path directory, String stem, byte[] classBytes, String summary) throws IOException {
		Path classFile = directory.resolve(stem + ".class");
		Path dataFile = directory.resolve(stem + ".class-data.txt");
		boolean classCreated = false;
		boolean dataCreated = false;
		try {
			try (var binary = Files.newOutputStream(classFile, WRITE, CREATE_NEW)) {
				classCreated = true;
				binary.write(classBytes);
			}
			try (var text = Files.newBufferedWriter(dataFile, WRITE, CREATE_NEW)) {
				dataCreated = true;
				text.write(summary);
			}
		} catch (IOException | SecurityException failure) {
			if (dataCreated) {
				deletePartial(dataFile, failure);
			}
			if (classCreated) {
				deletePartial(classFile, failure);
			}
			throw failure;
		}
	}

	private static void deletePartial(Path file, Exception failure) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException | SecurityException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	private static boolean isOrdinaryDirectory(Path path) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
		// Windows directory junctions have both the directory and reparse-point attributes.
		return attributes.isDirectory() && !attributes.isOther();
	}

	private static void requireDirectory(Path directory) throws IOException {
		if (!isOrdinaryDirectory(directory)) {
			throw new IOException("Expected an ordinary directory at " + directory);
		}
	}

	private static void deleteDirectory(Path directory) throws IOException {
		try (var children = Files.newDirectoryStream(directory)) {
			for (Path child : children) {
				if (isOrdinaryDirectory(child)) {
					deleteDirectory(child);
				} else {
					Files.delete(child);
				}
			}
		}
		Files.delete(directory);
	}

	private static void warn(String action, Exception failure) {
		Skript.warning("Skatalyst could not " + action + ": " + failure.getMessage());
	}

	public static final class Compilation {

		private final ClassExporter exporter;
		private final long generation;
		private final Path directory;
		private final String heading;
		private final Map<TriggerItem, String> triggerPaths = new IdentityHashMap<>();
		private boolean directoryCreated;
		private int nextChunk;

		private Compilation(ClassExporter exporter, long generation, Path directory, Trigger trigger) {
			this.exporter = exporter;
			this.generation = generation;
			this.directory = directory;
			heading = describeTrigger(trigger);
			indexTriggerPaths(trigger, "trigger");
		}

		public void write(byte[] classBytes, List<Object> classData, boolean scalarFunction) {
			exporter.export(this, classBytes, classData, scalarFunction);
		}

		private void indexTriggerPaths(TriggerItem item, String path) {
			if (triggerPaths.putIfAbsent(item, path) == null && item instanceof TriggerSection section) {
				List<TriggerItem> children = section.skatalystItems();
				for (int i = 0; i < children.size(); i++) {
					indexTriggerPaths(children.get(i), path + '[' + i + ']');
				}
			}
		}

		private String describe(List<Object> classData) {
			var text = new StringBuilder(heading).append("Class data (index -> type and value)\n");
			for (int i = 0; i < classData.size(); i++) {
				Object value = classData.get(i);
				Class<?> type = value.getClass();
				text.append(i).append(" -> ").append(type.getName());
				String simpleValue = describeSimpleValue(value);
				if (simpleValue != null) {
					text.append(" = ").append(simpleValue);
				} else if (type == IMMUTABLE_LIST || type == SMALL_IMMUTABLE_LIST) {
					describeList(text, (List<?>) value);
				} else if (value instanceof MethodHandle handle) {
					text.append(" = ").append(handle.type().toMethodDescriptorString());
				} else if (type == ClassInfo.class) {
					text.append(" = ClassInfo<").append(((ClassInfo<?>) value).getC().getName()).append('>');
				} else if (value instanceof TriggerItem item) {
					String path = triggerPaths.get(item);
					if (path != null) {
						text.append(" @ ").append(path);
					}
				}
				text.append('\n');
			}
			return text.toString();
		}
	}

	private static String describeTrigger(Trigger trigger) {
		Class<?> type = trigger.getClass();
		// Addon subclasses can override metadata getters, which the compile thread must not invoke.
		if (type != Trigger.class && type != ReturnableTrigger.class) {
			return "Trigger <" + type.getName() + ">\n";
		}
		var script = trigger.getScript();
		Config config = script == null ? null : script.getConfig();
		String fileName = config != null && config.getClass() == Config.class ? config.getFileName() : null;
		String name = trigger.getName();
		int line = trigger.getLineNumber();
		return "Trigger " + (name == null ? '<' + type.getName() + '>' : quoted(name)) + '\n'
			+ (fileName == null ? "" : "Script " + quoted(fileName) + '\n')
			+ (line < 0 ? "" : "Line " + line + '\n');
	}

	private static @Nullable String describeSimpleValue(Object value) {
		if (value instanceof String string) {
			return quoted(string);
		}
		if (BOXED_CONSTANTS.contains(value.getClass())) {
			return value.toString();
		}
		return value instanceof Enum<?> constant ? constant.name() : null;
	}

	private static void describeList(StringBuilder text, List<?> values) {
		text.append(" = [");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				text.append(", ");
			}
			Object value = values.get(i);
			if (value == null) {
				text.append("null");
				continue;
			}
			String item = describeSimpleValue(value);
			if (item == null) {
				text.append('<').append(value.getClass().getName()).append('>');
			} else {
				text.append(item);
			}
		}
		text.append(']');
	}

	private static String quoted(String text) {
		return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
	}
}
