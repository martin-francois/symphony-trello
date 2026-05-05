package ch.fmartin.symphony.trello.prompt;

import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class PromptRenderer {
    private static final String DEFAULT_PROMPT = "You are working on a Trello card.";

    private final PebbleEngine engine =
            new PebbleEngine.Builder().strictVariables(true).build();

    public String render(String template, Card card, Integer attempt) {
        String effectiveTemplate = template == null || template.isBlank() ? DEFAULT_PROMPT : template;
        Map<String, Object> cardData = card.toTemplateMap();
        Map<String, Object> context = new HashMap<>();
        context.put("card", cardData);
        context.put("issue", cardData);
        context.put("attempt", attempt);

        try {
            PebbleTemplate compiled = engine.getLiteralTemplate(effectiveTemplate);
            StringWriter writer = new StringWriter();
            compiled.evaluate(writer, context);
            return writer.toString();
        } catch (PebbleException e) {
            throw new WorkflowException("template_render_error", "Prompt template failed to render", e);
        } catch (IOException e) {
            throw new WorkflowException("template_render_error", "Prompt template output failed", e);
        }
    }

    public String continuationPrompt(int turnNumber, int maxTurns) {
        return """
                Continue working on the same Trello card.

                Do not repeat the full task prompt. Re-check the card state when useful, finish any remaining implementation and verification work, and hand off according to WORKFLOW.md.

                This is continuation turn %d of at most %d in this worker session.
                """
                .formatted(turnNumber, maxTurns)
                .trim();
    }
}
