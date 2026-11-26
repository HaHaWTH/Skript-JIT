package ch.njol.skript.lang.il;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.registrations.Classes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

	private static final List<SyntaxRegistry.Key<?>> SYNTAX_KEYS = List.of(
		SyntaxRegistry.STRUCTURE,
		SyntaxRegistry.SECTION,
		SyntaxRegistry.STATEMENT,
		SyntaxRegistry.EFFECT,
		SyntaxRegistry.CONDITION,
		SyntaxRegistry.EXPRESSION
	);

	/**
	 * @return the SHA-256 of the file's content, or {@code null} if it could not be read.
	 */
	public static @Nullable String hashFile(File file) {
		try (InputStream in = Files.newInputStream(file.toPath())) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			append(digest, "skript", Skript.getVersion().toString());
			append(digest, "mc", Bukkit.getBukkitVersion());
			append(digest, "api", Bukkit.getBukkitVersion());
			append(digest, "language", String.valueOf(SkriptConfig.language.value()));

			// the plugin jar identity: changes on every Skript build even if the version string does not
			append(digest, "jar", pluginJarIdentity());

			// config options that can change parse results
			append(digest, "simplify", String.valueOf(SkriptConfig.simplifySyntaxesOnParse.value()));
			append(digest, "missing-and-or", String.valueOf(SkriptConfig.disableMissingAndOrWarnings.value()));
			append(digest, "case-sensitive", String.valueOf(SkriptConfig.caseSensitive.value()));
			append(digest, "case-insensitive-vars", String.valueOf(SkriptConfig.caseInsensitiveVariables.value()));
			append(digest, "functions-before-defs", String.valueOf(SkriptConfig.allowFunctionsBeforeDefs.value()));
			append(digest, "event-priority", String.valueOf(SkriptConfig.defaultEventPriority.value()));
			append(digest, "number-accuracy", String.valueOf(SkriptConfig.numberAccuracy.value()));

			// plugins (addons register syntaxes, converters, class infos and event values)
			Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
			List<String> pluginIdentities = new ArrayList<>(plugins.length);
			for (Plugin plugin : plugins)
				pluginIdentities.add(plugin.getName() + "@" + plugin.getPluginMeta().getVersion());
			pluginIdentities.sort(String::compareTo);
			append(digest, "plugins", String.join(",", pluginIdentities));

			// registered syntaxes, in a JVM-independent (sorted) order
			SyntaxRegistry registry = Skript.instance().syntaxRegistry();
			List<String> syntaxIdentities = new ArrayList<>();
			for (SyntaxRegistry.Key<?> key : SYNTAX_KEYS) {
				for (SyntaxInfo<?> info : registry.syntaxes(key)) {
					syntaxIdentities.add(info.type().getName()
						+ '|' + info.priority()
						+ '|' + String.join("\u0001", info.patterns()));
				}
			}
			syntaxIdentities.sort(String::compareTo);
			append(digest, "syntax", String.join("\u0002", syntaxIdentities));

			// class infos (code names are stable and are what the IL stores)
			Collection<ClassInfo<?>> classInfos = Classes.getClassInfos();
			List<String> classInfoNames = classInfos.stream()
				.map(ClassInfo::getCodeName)
				.sorted()
				.collect(Collectors.toList());
			append(digest, "classinfos", String.join(",", classInfoNames));

			return IlScriptCache.hex(digest.digest());
		} catch (Exception e) {
			// if the fingerprint cannot be computed the cache is simply unusable
			return "unavailable";
		}
	}

	private static String pluginJarIdentity() {
		try {
			var location = Skript.class.getProtectionDomain().getCodeSource().getLocation();
			if (location == null)
				return "unknown";
			File source = new File(location.toURI());
			if (source.isFile())
				return source.length() + "@" + source.lastModified();
			// development environment (classes directory): use the Skript class file's identity
			File classFile = new File(source, "ch/njol/skript/Skript.class");
			if (classFile.isFile())
				return "classes@" + classFile.length() + "@" + classFile.lastModified();
			return "unknown";
		} catch (Exception e) {
			return "unknown";
		}
	}

	private static void append(MessageDigest digest, String name, String value) {
		digest.update((name + '=' + value + '\n').getBytes(StandardCharsets.UTF_8));
	}


}
