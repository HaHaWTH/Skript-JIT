package io.gunmu.skatalyst.addon;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.skript.config.Config;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import io.gunmu.skatalyst.Skatalyst;
import io.gunmu.skatalyst.api.SkatalystAddonApi;
import io.gunmu.skatalyst.api.SkatalystSemantics;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.docs.Origin;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.registration.DefaultSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ParsedBindingTest {
	private static SkriptAddon addon;

	@BeforeClass
	public static void registerAddon() {
		addon = Skript.instance().registerAddon(ParsedBindingTest.class, "Skatalyst Parsed Binding Tests");
	}

	@Test
	public void originalContractAppliesToInterpretedAndGeneratedWalkAndRevocation() throws IOException {
		var registry = addon.syntaxRegistry();
		var info = SyntaxInfo.builder(Probe.class).addPattern("skatalyst run original proof").build();
		registry.register(SyntaxRegistry.EFFECT, info);
		try (var lease = SkatalystAddonApi.forAddon(addon).registerAnnotated(Probe.class)) {
			Trigger trigger = parse("skatalyst run original proof");
			TriggerItem item = trigger.skatalystItems().getFirst();
			assertTrue(item.skatalystExposesLocals());
			assertTrue(item.skatalystHasOriginalContract());
			var generated = BytecodeCompiler.compile(trigger);
			try {
				var entry = Objects.requireNonNull(generated.entry(item));
				ProbeEvent interpreted = new ProbeEvent();
				TriggerItem.skatalystStep(item, interpreted);
				assertProbe(interpreted, false);
				ProbeEvent compiled = new ProbeEvent();
				entry.walk(compiled);
				assertProbe(compiled, false);

				lease.close();
				ProbeEvent revoked = new ProbeEvent();
				entry.walk(revoked);
				assertProbe(revoked, true);
			} finally {
				generated.retire();
				trigger.getScript().invalidate();
			}
		} finally {
			registry.unregister(SyntaxRegistry.EFFECT, info);
		}
	}

	@Test
	public void unregisteringActualSyntaxRevokesItsAlreadyParsedContract() throws IOException {
		var registry = addon.syntaxRegistry();
		var info = SyntaxInfo.builder(Probe.class).addPattern("skatalyst run original proof").build();
		registry.register(SyntaxRegistry.EFFECT, info);
		try (var lease = SkatalystAddonApi.forAddon(addon).registerAnnotated(Probe.class)) {
			Trigger trigger = parse("skatalyst run original proof");
			try {
				TriggerItem item = trigger.skatalystItems().getFirst();
				ParsedBinding binding = Objects.requireNonNull(item.skatalystBinding());
				assertTrue(binding.preservesLocals());
				registry.unregister(SyntaxRegistry.EFFECT, info);
				assertFalse(binding.preservesLocals());
				ProbeEvent event = new ProbeEvent();
				TriggerItem.skatalystStep(item, event);
				assertProbe(event, true);
			} finally {
				trigger.getScript().invalidate();
			}
		} finally {
			registry.unregister(SyntaxRegistry.EFFECT, info);
		}
	}

	@Test
	public void disableEventClosesAdmissionBeforePaperFlipsEnabledAndOldLeasesStayRevoked() {
		var registry = addon.syntaxRegistry();
		var info = SyntaxInfo.builder(Probe.class).origin(Origin.of(addon)).addPattern("skatalyst test lifecycle owner").build();
		AtomicBoolean enabled = new AtomicBoolean(true);
		Plugin owner = plugin(enabled);
		AtomicBoolean replacementEnabled = new AtomicBoolean(false);
		Plugin replacement = plugin(replacementEnabled);
		registry.register(SyntaxRegistry.EFFECT, info);
		try (var old = AddonRegistry.registerAnnotated(owner, addon, Probe.class, element -> null)) {
			var oldMatch = Objects.requireNonNull(AddonRegistry.lookup(info, new Probe()));
			assertTrue(oldMatch.provider().isActive());
			AddonRegistry.onPluginDisable(owner);
			// Paper dispatches PluginDisableEvent before JavaPlugin.setEnabled(false).
			assertTrue(owner.isEnabled());
			assertFalse(oldMatch.provider().isActive());
			assertThrows(IllegalStateException.class, () -> AddonRegistry.registerAnnotated(owner, addon, Probe.class, element -> null));
			assertNull(AddonRegistry.lookup(info, new Probe()));

			enabled.set(false);
			AddonRegistry.onPluginEnable(owner); // Paper may finish an enable attempt after the plugin disabled itself.
			assertThrows(IllegalStateException.class, () -> AddonRegistry.registerAnnotated(owner, addon, Probe.class, element -> null));
			// A freshly constructed instance can register in its first onEnable, before PluginEnableEvent.
			assertThrows(IllegalStateException.class, () -> AddonRegistry.registerAnnotated(replacement, addon, Probe.class, element -> null));
			replacementEnabled.set(true);
			try (var fresh = AddonRegistry.registerAnnotated(replacement, addon, Probe.class, element -> null)) {
				assertNotNull(AddonRegistry.lookup(info, new Probe()));
			}
			enabled.set(true);
			assertThrows(IllegalStateException.class, () -> AddonRegistry.registerAnnotated(owner, addon, Probe.class, element -> null));
			AddonRegistry.onPluginEnable(owner);
			assertNull("no registration from the disabling window is published", AddonRegistry.lookup(info, new Probe()));
			try (var newLease = AddonRegistry.registerAnnotated(owner, addon, Probe.class, element -> null)) {
				var newMatch = Objects.requireNonNull(AddonRegistry.lookup(info, new Probe()));
				old.close();
				assertFalse(oldMatch.provider().isActive());
				assertTrue(newMatch.provider().isActive());
				assertEquals(newMatch, AddonRegistry.lookup(info, new Probe()));
			}
		} finally {
			registry.unregister(SyntaxRegistry.EFFECT, info);
		}
	}

	private static Plugin plugin(AtomicBoolean enabled) {
		return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
			(proxy, method, args) -> switch (method.getName()) {
				case "isEnabled" -> enabled.get();
				case "equals" -> proxy == args[0];
				case "hashCode" -> System.identityHashCode(proxy);
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	@Test
	public void successfulNestedSyntaxIsOpaqueAndFailedPatternsRollBack() throws IOException {
		var registry = addon.syntaxRegistry();
		var expression = DefaultSyntaxInfos.Expression.builder(Unknown.class, Long.class)
			.addPattern("skatalyst untrusted input").build();
		var effect = SyntaxInfo.builder(Probe.class).addPatterns(
			"skatalyst reject this candidate %number%",
			"skatalyst reject this candidate skatalyst untrusted input",
			"skatalyst accept this candidate %number%").build();
		registry.register(SyntaxRegistry.EXPRESSION, expression);
		registry.register(SyntaxRegistry.EFFECT, effect);
		try (var lease = SkatalystAddonApi.forAddon(addon).registerAnnotated(Probe.class)) {
			Trigger trigger = parse("""
				skatalyst reject this candidate skatalyst untrusted input
				skatalyst accept this candidate skatalyst untrusted input
				""");
			try {
				assertTrue(trigger.skatalystItems().getFirst().skatalystHasOriginalContract());
				TriggerItem unknown = trigger.skatalystItems().getLast();
				assertTrue(unknown.skatalystExposesLocals());
				assertFalse(unknown.skatalystHasOriginalContract());
				ProbeEvent event = new ProbeEvent();
				TriggerItem.skatalystStep(unknown, event);
				assertProbe(event, true);
				assertEquals(1, event.operandCalls);
			} finally {
				trigger.getScript().invalidate();
			}
		} finally {
			registry.unregister(SyntaxRegistry.EFFECT, effect);
			registry.unregister(SyntaxRegistry.EXPRESSION, expression);
		}
	}

	private static Trigger parse(String bodyText) throws IOException {
		String text = "fixture:\n\t" + bodyText.stripTrailing().replace("\n", "\n\t") + "\n";
		Config config = new Config(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
			"skatalyst-addon-parsing-test.sk", true, false, ":");
		SectionNode body = (SectionNode) config.getMainNode().get("fixture");
		assertNotNull(body);
		Script script = new Script(config, List.of());
		ParserInstance parser = ParserInstance.get();
		parser.setActive(script);
		try (var log = SkriptLogger.startRetainingLog()) {
			parser.setCurrentEvent("Skatalyst addon test", ProbeEvent.class);
			Trigger trigger = new Trigger(script, "Skatalyst addon test", new SimpleEvent(), ScriptLoader.loadItems(body));
			assertFalse(log.getLog().toString(), log.hasErrors());
			return trigger;
		} finally {
			parser.setInactive();
		}
	}

	private static void assertProbe(ProbeEvent event, boolean exposed) {
		assertEquals(1, event.calls);
		assertEquals(exposed, event.exposed);
		assertEquals(19L, Variables.getVariable("written", event, true));
		Variables.removeLocals(event);
	}

	@SkatalystSemantics(eventEscape = SkatalystSemantics.EventEscape.NO_ESCAPE, locals = SkatalystSemantics.Locals.WRITE,
		mayReenter = false, maySuspend = false)
	public static class Probe extends Effect {
		private @Nullable Expression<?> operand;

		@Override
		public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean delayed, ParseResult result) {
			if (expressions.length != 0 && matchedPattern == 0) {
				return false;
			}
			operand = expressions.length == 0 ? null : expressions[0];
			return true;
		}

		@Override
		protected void execute(Event event) {
			ProbeEvent probe = (ProbeEvent) event;
			probe.calls++;
			probe.exposed = Skatalyst.localsEscaped(event);
			if (operand != null) {
				operand.getSingle(event);
			}
			Variables.setVariable("written", 19L, event, true);
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst parsed binding probe";
		}
	}

	@SkatalystSemantics
	public static class Unknown extends SimpleExpression<Long> {
		@Override
		public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean delayed, ParseResult result) {
			return true;
		}

		@Override
		protected Long[] get(Event event) {
			((ProbeEvent) event).operandCalls++;
			return new Long[]{17L};
		}

		@Override
		public boolean isSingle() {
			return true;
		}

		@Override
		public Class<? extends Long> getReturnType() {
			return Long.class;
		}

		@Override
		public String toString(@Nullable Event event, boolean debug) {
			return "skatalyst untrusted input";
		}
	}

	public static class ProbeEvent extends Event {
		private static final HandlerList HANDLERS = new HandlerList();
		private int calls;
		private int operandCalls;
		private boolean exposed;

		@Override
		public HandlerList getHandlers() {
			return HANDLERS;
		}
	}
}
