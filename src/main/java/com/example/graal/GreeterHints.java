package com.example.graal;

import com.example.graal.greeters.RegisteredGreeter;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * native-image does a "closed world" analysis: it keeps only code it can SEE being used.
 * A class loaded by a name that arrives at runtime (Class.forName(userInput)) is invisible,
 * so it is left out of the binary. This registrar declares RegisteredGreeter on purpose.
 * UnregisteredGreeter is left out on purpose, so /reflect shows the failure.
 *
 * Spring AOT writes these hints to
 * target/spring-aot/main/resources/META-INF/native-image/.../reflect-config.json
 * (open it after a native build to see what native-image actually receives).
 */
class GreeterHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(RegisteredGreeter.class,
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
