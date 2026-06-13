package ch.fmartin.symphony.trello.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Card(
        String id,
        String identifier,
        String title,
        String description,
        Integer priority,
        String state,
        String stateSource,
        String listId,
        String listName,
        Boolean listClosed,
        String boardId,
        Boolean boardClosed,
        boolean closed,
        Integer idShort,
        String shortLink,
        String shortUrl,
        String branchName,
        String url,
        List<String> labels,
        List<String> labelIds,
        List<String> members,
        List<BlockerRef> blockedBy,
        List<Comment> comments,
        Instant createdAt,
        Instant updatedAt,
        Instant dueAt,
        Boolean dueComplete,
        BigDecimal position) {

    public Card {
        labels = List.copyOf(labels == null ? List.of() : labels);
        labelIds = List.copyOf(labelIds == null ? List.of() : labelIds);
        members = List.copyOf(members == null ? List.of() : members);
        blockedBy = List.copyOf(blockedBy == null ? List.of() : blockedBy);
        comments = List.copyOf(comments == null ? List.of() : comments);
    }

    public Map<String, Object> toTemplateMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("identifier", identifier);
        values.put("title", title);
        values.put("description", description);
        values.put("priority", priority);
        values.put("state", state);
        values.put("state_source", stateSource);
        values.put("list_id", listId);
        values.put("list_name", listName);
        values.put("list_closed", listClosed);
        values.put("board_id", boardId);
        values.put("board_closed", boardClosed);
        values.put("closed", closed);
        values.put("id_short", idShort);
        values.put("short_link", shortLink);
        values.put("short_url", shortUrl);
        values.put("branch_name", branchName);
        values.put("url", url);
        values.put("labels", labels);
        values.put("label_ids", labelIds);
        values.put("members", members);
        values.put("blocked_by", blockedBy.stream().map(Card::blockerMap).toList());
        values.put("comments", comments.stream().map(Card::commentMap).toList());
        values.put("created_at", createdAt);
        values.put("updated_at", updatedAt);
        values.put("due_at", dueAt);
        values.put("due_complete", dueComplete);
        values.put("position", position);
        return values;
    }

    public boolean hasRequiredDispatchFields() {
        return notBlank(id) && notBlank(identifier) && notBlank(title) && notBlank(state);
    }

    /**
     * The Trello card URL preferred for operator click-through, using the stable short URL when
     * present and falling back to the full canonical URL. Returns {@code null} when neither is known
     * so snapshot rows can omit an unknown URL.
     */
    public String cardUrl() {
        if (notBlank(shortUrl)) {
            return shortUrl;
        }
        return notBlank(url) ? url : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Object> blockerMap(BlockerRef blocker) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", blocker.id());
        values.put("identifier", blocker.identifier());
        values.put("state", blocker.state());
        values.put("url", blocker.url());
        return values;
    }

    private static Map<String, Object> commentMap(Comment comment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", comment.id());
        values.put("text", comment.text());
        values.put("author", comment.author());
        values.put("created_at", comment.createdAt());
        return values;
    }

    public record Comment(String id, String text, String author, Instant createdAt) {}
}
