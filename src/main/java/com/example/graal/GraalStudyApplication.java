package com.example.graal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
// Tells Spring AOT to pass our extra reflection hints to native-image at build time.
@ImportRuntimeHints(GreeterHints.class)
public class GraalStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraalStudyApplication.class, args);
    }
}
