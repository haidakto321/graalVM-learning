package com.example.graal;

import com.example.graal.greeters.RegisteredGreeter;
import com.example.graal.greeters.UnregisteredGreeter;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the hints on the JVM, in milliseconds, instead of waiting minutes for a native build
 * to find out a class was left out. This is the normal way to test hints in real projects.
 */
class GreeterHintsTest {

    @Test
    void onlyRegisteredGreeterIsDeclared() {
        RuntimeHints hints = new RuntimeHints();
        new GreeterHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(RegisteredGreeter.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(UnregisteredGreeter.class)).rejects(hints);
    }
}
