package ch.fmartin.symphony.trello.config;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/// How a Symphony-created follow-up card relates to the card that was running when Codex found it.
///
/// The two ordered relationships are true prerequisites and use the Trello prerequisite checklist
/// convention. `RELATED` has no required order, so only the follow-up card's Symphony metadata
/// footer records it.
public enum FollowUpRelationship {
    RELATED("related", "Related work, no required order", "Related work, no required order.", PrerequisiteSide.NONE),
    FOLLOW_UP_WAITS_FOR_CURRENT(
            "follow_up_waits_for_current",
            "This card must wait for the source card to finish first",
            "The follow-up card must wait for this card to finish first.",
            PrerequisiteSide.FOLLOW_UP_CARD),
    CURRENT_WAITS_FOR_FOLLOW_UP(
            "current_waits_for_follow_up",
            "The source card must wait for this card to finish first",
            "This card must wait for the follow-up card to finish first.",
            PrerequisiteSide.SOURCE_CARD);

    /// The card that gets the `Must finish first` checklist item for this relationship.
    public enum PrerequisiteSide {
        NONE,
        FOLLOW_UP_CARD,
        SOURCE_CARD
    }

    private final String value;
    private final String visibleDescription;
    private final String sourceCardNote;
    private final PrerequisiteSide prerequisiteSide;

    FollowUpRelationship(
            String value, String visibleDescription, String sourceCardNote, PrerequisiteSide prerequisiteSide) {
        this.value = value;
        this.visibleDescription = visibleDescription;
        this.sourceCardNote = sourceCardNote;
        this.prerequisiteSide = prerequisiteSide;
    }

    /// The tool argument and workflow config key.
    public String value() {
        return value;
    }

    /// The plain wording a board user reads in the follow-up card's metadata footer.
    public String visibleDescription() {
        return visibleDescription;
    }

    /// The same relationship worded from the source card's side, for its workpad note.
    public String sourceCardNote() {
        return sourceCardNote;
    }

    public PrerequisiteSide prerequisiteSide() {
        return prerequisiteSide;
    }

    public boolean sourceCardWaits() {
        return prerequisiteSide == PrerequisiteSide.SOURCE_CARD;
    }

    public static List<String> toolValues() {
        return Arrays.stream(values()).map(FollowUpRelationship::value).toList();
    }

    public static Optional<FollowUpRelationship> fromValue(String value) {
        return Arrays.stream(values())
                .filter(relationship -> relationship.value.equals(value))
                .findAny();
    }

    public static Optional<FollowUpRelationship> fromVisibleDescription(String text) {
        return Arrays.stream(values())
                .filter(relationship -> relationship.visibleDescription.equals(text))
                .findAny();
    }
}
