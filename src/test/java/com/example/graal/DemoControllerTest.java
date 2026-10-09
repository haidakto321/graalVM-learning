package com.example.graal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.condition.EnabledInNativeImage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DemoControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void helloAnswers() throws Exception {
        mvc.perform(get("/hello").param("name", "Hai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello Hai"));
    }

    // `mvnw test` runs on the JVM; `mvnw -PnativeTest test` runs the same tests inside a native binary.
    // These annotations pick which of the two runs a test belongs to.

    @Test
    @DisabledInNativeImage
    void infoReportsJvm() throws Exception {
        mvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeImage").value(false));
    }

    @Test
    @EnabledInNativeImage
    void infoReportsNativeImage() throws Exception {
        mvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeImage").value(true));
    }

    @Test
    void registeredGreeterWorksOnJvm() throws Exception {
        mvc.perform(get("/reflect").param("cls", "RegisteredGreeter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /** The JVM is lenient: it finds any class on the classpath. */
    @Test
    @DisabledInNativeImage
    void unregisteredGreeterWorksOnJvm() throws Exception {
        mvc.perform(get("/reflect").param("cls", "UnregisteredGreeter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /**
     * No hint = native-image left the class out of the binary (closed world).
     * Exact string on purpose: Hamcrest matchers like startsWith() use reflection internally
     * and fail inside a native image ("Cannot determine correct type for matchesSafely()").
     */
    @Test
    @EnabledInNativeImage
    void unregisteredGreeterIsMissingInNativeImage() throws Exception {
        mvc.perform(get("/reflect").param("cls", "UnregisteredGreeter"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error")
                        .value("ClassNotFoundException: com.example.graal.greeters.UnregisteredGreeter"));
    }

    @Test
    void rejectsNamesOutsideDemoPackage() throws Exception {
        mvc.perform(get("/reflect").param("cls", "java.lang.Runtime"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void interfaceCannotBeInstantiated() throws Exception {
        mvc.perform(get("/reflect").param("cls", "Greeter"))
                .andExpect(status().isInternalServerError());
    }
}
