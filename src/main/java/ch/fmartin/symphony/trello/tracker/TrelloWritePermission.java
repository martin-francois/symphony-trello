package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// One `trello_tools` setting that a Trello write needs. The worker's Trello tools and the
/// `symphony-trello card` commands refuse a write with the same code and message for the same
/// setting.
@NullMarked
public enum TrelloWritePermission {
    TOOLS("trello_tools_disabled", "Trello handoff tools are disabled by trello_tools.enabled."),
    FOLLOW_UP_CARDS(
            "trello_follow_up_cards_disabled", "Follow-up cards are disabled by trello_tools.follow_up_cards.enabled."),
    WRITES("trello_writes_disabled", "Trello writes are disabled by trello_tools.allow_writes."),
    COMMENTS("trello_comments_disabled", "Trello comments are disabled by trello_tools.allow_comments."),
    CHECKLISTS("trello_checklists_disabled", "Trello checklists are disabled by trello_tools.allow_checklists."),
    URL_ATTACHMENTS(
            "trello_url_attachments_disabled",
            "Trello URL attachments are disabled by trello_tools.allow_url_attachments.");

    private final String code;
    private final String message;

    TrelloWritePermission(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public boolean granted(EffectiveConfig.TrelloToolsConfig tools) {
        return switch (this) {
            case TOOLS -> tools.enabled();
            case FOLLOW_UP_CARDS -> tools.followUpCards().enabled();
            case WRITES -> tools.allowWrites();
            case COMMENTS -> tools.allowComments();
            case CHECKLISTS -> tools.allowChecklists();
            case URL_ATTACHMENTS -> tools.allowUrlAttachments();
        };
    }

    /// Throws the refusal for the first permission in `permissions` that the workflow does not
    /// grant, in the given order.
    public static void requireGranted(EffectiveConfig config, TrelloWritePermission... permissions) {
        TrelloWritePermission refused = firstRefused(config, List.of(permissions));
        if (refused != null) {
            throw new TrelloException(refused.code, refused.message);
        }
    }

    public static boolean allGranted(EffectiveConfig config, TrelloWritePermission... permissions) {
        return firstRefused(config, List.of(permissions)) == null;
    }

    /// Returns the first permission in `permissions` that the workflow does not grant, or null when
    /// it grants them all.
    public static @Nullable TrelloWritePermission firstRefused(
            EffectiveConfig config, List<TrelloWritePermission> permissions) {
        for (TrelloWritePermission permission : permissions) {
            if (!permission.granted(config.trelloTools())) {
                return permission;
            }
        }
        return null;
    }
}
