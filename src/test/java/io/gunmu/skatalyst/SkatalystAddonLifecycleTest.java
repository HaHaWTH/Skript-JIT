package io.gunmu.skatalyst;

import ch.njol.skript.config.Config;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.util.SimpleEvent;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import io.gunmu.skatalyst.compiler.CompiledCode;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;
import org.skriptlang.skript.lang.script.Script;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class SkatalystAddonLifecycleTest {

	@Test
	public void registryRevocationRetiresOnlyDependentCompiledCode() throws IOException {
		Fixture dependent = fixture();
		Fixture ordinary = fixture();
		AtomicBoolean active = new AtomicBoolean(true);
		try {
			assertTrue(Skatalyst.forceCompile(dependent.trigger(), () -> compile(dependent, active)));
			assertTrue(Skatalyst.forceCompile(ordinary.trigger()));
			var unaffected = Skatalyst.compiledCode(ordinary.trigger());

			active.set(false);
			Skatalyst.addonRegistryChanged();

			assertNull(Skatalyst.compiledCode(dependent.trigger()));
			assertSame(unaffected, Skatalyst.compiledCode(ordinary.trigger()));
		} finally {
			dependent.owner().invalidate();
			ordinary.owner().invalidate();
		}
	}

	@Test
	public void revokedDependencyPreventsPublication() throws IOException {
		Fixture fixture = fixture();
		AtomicBoolean active = new AtomicBoolean(true);
		try {
			assertFalse(Skatalyst.forceCompile(fixture.trigger(), () -> {
				var code = compile(fixture, active);
				assertTrue(code.dependenciesActive());
				active.set(false);
				return code;
			}));
			assertNull(Skatalyst.compiledCode(fixture.trigger()));
		} finally {
			fixture.owner().invalidate();
		}
	}

	private static CompiledCode compile(Fixture fixture, AtomicBoolean active) {
		return BytecodeCompiler.compile(fixture.trigger(), Map.of(fixture.item(), emitter -> {
			emitter.dependOn(active::get);
			emitter.finishAt(null);
		}));
	}

	private static Fixture fixture() throws IOException {
		Config config = new Config(new ByteArrayInputStream(new byte[0]),
			"skatalyst-addon-lifecycle-test.sk", true, false, ":");
		Script owner = new Script(config, List.of());
		owner.skatalystScope().activate();
		TriggerItem item = new TriggerItem() {
			@Override
			protected boolean run(Event event) {
				return true;
			}

			@Override
			public String toString(@Nullable Event event, boolean debug) {
				return "skatalyst addon dependency test";
			}
		};
		return new Fixture(owner, new Trigger(owner, "Skatalyst addon dependency", new SimpleEvent(), List.of(item)), item);
	}

	private record Fixture(Script owner, Trigger trigger, TriggerItem item) {
	}
}
