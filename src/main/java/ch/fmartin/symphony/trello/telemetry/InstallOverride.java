package ch.fmartin.symphony.trello.telemetry;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// The install-time location inputs an installer records by name in `install-context.properties`.
/// A report carries the names only, never a value or a path, and only names from this list, so
/// the field cannot carry free text. The order here is the order on the wire.
public enum InstallOverride implements WireVocabulary {
    SYMPHONY_HOME("SYMPHONY_HOME"),
    CONFIG_DIR("SYMPHONY_TRELLO_CONFIG_DIR"),
    WORKSPACE_ROOT("SYMPHONY_TRELLO_WORKSPACE_ROOT"),
    STATE_HOME("SYMPHONY_TRELLO_STATE_HOME"),
    SYMPHONY_HOME_FLAG("--symphony-home"),
    PREFIX_FLAG("--prefix"),
    BIN_DIR_FLAG("--bin-dir"),
    /// The POSIX installer chose the MicroOS `/var` layout on its own; no input was set.
    MICROOS_VAR_LAYOUT("microos-var");

    /// The separator between names in the installer's record.
    static final String RECORD_SEPARATOR = ",";

    private final String wireName;

    InstallOverride(String wireName) {
        this.wireName = wireName;
    }

    @Override
    public String wireName() {
        return wireName;
    }

    /// The names an installer recorded, in wire order and without repeats. Names outside this list
    /// are dropped rather than guessed at. An empty record means the installer saw no override; a
    /// missing record (`null`) means it left none, which the report shows as unknown.
    static Optional<List<String>> parse(@Nullable String recorded) {
        if (recorded == null) {
            return Optional.empty();
        }
        List<String> names = Arrays.stream(recorded.split(RECORD_SEPARATOR))
                .map(String::strip)
                .toList();
        return Optional.of(Arrays.stream(values())
                .map(InstallOverride::wireName)
                .filter(names::contains)
                .toList());
    }
}
