package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReleaseWorkflowTest {
    @Test
    void releaseWorkflowBuildsAssetsOnlyForReleasePleaseCreatedTags() throws IOException {
        // given
        String workflow = releaseWorkflowSource();

        // when
        String source = releaseImplementation();

        // then
        assertThat(source)
                .contains(
                        "on:",
                        "push:",
                        "Checkout release workflow source",
                        "Resolve release asset upload target",
                        "RELEASE_CREATED: ${{ steps.release.outputs.release_created }}",
                        "RELEASE_VERSION: ${{ steps.release.outputs.version }}",
                        "RELEASE_TAG: ${{ steps.release.outputs.tag_name }}",
                        "upload_assets=true",
                        "release tag does not match release version.",
                        "gh release view \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\"",
                        "release does not exist: $RELEASE_TAG",
                        "release_draft=\"$(gh release view \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\" --json isDraft --jq '.isDraft')\"",
                        "release must be draft before asset upload so immutable releases publish with complete assets.",
                        "checkout_ref=refs/tags/$RELEASE_TAG",
                        "source_root=$GITHUB_WORKSPACE/target/release-source",
                        "asset_dir=$GITHUB_WORKSPACE/target/release-source/dist/release-assets",
                        "Checkout release source tag",
                        "path: target/release-source",
                        "release_script=\"$RELEASE_SOURCE_ROOT/scripts/package-release-assets.sh\"",
                        "release tag does not contain scripts/package-release-assets.sh",
                        "bash \"$release_script\"",
                        "\"$ASSET_DIR\"",
                        "Attest release assets",
                        "subject-path: ${{ steps.release-assets.outputs.asset_dir }}/*",
                        "create-storage-record: false",
                        "Add release provenance asset",
                        "PROVENANCE_BUNDLE: ${{ steps.attest-release-assets.outputs.bundle-path }}",
                        "release provenance bundle was not created",
                        "symphony-trello-$RELEASE_VERSION.intoto.jsonl",
                        "existing_assets=\"$(gh release view \"$RELEASE_TAG\" --json assets --jq '.assets[].name')\"",
                        "grep -Fx -- \"$asset\" <<<\"$existing_assets\"",
                        "release already contains expected public assets; refusing same-tag asset reuse",
                        "gh release upload \"$RELEASE_TAG\" \"${upload_assets[@]}\"",
                        "Verify release assets",
                        "Publish release",
                        "gh release edit \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\" --draft=false --latest")
                .contains("if: ${{ github.event_name == 'push' }}")
                .containsPattern("(?m)^\\s*uses: actions/attest@[0-9a-f]{40} # v[0-9]+\\.[0-9]+\\.[0-9]+$")
                .doesNotContain(
                        "workflow_dispatch:",
                        "DISPATCH_VERSION",
                        "DISPATCH_TAG",
                        "git ls-remote --exit-code origin",
                        "gh release upload \"$RELEASE_TAG\" dist/release-assets/*",
                        "--clobber");
        assertAppearsBefore(workflow, "Resolve release asset upload target", "Checkout release source tag");
        assertAppearsBefore(workflow, "Build release assets", "Attest release assets");
        assertAppearsBefore(workflow, "Attest release assets", "Add release provenance asset");
        assertAppearsBefore(workflow, "Add release provenance asset", "Upload release assets");
        assertAppearsBefore(workflow, "Upload release assets", "Verify release assets");
        assertAppearsBefore(workflow, "Verify release assets", "Publish release");
    }

    @Test
    void releasePleaseConfigCreatesDraftReleasesRefreshesMetadataAndCreditsContributors() throws IOException {
        // given
        Path normalConfig = Path.of("release-please-config.json");

        // when
        String normalSource = Files.readString(normalConfig);

        // then
        assertThat(normalSource)
                .contains(
                        "\"changelog-type\": \"github\"",
                        "\"draft\": true",
                        "\"force-tag-creation\": true",
                        "\"always-update\": true");
    }

    @Test
    void releasePleasePullRequestsDoNotCreateCiChecks() throws IOException {
        // given
        Path releaseConfig = Path.of("release-please-config.json");
        Path compatibilityLabelerWorkflow = Path.of(".github/workflows/compatibility-labeler.yml");
        Path commitlintWorkflow = Path.of(".github/workflows/commitlint.yml");
        Path sizeLabelerWorkflow = Path.of(".github/workflows/size-labeler.yml");
        Path codeQlWorkflow = Path.of(".github/workflows/codeql.yml");
        Path codeRabbitConfig = Path.of(".coderabbit.yaml");

        // when
        String releaseSource = Files.readString(releaseConfig);
        String compatibilityLabelerSource = Files.readString(compatibilityLabelerWorkflow);
        String commitlintSource = Files.readString(commitlintWorkflow);
        String sizeLabelerSource = Files.readString(sizeLabelerWorkflow);
        String codeQlSource = Files.readString(codeQlWorkflow);
        String codeRabbitSource = Files.readString(codeRabbitConfig);

        // then
        assertThat(releaseSource)
                .contains(
                        "\"pull-request-title-pattern\": \"chore${scope}: release${component} ${version} [skip ci]\"",
                        "\"group-pull-request-title-pattern\": \"chore${scope}: release${component} ${version} [skip ci]\"");
        assertThat(Map.of(
                        "compatibility labeler", compatibilityLabelerSource,
                        "size labeler", sizeLabelerSource,
                        "CodeQL", codeQlSource))
                .allSatisfy((workflow, source) -> assertThat(source)
                        .as("%s workflow", workflow)
                        .contains(
                                "paths-ignore:",
                                "- .release-please-manifest.json",
                                "- CHANGELOG.md",
                                "- install.ps1",
                                "- install.sh",
                                "- pom.xml"));
        assertThat(compatibilityLabelerSource)
                .contains("pull_request_target:")
                .doesNotContain("startsWith(github.head_ref, 'release-please--branches--')");
        assertThat(commitlintSource)
                .contains(
                        "name: Commitlint",
                        "pull_request:",
                        "types: [opened, reopened, synchronize, ready_for_review, edited]")
                .doesNotContain("paths:", "paths-ignore:");
        assertThat(sizeLabelerSource)
                .contains(
                        "pull_request_target:",
                        "paths-ignore:",
                        "workflow_run:",
                        "workflows: [Commitlint]",
                        "types: [completed]",
                        "github.event.workflow_run.event == 'pull_request'",
                        "schedule:")
                .doesNotContain("startsWith(github.head_ref, 'release-please--branches--')");
        assertThat(codeQlSource).contains("pull_request:");
        assertThat(codeRabbitSource)
                .contains(
                        "commit_status: false",
                        "review_status: false",
                        "ignore_title_keywords:",
                        "- \"[skip ci]\"",
                        "ignore_usernames:",
                        "- \"github-actions[bot]\"");
    }

    @Test
    void releaseWorkflowUploadsAndVerifiesEveryPublicDownloadAsset() throws IOException {
        // given
        List<String> expectedAssetsAndChecks = List.of(
                "\"install.sh\"",
                "\"install.ps1\"",
                "\"uninstall.sh\"",
                "\"uninstall.ps1\"",
                "\"checksums.txt\"",
                "\"symphony-trello-$version.intoto.jsonl\"",
                "\"symphony-trello-$version.tar.gz\"",
                "\"symphony-trello-$version.zip\"",
                "release asset was not built: $asset",
                "release already contains expected public assets; refusing same-tag asset reuse",
                "release asset is missing after upload: $asset");

        // when
        String source = releaseImplementation();

        // then
        assertThat(expectedAssetsAndChecks)
                .allSatisfy(expected ->
                        assertThat(source).as("release implementation").contains(expected));
        assertThat(source).doesNotContain("--clobber");
    }

    @Test
    void releaseWorkflowSkipsAssetsWhenReleasePleaseDoesNotCreateRelease() throws IOException {
        // given
        String workflow = releaseWorkflowSource();

        // when
        String source = releaseImplementation();

        // then
        assertThat(source)
                .contains(
                        "if [[ \"$RELEASE_CREATED\" != \"true\" ]]; then",
                        "echo \"upload_assets=false\" >>\"$GITHUB_OUTPUT\"",
                        "if: ${{ steps.release-assets.outputs.upload_assets == 'true' }}")
                .doesNotContain("workflow_dispatch:", "DISPATCH_VERSION", "DISPATCH_TAG");
        assertAppearsBefore(workflow, "run: scripts/resolve-release-asset-target", "Checkout release source tag");
    }

    @Test
    void releasePackagingUsesTagLocalToolingForInstallersAndApp() throws IOException {
        // given
        Path script = Path.of("scripts/package-release-assets.sh");

        // when
        String source = Files.readString(script);

        // then
        assertThat(source)
                .contains(
                        "Usage: scripts/package-release-assets.sh VERSION [DIST]",
                        "stamp_posix_installer",
                        "stamp_powershell_installer",
                        "/^DEFAULT_VERSION=.*# x-release-please-version/",
                        "/^[[:space:]]*\\[string\\]\\$Version = .*# x-release-please-version/",
                        "/^[[:space:]]*\\[string\\]\\$Ref = .*# x-release-please-version/",
                        "/^\\$DefaultVersion = .*# x-release-please-version/",
                        "cd \"$ROOT\"",
                        "cp -R \"$ROOT/target/quarkus-app\" \"$STAGING/target/quarkus-app\"",
                        "cp \"$ROOT/README.md\" \"$STAGING/README.md\"",
                        "cp \"$ROOT/uninstall.sh\" \"$ROOT/uninstall.ps1\" \"$ASSET_DIR/\"")
                .doesNotContain("SOURCE_ROOT", "[SOURCE_ROOT]");
    }

    private static void assertAppearsBefore(String source, String earlier, String later) {
        assertThat(source.indexOf(earlier))
                .as("expected `%s` to appear", earlier)
                .isNotNegative();
        assertThat(source.indexOf(later)).as("expected `%s` to appear", later).isNotNegative();
        assertThat(source.indexOf(earlier))
                .as("expected `%s` before `%s`", earlier, later)
                .isLessThan(source.indexOf(later));
    }

    private static String releaseWorkflowSource() throws IOException {
        return Files.readString(Path.of(".github/workflows/release-please.yml"));
    }

    private static String releaseImplementation() throws IOException {
        return String.join(
                System.lineSeparator(),
                releaseWorkflowSource(),
                Files.readString(Path.of("scripts/list-release-assets")),
                Files.readString(Path.of("scripts/resolve-release-asset-target")),
                Files.readString(Path.of("scripts/build-release-assets")),
                Files.readString(Path.of("scripts/add-release-provenance")),
                Files.readString(Path.of("scripts/upload-release-assets")),
                Files.readString(Path.of("scripts/verify-release-assets")));
    }
}
