package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SetupSystemPropertiesTest {
    @Test
    void nestedLookupRestoresOuterLookupAndThenSystemProperties() {
        // given
        String propertyName = SetupSystemPropertiesTest.class.getName();
        String systemValue = System.getProperty(propertyName);
        List<String> observedValues = new ArrayList<>();

        // when
        int result = SetupSystemProperties.withLookup(name -> "outer", () -> {
            observedValues.add(SetupSystemProperties.get(propertyName));
            int nestedResult = SetupSystemProperties.withLookup(name -> "inner", () -> {
                observedValues.add(SetupSystemProperties.get(propertyName));
                return 41;
            });
            observedValues.add(SetupSystemProperties.get(propertyName));
            return nestedResult + 1;
        });
        observedValues.add(SetupSystemProperties.get(propertyName));

        // then
        assertThat(result).isEqualTo(42);
        assertThat(observedValues).containsExactly("outer", "inner", "outer", systemValue);
    }

    @Test
    void failedLookupScopeRestoresOuterLookup() {
        // given
        String propertyName = SetupSystemPropertiesTest.class.getName();
        var failure = new IllegalStateException("failed nested setup");
        List<String> observedValues = new ArrayList<>();

        // when
        Throwable thrown = catchThrowable(() -> SetupSystemProperties.withLookup(name -> "outer", () -> {
            observedValues.add(SetupSystemProperties.get(propertyName));
            try {
                return SetupSystemProperties.withLookup(name -> "inner", () -> {
                    observedValues.add(SetupSystemProperties.get(propertyName));
                    throw failure;
                });
            } catch (IllegalStateException nestedFailure) {
                observedValues.add(SetupSystemProperties.get(propertyName));
                throw nestedFailure;
            }
        }));
        observedValues.add(SetupSystemProperties.get(propertyName));

        // then
        assertThat(thrown).isSameAs(failure);
        assertThat(observedValues).containsExactly("outer", "inner", "outer", System.getProperty(propertyName));
    }

    @Test
    void nullLookupTemporarilyFallsBackToSystemProperties() {
        // given
        String propertyName = SetupSystemPropertiesTest.class.getName();
        String systemValue = System.getProperty(propertyName);
        List<String> observedValues = new ArrayList<>();

        // when
        SetupSystemProperties.withLookup(name -> "outer", () -> {
            SetupSystemProperties.withLookup(null, () -> {
                observedValues.add(SetupSystemProperties.get(propertyName));
                return 0;
            });
            observedValues.add(SetupSystemProperties.get(propertyName));
            return 0;
        });

        // then
        assertThat(observedValues).containsExactly(systemValue, "outer");
    }
}
