package ch.fmartin.symphony.trello.setup;

import java.util.function.Function;
import java.util.function.IntSupplier;

final class SetupSystemProperties {
    private static final ThreadLocal<Function<String, String>> PROPERTY_LOOKUP = new ThreadLocal<>();

    private SetupSystemProperties() {}

    static String get(String name) {
        Function<String, String> lookup = PROPERTY_LOOKUP.get();
        return lookup == null ? System.getProperty(name) : lookup.apply(name);
    }

    static int withLookup(Function<String, String> properties, IntSupplier run) {
        Function<String, String> previous = PROPERTY_LOOKUP.get();
        PROPERTY_LOOKUP.set(properties);
        try {
            return run.getAsInt();
        } finally {
            if (previous == null) {
                PROPERTY_LOOKUP.remove();
            } else {
                PROPERTY_LOOKUP.set(previous);
            }
        }
    }
}
