package com.example.graal;

import com.example.graal.greeters.Greeter;
import org.springframework.core.NativeDetector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class DemoController {

    private static final String GREETER_PACKAGE = "com.example.graal.greeters.";

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name) {
        return Map.of("message", "Hello " + name);
    }

    /** Shows which runtime answered: the JVM (jar) or the native binary. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nativeImage", NativeDetector.inNativeImage());
        body.put("vmName", System.getProperty("java.vm.name"));
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        body.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        return body;
    }

    /**
     * Loads a greeter by a class name that only arrives at runtime - the classic native-image gotcha.
     * Try cls=RegisteredGreeter and cls=UnregisteredGreeter on both the jar and the native binary.
     */
    @GetMapping("/reflect")
    public ResponseEntity<Map<String, Object>> reflect(@RequestParam String cls,
                                                       @RequestParam(defaultValue = "world") String name) {
        // Only simple names inside our own demo package: never instantiate an arbitrary class from a request.
        if (!cls.matches("[A-Za-z][A-Za-z0-9]*")) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "cls must be a simple class name"));
        }
        String fqn = GREETER_PACKAGE + cls;
        try {
            Class<?> type = Class.forName(fqn);
            if (!Greeter.class.isAssignableFrom(type)) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", fqn + " is not a Greeter"));
            }
            Greeter greeter = (Greeter) type.getDeclaredConstructor().newInstance();
            return ResponseEntity.ok(Map.of("ok", true, "class", fqn, "result", greeter.greet(name)));
        } catch (ReflectiveOperationException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false,
                    "class", fqn,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "nativeImage", NativeDetector.inNativeImage()));
        }
    }
}
