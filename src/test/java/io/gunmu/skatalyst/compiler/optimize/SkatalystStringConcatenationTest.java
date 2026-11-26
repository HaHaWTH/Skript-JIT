package io.gunmu.skatalyst.compiler.optimize;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.effects.EffChange;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.util.ContextlessEvent;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.localization.Message;
import ch.njol.skript.util.StringMode;
import ch.njol.skript.variables.Variables;
import io.gunmu.skatalyst.compiler.BytecodeCompiler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SkatalystStringConcatenationTest {

	@Test
	public void compiledConcatenationUsesTheCurrentClonerAndItsExactResult() throws ReflectiveOperationException {
		var messageField = Message.class.getDeclaredField("messages");
		messageField.setAccessible(true);
		var messages = (Collection<?>) messageField.get(null);
		var event = ContextlessEvent.get();
		var stringClass = new ClassInfo<>(String.class, "string");
		try {
			Trigger isolated = new Trigger(null, "isolated concatenation", new SimpleEvent(), List.of());
			var plan = new StringConcatenationOptimization.Concatenation(new EffChange(), "out", StringMode.MESSAGE,
				List.of("prefix ", new SimpleLiteral<>("value", false), " suffix"), stringClass);
			var entry = BytecodeCompiler.compile(isolated, Map.of(isolated, plan)).entry(isolated);
			assertNotNull(entry);
			assertNull(entry.walk(event));
			assertEquals("prefix value suffix", Variables.getVariable("out", event, true));

			List<String> inputs = new ArrayList<>();
			String replacement = "replacement";
			stringClass.cloner(value -> {
				inputs.add(value);
				return inputs.size() == 1 ? replacement : null;
			});
			assertNull(entry.walk(event));
			assertSame(replacement, Variables.getVariable("out", event, true));
			assertNull(entry.walk(event));
			assertNull(Variables.getVariable("out", event, true));
			assertEquals(List.of("prefix value suffix", "prefix value suffix"), inputs);
		} finally {
			// Unregistered ClassInfo instances still create a localization message.
			messages.remove(stringClass.getName());
			Variables.removeLocals(event);
		}
	}
}
