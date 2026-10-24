package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations;

import ch.fmartin.symphony.trello.testsupport.FuzzIsolationExtension;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Keeps every fuzz harness under [FuzzIsolationExtension] and keeps Jazzer's network detector switched on
/// for ClusterFuzzLite and OSS-Fuzz runs.
final class FuzzHarnessIsolationRulesTest {
    private static final Path TEST_SOURCES = Path.of("src/test/java");
    private static final String JAVA_SUFFIX = ".java";
    private static final List<Path> FUZZING_CONFIGURATION =
            List.of(Path.of("pom.xml"), Path.of("oss-fuzz"), Path.of(".clusterfuzzlite"), Path.of(".github/workflows"));
    private static final Path SEED_CORPORA = Path.of("oss-fuzz/corpora");
    // Jazzer takes options as flags, -Djazzer.* properties, JAZZER_* variables, and .options entries, so
    // match the option key followed by an assignment in any of those spellings.
    private static final Pattern HOOK_SWITCH =
            Pattern.compile("(?i)disabled_hooks\\s*[=:]|(?<![a-z])hooks\\s*[=:]\\s*[\"']?(?:false|0)\\b");

    @Test
    void everyFuzzTestClassRecordsItsFileAndSocketIo() throws IOException, ClassNotFoundException {
        // given
        List<Class<?>> fuzzTestClasses = new ArrayList<>();

        // when
        for (String className : testClassNames()) {
            Class<?> testClass = Class.forName(className, false, FuzzHarnessIsolationRulesTest.class.getClassLoader());
            if (declaresFuzzTest(testClass)) {
                fuzzTestClasses.add(testClass);
            }
        }

        // then
        assertThat(fuzzTestClasses)
                .as("the scan finds the fuzz test classes")
                .contains(StandaloneFuzzerIsolationTest.class)
                .allSatisfy(testClass -> assertThat(findRepeatableAnnotations(testClass, ExtendWith.class))
                        .as(
                                "%s declares @FuzzTest methods, so it needs @ExtendWith(FuzzIsolationExtension.class)",
                                testClass.getName())
                        .flatExtracting(extendWith -> List.of(extendWith.value()))
                        .contains(FuzzIsolationExtension.class));
    }

    @Test
    void fuzzingConfigurationKeepsJazzerNetworkDetectorEnabled() throws IOException {
        // given
        Map<Path, String> configuration = new TreeMap<>();

        // when
        for (Path root : FUZZING_CONFIGURATION) {
            for (Path file : regularFiles(root)) {
                configuration.put(file, Files.readString(file));
            }
        }

        // then
        assertThat(configuration)
                .as("Jazzer's ServerSideRequestForgery hook reports every network connection during fuzzing")
                .containsKeys(
                        Path.of("pom.xml"), Path.of("oss-fuzz/build.sh"), Path.of(".clusterfuzzlite/project.yaml"))
                .allSatisfy((file, content) -> assertThat(content)
                        .as("%s must not disable Jazzer hooks", file)
                        .doesNotContainPattern(HOOK_SWITCH));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "--disabled_hooks=com.code_intelligence.jazzer.sanitizers.ServerSideRequestForgery",
                "-Djazzer.disabled_hooks=com.code_intelligence.jazzer.sanitizers.ServerSideRequestForgery",
                "JAZZER_DISABLED_HOOKS: com.code_intelligence.jazzer.sanitizers.ServerSideRequestForgery",
                "disabled_hooks = com.code_intelligence.jazzer.sanitizers.ServerSideRequestForgery",
                "--hooks=false",
                "-Djazzer.hooks=0",
                "JAZZER_HOOKS: \"false\""
            })
    void hookSwitchMatchesEverySpellingThatTurnsHooksOff(String setting) {
        // given
        String line = setting;

        // when
        boolean matched = HOOK_SWITCH.matcher(line).find();

        // then
        assertThat(matched)
                .as("the network detector check must recognize %s as switching Jazzer hooks off", line)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "# Jazzer's ServerSideRequestForgery hook reports every network connection.",
                "-Djazzer.hooks=true",
                "hooks:",
                "  before_run: ./prepare.sh"
            })
    void hookSwitchIgnoresSettingsThatKeepHooksOn(String setting) {
        // given
        String line = setting;

        // when
        boolean matched = HOOK_SWITCH.matcher(line).find();

        // then
        assertThat(matched)
                .as("the network detector check must not flag %s, which keeps Jazzer hooks on", line)
                .isFalse();
    }

    private static List<String> testClassNames() throws IOException {
        List<String> classNames = new ArrayList<>();
        for (Path source : regularFiles(TEST_SOURCES)) {
            String relative = TEST_SOURCES.relativize(source).toString();
            if (relative.endsWith(JAVA_SUFFIX) && !relative.endsWith("-info" + JAVA_SUFFIX)) {
                classNames.add(relative.substring(0, relative.length() - JAVA_SUFFIX.length())
                        .replace(source.getFileSystem().getSeparator(), "."));
            }
        }
        return List.copyOf(classNames);
    }

    private static boolean declaresFuzzTest(Class<?> testClass) {
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(FuzzTest.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> regularFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                // Seed inputs are fuzzer data, not configuration.
                return directory.equals(SEED_CORPORA) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Path::compareTo);
        return List.copyOf(files);
    }
}
