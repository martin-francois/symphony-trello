package ch.fmartin.symphony.trello.testsupport;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/// Models a source launcher whose application dependencies are absent from java.class.path.
public final class IsolatedCliLauncher {
    private IsolatedCliLauncher() {}

    public static void main(String[] arguments) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : Pattern.compile(Pattern.quote(File.pathSeparator))
                .split(System.getenv("SYMPHONY_TEST_APPLICATION_CLASSPATH"), -1)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class.forName(arguments[0], true, loader)
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) Arrays.copyOfRange(arguments, 1, arguments.length));
        }
    }
}
