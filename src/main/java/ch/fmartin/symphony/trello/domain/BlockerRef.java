package ch.fmartin.symphony.trello.domain;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record BlockerRef(
        @Nullable String id, @Nullable String identifier, @Nullable String state, @Nullable String url) {}
