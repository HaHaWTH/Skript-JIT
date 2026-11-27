package ch.njol.skript.lang.il;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.registrations.Classes;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Computes the identity of the current parse environment.
 * <p>
 * Cached parse results are only valid if every input that can influence parsing is the same as when they were
 * recorded: the Skript build itself, installed addons (they register syntaxes and converters), the registered
 * syntax and class registries, the active language, the server version and the config options that affect
 * parsing.
 */
@ApiStatus.Internal
public final class IlEnvironment {

	private IlEnvironment() { }

	/**
	 * @return the SHA-256 of the file's content, or {@code null} if it could not be read.
	 */
	public static @Nullable String hashFile(File file) {
		try (InputStream in = Files.newInputStream(file.toPath())) {
			MessageDigest digest = newDigest();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0)
				digest.update(buffer, 0, read);
			return IlScriptCache.hex(digest.digest());
		} catch (Exception e) {
			return null;
		}
	}

	public static String computeFingerprint() {
		IlSyntaxEnvironment environment = IlSyntaxEnvironment.current();
		return environment == null ? "unavailable" : environment.fingerprint();
	}

	static @Nullable String nonSyntaxFingerprint(ParseSettings settings) {
		try {
			MessageDigest digest = newDigest();
			append(digest, "skript", Skript.getVersion().toString());
			append(digest, "mc", Bukkit.getBukkitVersion());
			append(digest, "api", Bukkit.getBukkitVersion());
			append(digest, "language", settings.language);

			// the plugin jar identity: changes on every Skript build even if the version string does not
			append(digest, "jar", pluginCodeIdentity(Skript.class));

			// config options that can change parse results
			append(digest, "simplify", Boolean.toString(settings.simplify));
			append(digest, "missing-and-or", Boolean.toString(settings.disableMissingAndOrWarnings));
			append(digest, "case-sensitive", Boolean.toString(settings.caseSensitive));
			append(digest, "case-insensitive-vars", Boolean.toString(settings.caseInsensitiveVariables));
			append(digest, "functions-before-defs", Boolean.toString(settings.allowFunctionsBeforeDefinitions));
			append(digest, "event-priority", settings.eventPriority.name());
			append(digest, "number-accuracy", Integer.toString(settings.numberAccuracy));

			// plugins (addons register syntaxes, converters, class infos and event values)
			Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
			List<String> pluginIdentities = new ArrayList<>(plugins.length);
			for (Plugin plugin : plugins) {
				pluginIdentities.add(plugin.getName() + "@" + plugin.getPluginMeta().getVersion()
					+ "@" + pluginCodeIdentity(plugin.getClass()));
			}
			pluginIdentities.sort(String::compareTo);
			append(digest, "plugins", Integer.toString(pluginIdentities.size()));
			for (String plugin : pluginIdentities) append(digest, plugin);

			// class infos (code names are stable and are what the IL stores)
			Collection<ClassInfo<?>> classInfos = Classes.getClassInfos();
			List<String> classInfoNames = classInfos.stream()
				.map(ClassInfo::getCodeName)
				.sorted()
				.collect(Collectors.toList());
			append(digest, "classinfos", Integer.toString(classInfoNames.size()));
			for (String classInfo : classInfoNames) append(digest, classInfo);

			return IlScriptCache.hex(digest.digest());
		} catch (Exception e) {
			// if the fingerprint cannot be computed the cache is simply unusable
			return null;
		}
	}

	private static String pluginCodeIdentity(Class<?> type) {
		try {
			var location = type.getProtectionDomain().getCodeSource().getLocation();
			if (location == null)
				return "unknown";
			File source = new File(location.toURI());
			if (source.isFile())
				return source.length() + "@" + source.lastModified();
			// In a development classes directory, identify the plugin's class file.
			File classFile = new File(source, type.getName().replace('.', '/') + ".class");
			if (classFile.isFile())
				return "classes@" + classFile.length() + "@" + classFile.lastModified();
			return "unknown";
		} catch (Exception e) {
			return "unknown";
		}
	}

	static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	static void append(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		int length = bytes.length;
		digest.update((byte) (length >>> 24));
		digest.update((byte) (length >>> 16));
		digest.update((byte) (length >>> 8));
		digest.update((byte) length);
		digest.update(bytes);
	}

	private static void append(MessageDigest digest, String name, String value) {
		append(digest, name);
		append(digest, value);
	}

	record ParseSettings(String language, boolean simplify, boolean disableMissingAndOrWarnings,
						 boolean caseSensitive, boolean caseInsensitiveVariables, boolean allowFunctionsBeforeDefinitions,
						 EventPriority eventPriority, int numberAccuracy) {

		static ParseSettings capture() {
			return new ParseSettings(SkriptConfig.language.value(), SkriptConfig.simplifySyntaxesOnParse.value(),
				SkriptConfig.disableMissingAndOrWarnings.value(), SkriptConfig.caseSensitive.value(),
				SkriptConfig.caseInsensitiveVariables.value(), SkriptConfig.allowFunctionsBeforeDefs.value(),
				SkriptConfig.defaultEventPriority.value(), SkriptConfig.numberAccuracy.value());
		}

		boolean matchesCurrentValues() {
			return Objects.equals(language, SkriptConfig.language.value())
				&& simplify == SkriptConfig.simplifySyntaxesOnParse.value()
				&& disableMissingAndOrWarnings == SkriptConfig.disableMissingAndOrWarnings.value()
				&& caseSensitive == SkriptConfig.caseSensitive.value()
				&& caseInsensitiveVariables == SkriptConfig.caseInsensitiveVariables.value()
				&& allowFunctionsBeforeDefinitions == SkriptConfig.allowFunctionsBeforeDefs.value()
				&& eventPriority == SkriptConfig.defaultEventPriority.value()
				&& numberAccuracy == SkriptConfig.numberAccuracy.value();
		}
	}

}
