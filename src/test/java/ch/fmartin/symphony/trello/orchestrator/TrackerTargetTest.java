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
        String display = decorated.toString();

        // then
        assertThat(decorated).isEqualTo(plain);
        assertThat(display).doesNotContainIgnoringCase("api.example.test", "https", "/%7e1");
    }

    @Test
    void keepsDotSegmentsDistinctWhenRawEndpointPathIsSent() {
        // given
        String trackerKind = "trello";
        String boardId = "board-1";

        // when
        TrackerTarget endpointWithDotSegments =
                TrackerTarget.from(trackerKind, "https://api.example.test/x/../1", boardId);
        TrackerTarget endpointWithoutDotSegments =
                TrackerTarget.from(trackerKind, "https://api.example.test/1", boardId);

        // then
        assertThat(endpointWithDotSegments).isNotEqualTo(endpointWithoutDotSegments);
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
        String trackerKind = "trello";
        String boardId = "board-1";
        String endpoint = "https://api.example.test/1";

        // when
        TrackerTarget trailingSlashes = TrackerTarget.from(trackerKind, endpoint + "///", boardId);
        TrackerTarget plain = TrackerTarget.from(trackerKind, endpoint, boardId);
        TrackerTarget slashBeforeQuery = TrackerTarget.from(trackerKind, endpoint + "/?tenant=x", boardId);
        TrackerTarget noSlashBeforeQuery = TrackerTarget.from(trackerKind, endpoint + "?tenant=x", boardId);
        TrackerTarget slashBeforeFragment = TrackerTarget.from(trackerKind, endpoint + "/#section", boardId);
        TrackerTarget noSlashBeforeFragment = TrackerTarget.from(trackerKind, endpoint + "#section", boardId);
        TrackerTarget slashBeforeEmptyQuery = TrackerTarget.from(trackerKind, endpoint + "/?", boardId);
        TrackerTarget noSlashBeforeEmptyQuery = TrackerTarget.from(trackerKind, endpoint + "?", boardId);

        // then
        assertThat(trailingSlashes).isEqualTo(plain);
        assertThat(List.of(slashBeforeQuery, slashBeforeFragment, slashBeforeEmptyQuery))
                .as("endpoints with a slash before [query, fragment, empty query]")
                .zipSatisfy(
                        List.of(noSlashBeforeQuery, noSlashBeforeFragment, noSlashBeforeEmptyQuery),
                        (withSlash, withoutSlash) -> assertThat(withSlash).isNotEqualTo(withoutSlash));
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
