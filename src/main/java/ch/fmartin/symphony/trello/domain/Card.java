package ch.fmartin.symphony.trello.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Card(
        String id,
        String identifier,
        String title,
        @Nullable String description,
        @Nullable Integer priority,
        String state,
        String stateSource,
        @Nullable String listId,
        @Nullable String listName,
        @Nullable Boolean listClosed,
        @Nullable String boardId,
        @Nullable Boolean boardClosed,
        boolean closed,
        @Nullable Integer idShort,
        @Nullable String shortLink,
        @Nullable String shortUrl,
        @Nullable String branchName,
        @Nullable String url,
        List<String> labels,
        List<String> labelIds,
        List<String> members,
        List<Checklist> checklists,
        List<Attachment> attachments,
        List<TrelloReference> trelloReferences,
        List<PrerequisiteProblem> prerequisiteProblems,
        List<BlockerRef> blockedBy,
        List<Comment> comments,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt,
        @Nullable Instant dueAt,
        @Nullable Boolean dueComplete,
        @Nullable BigDecimal position) {

    public Card {
        labels = List.copyOf(labels == null ? List.of() : labels);
        labelIds = List.copyOf(labelIds == null ? List.of() : labelIds);
        members = List.copyOf(members == null ? List.of() : members);
        checklists = List.copyOf(checklists == null ? List.of() : checklists);
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
        trelloReferences = List.copyOf(trelloReferences == null ? List.of() : trelloReferences);
        prerequisiteProblems = List.copyOf(prerequisiteProblems == null ? List.of() : prerequisiteProblems);
        blockedBy = List.copyOf(blockedBy == null ? List.of() : blockedBy);
        comments = List.copyOf(comments == null ? List.of() : comments);
    }

    public Map<String, @Nullable Object> toTemplateMap() {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
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
        values.put("checklists", checklists.stream().map(Card::checklistMap).toList());
        values.put("attachments", attachments.stream().map(Card::attachmentMap).toList());
        values.put(
                "trello_references",
                trelloReferences.stream().map(Card::trelloReferenceMap).toList());
        values.put(
                "prerequisite_problems",
                prerequisiteProblems.stream().map(Card::prerequisiteProblemMap).toList());
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

    /// The Trello card URL preferred for operator click-through, using the stable short URL when
    /// present and falling back to the full canonical URL. Returns `null` when neither is known
    /// so snapshot rows can omit an unknown URL.
    public @Nullable String cardUrl() {
        if (notBlank(shortUrl)) {
            return shortUrl;
        }
        return notBlank(url) ? url : null;
    }

    public Card withChecklists(List<Checklist> nextChecklists) {
        return withRelationships(nextChecklists, trelloReferences, prerequisiteProblems, blockedBy);
    }

    public Card withRelationships(
            List<Checklist> nextChecklists,
            List<TrelloReference> nextTrelloReferences,
            List<PrerequisiteProblem> nextPrerequisiteProblems,
            List<BlockerRef> nextBlockedBy) {
        return new Card(
                id,
                identifier,
                title,
                description,
                priority,
                state,
                stateSource,
                listId,
                listName,
                listClosed,
                boardId,
                boardClosed,
                closed,
                idShort,
                shortLink,
                shortUrl,
                branchName,
                url,
                labels,
                labelIds,
                members,
                nextChecklists,
                attachments,
                nextTrelloReferences,
                nextPrerequisiteProblems,
                nextBlockedBy,
                comments,
                createdAt,
                updatedAt,
                dueAt,
                dueComplete,
                position);
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, @Nullable Object> checklistMap(Checklist checklist) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("id", checklist.id());
        values.put("name", checklist.name());
        values.put(
                "items", checklist.items().stream().map(Card::checklistItemMap).toList());
        return values;
    }

    private static Map<String, @Nullable Object> attachmentMap(Attachment attachment) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("id", attachment.id());
        values.put("name", attachment.name());
        values.put("url", attachment.url());
        return values;
    }

    private static Map<String, @Nullable Object> checklistItemMap(ChecklistItem item) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("id", item.id());
        values.put("text", item.text());
        values.put("complete", item.complete());
        return values;
    }

    private static Map<String, @Nullable Object> trelloReferenceMap(TrelloReference reference) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("source", reference.source());
        values.put("text", reference.text());
        values.put("lookup_id", reference.lookupId());
        values.put("identifier", reference.identifier());
        values.put("title", reference.title());
        values.put("state", reference.state());
        values.put("url", reference.url());
        values.put("status", reference.status());
        values.put("terminal", reference.terminal());
        return values;
    }

    private static Map<String, @Nullable Object> prerequisiteProblemMap(PrerequisiteProblem problem) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("code", problem.code());
        values.put("message", problem.message());
        values.put("checklist", problem.checklist());
        return values;
    }

    private static Map<String, @Nullable Object> blockerMap(BlockerRef blocker) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("id", blocker.id());
        values.put("identifier", blocker.identifier());
        values.put("state", blocker.state());
        values.put("url", blocker.url());
        return values;
    }

    private static Map<String, @Nullable Object> commentMap(Comment comment) {
        Map<String, @Nullable Object> values = new LinkedHashMap<>();
        values.put("id", comment.id());
        values.put("text", comment.text());
        values.put("author", comment.author());
        values.put("created_at", comment.createdAt());
        return values;
    }

    public record Comment(@Nullable String id, String text, @Nullable String author, @Nullable Instant createdAt) {}

    public record Checklist(@Nullable String id, @Nullable String name, List<ChecklistItem> items) {
        public Checklist {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record ChecklistItem(@Nullable String id, @Nullable String text, boolean complete) {}

    public record Attachment(@Nullable String id, @Nullable String name, @Nullable String url) {}

    public record TrelloReference(
            String source,
            @Nullable String text,
            String lookupId,
            @Nullable String identifier,
            @Nullable String title,
            @Nullable String state,
            @Nullable String url,
            String status,
            @Nullable Boolean terminal) {}

    public record PrerequisiteProblem(String code, String message, @Nullable String checklist) {}
}
