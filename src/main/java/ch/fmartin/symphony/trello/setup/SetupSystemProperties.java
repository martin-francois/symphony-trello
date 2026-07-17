package ch.fmartin.symphony.trello.setup;

import java.util.function.Function;
import java.util.function.IntSupplier;

final class SetupSystemProperties {
    private static final Function<String, String> SYSTEM_PROPERTY_LOOKUP = System::getProperty;
    private static final ScopedValue<Function<String, String>> PROPERTY_LOOKUP = ScopedValue.newInstance();

    private SetupSystemProperties() {}

    static String get(String name) {
        return PROPERTY_LOOKUP.orElse(SYSTEM_PROPERTY_LOOKUP).apply(name);
    }

    static int withLookup(Function<String, String> properties, IntSupplier run) {
        Function<String, String> lookup = properties == null ? SYSTEM_PROPERTY_LOOKUP : properties;
        return ScopedValue.where(PROPERTY_LOOKUP, lookup).call(run::getAsInt);
    }
}
