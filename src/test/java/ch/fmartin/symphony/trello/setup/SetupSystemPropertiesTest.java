package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

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
}
