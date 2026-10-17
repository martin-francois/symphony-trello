package ch.fmartin.symphony.trello.setup;

import org.jspecify.annotations.NullMarked;

/// A proposed workflow body migration: the metadata stays byte for byte, the user's text before and
/// after the old generated body stays as raw text, and the target generated body replaces the old one.
/// An optional advisory step, such as a Codex review of the preserved additions, may only propose a
/// replacement with different additions; the metadata and the target body stay fixed.
@NullMarked
record WorkflowBodyReplacement(
        WorkflowFileText original, String preservedPrefix, String preservedSuffix, String targetBody) {
    String proposedContent() {
        return original.withBody(preservedPrefix, targetBody, preservedSuffix);
    }

    boolean preservesUserAdditions() {
        return !preservedPrefix.isEmpty() || !preservedSuffix.isEmpty();
    }
}
