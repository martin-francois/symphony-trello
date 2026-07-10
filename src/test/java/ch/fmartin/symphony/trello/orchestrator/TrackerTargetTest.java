package ch.fmartin.symphony.trello.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TrackerTargetTest {
    @Test
    void normalizesEquivalentEndpointSpellingsWithoutRetainingRawEndpoint() {
        // given
        TrackerTarget decorated = TrackerTarget.from("TRELLO", "HTTPS://API.Example.Test.:443/%7e1///", "board-1");
        TrackerTarget plain = TrackerTarget.from("trello", "https://api.example.test/%7E1", "board-1");

        // when
        boolean equivalent = decorated.equals(plain);
        String display = decorated.toString();

        // then
        assertThat(equivalent).isTrue();
        assertThat(display).doesNotContainIgnoringCase("api.example.test", "https", "/%7e1");
    }

    @Test
    void keepsDotSegmentsDistinctWhenRawEndpointPathIsSent() {
        // given
        TrackerTarget endpointWithDotSegments =
                TrackerTarget.from("trello", "https://api.example.test/x/../1", "board-1");
        TrackerTarget endpointWithoutDotSegments =
                TrackerTarget.from("trello", "https://api.example.test/1", "board-1");

        // when
        boolean equivalent = endpointWithDotSegments.equals(endpointWithoutDotSegments);

        // then
        assertThat(equivalent).isFalse();
    }

    @Test
    void keepsAbsentUriComponentsDistinctFromExplicitEmptyComponents() {
        // given
        TrackerTarget withoutEmptyComponent = TrackerTarget.from("trello", "https://api.example.test/1", "board-1");

        // when
        List<TrackerTarget> explicitlyEmptyComponents = List.of(
                TrackerTarget.from("trello", "https://@api.example.test/1", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test/1?", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test/1#", "board-1"));

        // then
        assertThat(explicitlyEmptyComponents).noneMatch(withoutEmptyComponent::equals);
    }

    @Test
    void onlyNormalizesSlashesAtEndOfWholeRawEndpoint() {
        // given
        TrackerTarget trailingSlashes = TrackerTarget.from("trello", "https://api.example.test/1///", "board-1");
        TrackerTarget plain = TrackerTarget.from("trello", "https://api.example.test/1", "board-1");
        TrackerTarget slashBeforeQuery =
                TrackerTarget.from("trello", "https://api.example.test/1/?tenant=x", "board-1");
        TrackerTarget noSlashBeforeQuery =
                TrackerTarget.from("trello", "https://api.example.test/1?tenant=x", "board-1");
        TrackerTarget slashBeforeFragment =
                TrackerTarget.from("trello", "https://api.example.test/1/#section", "board-1");
        TrackerTarget noSlashBeforeFragment =
                TrackerTarget.from("trello", "https://api.example.test/1#section", "board-1");
        TrackerTarget slashBeforeEmptyQuery = TrackerTarget.from("trello", "https://api.example.test/1/?", "board-1");
        TrackerTarget noSlashBeforeEmptyQuery = TrackerTarget.from("trello", "https://api.example.test/1?", "board-1");

        // when
        boolean trailingSlashesMatch = trailingSlashes.equals(plain);
        boolean queryVariantsMatch = slashBeforeQuery.equals(noSlashBeforeQuery);
        boolean fragmentVariantsMatch = slashBeforeFragment.equals(noSlashBeforeFragment);
        boolean emptyQueryVariantsMatch = slashBeforeEmptyQuery.equals(noSlashBeforeEmptyQuery);

        // then
        assertThat(trailingSlashesMatch).isTrue();
        assertThat(queryVariantsMatch).isFalse();
        assertThat(fragmentVariantsMatch).isFalse();
        assertThat(emptyQueryVariantsMatch).isFalse();
    }

    @Test
    void keepsDistinctTrackerNamespacesDistinct() {
        // given
        TrackerTarget baseline = TrackerTarget.from("trello", "https://api.example.test/1", "board-1");

        // when
        List<TrackerTarget> distinctTargets = List.of(
                TrackerTarget.from("trello", "https://other.example.test/1", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test:8443/1", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test/2", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test/1?tenant=a", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test/%2f1", "board-1"),
                TrackerTarget.from("trello", "https://api.example.test/1", "board-2"),
                TrackerTarget.from("other", "https://api.example.test/1", "board-1"));

        // then
        assertThat(distinctTargets).noneMatch(baseline::equals);
    }
}
