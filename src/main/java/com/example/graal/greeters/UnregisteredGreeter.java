package com.example.graal.greeters;

/**
 * NOT declared in GreeterHints and never referenced in code.
 * Works on the JVM; in the native binary Class.forName cannot find it.
 */
public class UnregisteredGreeter implements Greeter {

    @Override
    public String greet(String name) {
        return "Hello " + name + " from UnregisteredGreeter";
    }
}
