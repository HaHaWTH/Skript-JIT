package ch.njol.skript.lang.il;

import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.patterns.SkriptPattern;
import org.skriptlang.skript.registration.SyntaxInfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** A registration bound to one live syntax-registry snapshot. */
final class IlRegistration {

	private final String descriptor;
	private final SyntaxInfo<?> info;
	private final List<String> patterns;
	private final AtomicReferenceArray<SkriptPattern> compiled;

	IlRegistration(String descriptor, SyntaxInfo<?> info) {
		this.descriptor = descriptor;
		this.info = info;
		patterns = List.copyOf(info.patterns());
		compiled = new AtomicReferenceArray<>(patterns.size());
	}

	String descriptor() {
		return descriptor;
	}

	SyntaxInfo<?> info() {
		return info;
	}

	int patternCount() {
		return patterns.size();
	}

	SkriptPattern pattern(int index) {
		SkriptPattern pattern = compiled.get(index);
		if (pattern == null) {
			pattern = SkriptParser.getSyntaxPattern(patterns.get(index));
			compiled.set(index, pattern);
		}
		return pattern;
	}
}
