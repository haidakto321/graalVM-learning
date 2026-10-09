package com.example.graal.greeters;

/** Declared in GreeterHints, so it survives into the native binary. */
public class RegisteredGreeter implements Greeter {

    @Override
    public String greet(String name) {
        return "Hello " + name + " from RegisteredGreeter";
    }
}
