package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReleaseWorkflowTest {
    @Test
    void releaseWorkflowBuildsAssetsOnlyForReleasePleaseCreatedTags() throws IOException {
        // given
        Path workflow = Path.of(".github/workflows/release-please.yml");

        // when
        String source = Files.readString(workflow);

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
                        "release_script=\"${{ steps.release-assets.outputs.source_root }}/scripts/package-release-assets.sh\"",
                        "release tag does not contain scripts/package-release-assets.sh",
                        "bash \"$release_script\"",
                        "\"${{ steps.release-assets.outputs.asset_dir }}\"",
                        "existing_assets=\"$(gh release view \"$RELEASE_TAG\" --json assets --jq '.assets[].name')\"",
                        "grep -Fx -- \"$asset\" <<<\"$existing_assets\"",
                        "release already contains expected public assets; refusing same-tag asset reuse",
                        "gh release upload \"$RELEASE_TAG\" \"${upload_assets[@]}\"",
                        "Verify release assets",
                        "Publish release",
                        "gh release edit \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\" --draft=false --latest")
                .contains("if: ${{ github.event_name == 'push' }}")
                .doesNotContain(
                        "workflow_dispatch:",
                        "DISPATCH_VERSION",
                        "DISPATCH_TAG",
                        "git ls-remote --exit-code origin",
                        "gh release upload \"$RELEASE_TAG\" dist/release-assets/*",
                        "--clobber");
        assertAppearsBefore(
                source,
                "gh release view \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\"",
                "path: target/release-source");
        assertAppearsBefore(
                source, "gh release view \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\"", "Build release assets");
        assertAppearsBefore(source, "Upload release assets", "Verify release assets");
        assertAppearsBefore(source, "Verify release assets", "Publish release");
    }

    @Test
    void releasePleaseConfigsCreateDraftReleasesForImmutableAssetPublication() throws IOException {
        // given
        Path normalConfig = Path.of("release-please-config.json");

        // when
        String normalSource = Files.readString(normalConfig);

        // then
        assertThat(normalSource).contains("\"draft\": true", "\"force-tag-creation\": true");
    }

    @Test
    void releasePleasePullRequestsDoNotCreateCiChecks() throws IOException {
        // given
        Path releaseConfig = Path.of("release-please-config.json");
        Path labelerWorkflow = Path.of(".github/workflows/compatibility-labeler.yml");
        Path codeQlWorkflow = Path.of(".github/workflows/codeql.yml");
        Path codeRabbitConfig = Path.of(".coderabbit.yaml");

        // when
        String releaseSource = Files.readString(releaseConfig);
        String labelerSource = Files.readString(labelerWorkflow);
        String codeQlSource = Files.readString(codeQlWorkflow);
        String codeRabbitSource = Files.readString(codeRabbitConfig);

        // then
        assertThat(releaseSource)
                .contains(
                        "\"pull-request-title-pattern\": \"chore${scope}: release${component} ${version} [skip ci]\"",
                        "\"group-pull-request-title-pattern\": \"chore${scope}: release${component} ${version} [skip ci]\"");
        assertThat(labelerSource)
                .contains(
                        "pull_request_target:",
                        "paths-ignore:",
                        "- .release-please-manifest.json",
                        "- CHANGELOG.md",
                        "- install.ps1",
                        "- install.sh",
                        "- pom.xml")
                .doesNotContain("startsWith(github.head_ref, 'release-please--branches--')");
        assertThat(codeQlSource)
                .contains(
                        "pull_request:",
                        "paths-ignore:",
                        "- .release-please-manifest.json",
                        "- CHANGELOG.md",
                        "- install.ps1",
                        "- install.sh",
                        "- pom.xml");
        assertThat(codeRabbitSource)
                .contains("commit_status: false", "ignore_title_keywords:", "- \"[skip ci]\"");
    }

    @Test
    void releaseWorkflowUploadsAndVerifiesEveryPublicDownloadAsset() throws IOException {
        // given
        Path workflow = Path.of(".github/workflows/release-please.yml");

        // when
        String source = Files.readString(workflow);

        // then
        assertThat(source)
                .contains(
                        "\"install.sh\"",
                        "\"install.ps1\"",
                        "\"uninstall.sh\"",
                        "\"uninstall.ps1\"",
                        "\"checksums.txt\"",
                        "\"symphony-trello-$RELEASE_VERSION.tar.gz\"",
                        "\"symphony-trello-$RELEASE_VERSION.zip\"",
                        "release asset was not built: $asset",
                        "release already contains expected public assets; refusing same-tag asset reuse",
                        "release asset is missing after upload: $asset")
                .doesNotContain("--clobber");
    }

    @Test
    void releaseWorkflowSkipsAssetsWhenReleasePleaseDoesNotCreateRelease() throws IOException {
        // given
        Path workflow = Path.of(".github/workflows/release-please.yml");

        // when
        String source = Files.readString(workflow);

        // then
        assertThat(source)
                .contains(
                        "if [ \"$RELEASE_CREATED\" = \"true\" ]; then",
                        "echo \"upload_assets=false\" >> \"$GITHUB_OUTPUT\"",
                        "if: ${{ steps.release-assets.outputs.upload_assets == 'true' }}")
                .doesNotContain("workflow_dispatch:", "DISPATCH_VERSION", "DISPATCH_TAG");
        assertAppearsBefore(
                source, "echo \"upload_assets=false\" >> \"$GITHUB_OUTPUT\"", "Checkout release source tag");
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
}
