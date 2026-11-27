package ch.njol.skript.lang.il;

import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.ScriptData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * The in-memory cache of a single script file.
 */
final class IlScriptCache implements ScriptData {

	static final int NO_CHANGE = 0;
	static final int ADDED = 1;
	static final int CONFLICT = 2;

	private final File file;
	private final String sourceHash;
	private final IlSyntaxEnvironment environment;
	private final Map<String, IlManager.Entry> entries;

	/**
	 * Keys recorded during the current load; they must not be replayed before the load has finished.
	 */
	private final java.util.Set<String> freshKeys = new java.util.HashSet<>();
	private boolean dirty;

	private IlScriptCache(File file, String sourceHash, IlSyntaxEnvironment environment,
	                      Map<String, IlManager.Entry> entries) {
		this.file = file;
		this.sourceHash = sourceHash;
		this.environment = environment;
		this.entries = entries;
	}

	/**
	 * Loads the cache for a script file, returning an empty cache if the file is missing or does not match the
	 * current source/environment.
	 */
	static IlScriptCache load(Path sourcePath, String sourceHash, IlSyntaxEnvironment environment) {
		Path cacheDirectory = IlManager.cacheDirectory();
		File cacheFile = null;
		Map<String, IlManager.Entry> entries = new HashMap<>();
		if (cacheDirectory != null) {
			try {
				Files.createDirectories(cacheDirectory);
				String pathHash = hash(sourcePath.toAbsolutePath().normalize().toString());
				cacheFile = cacheDirectory.resolve(sourceHash + "-" + pathHash.substring(0, 16) + ".il").toFile();
				Map<String, IlManager.Entry> loaded = IlFile.load(cacheFile, sourceHash, environment);
				if (loaded != null)
					entries = loaded;
			} catch (Exception e) {
				IlManager.debug("Could not read IL cache for " + sourcePath + ": " + e);
			}
		}
		return new IlScriptCache(cacheFile, sourceHash, environment, entries);
	}

	IlSyntaxEnvironment environment() {
		return environment;
	}

	/**
	 * @return the entry to replay for this key, or null if there is none.
	 */
	@Nullable
	IlManager.Entry get(String key) {
		if (freshKeys.contains(key))
			return null;
		return entries.get(key);
	}

	int entryCount() {
		return entries.size();
	}

	void invalidate(String key) {
		entries.remove(key);
		dirty = true;
	}

	/** Keeps confirmed entries on the current ordering if a later write is needed. */
	void refresh(String key, IlManager.Entry entry) {
		entries.put(key, entry);
	}

	void markDirty() {
		dirty = true;
	}

	/**
	 * Stores an entry.
	 * If the same key was already recorded with a different result,
	 * means that the entry has conflictions so that it is never replayed.
	 */
	int put(String key, IlManager.Entry entry) {
		freshKeys.add(key);
		IlManager.Entry existing = entries.get(key);
		if (existing == null) {
			entries.put(key, entry);
			return ADDED;
		}
		if (existing.equals(entry))
			return NO_CHANGE;
		entries.put(key, new IlManager.Entry(IlManager.STATE_UNSAFE, null,
			entry.flags(), entry.parseContext(), entry.expr()));
		return CONFLICT;
	}

	/**
	 * @return whether anything had to be written.
	 */
	boolean saveIfDirty() throws IOException {
		if (!dirty || file == null)
			return false;
		IlFile.save(file, sourceHash, environment.fingerprint(), entries);
		dirty = false;
		return true;
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			return hex(digest);
		} catch (Exception e) {
			return "0000000000000000";
		}
	}

	static String hex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
			builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		return builder.toString();
	}

}
