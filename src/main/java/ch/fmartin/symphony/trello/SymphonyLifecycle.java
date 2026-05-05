package ch.fmartin.symphony.trello;

import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SymphonyLifecycle {
    private static final Logger LOG = Logger.getLogger(SymphonyLifecycle.class);

    private final SymphonyOrchestrator orchestrator;

    @ConfigProperty(name = "symphony.autostart")
    boolean autostart;

    public SymphonyLifecycle(SymphonyOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    void onStart(@Observes StartupEvent event) {
        if (!autostart) {
            LOG.info("symphony autostart disabled");
            return;
        }
        orchestrator.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        orchestrator.stop();
    }
}
