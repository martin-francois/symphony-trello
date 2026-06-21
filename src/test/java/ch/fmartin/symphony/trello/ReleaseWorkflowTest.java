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
                        "Verify release assets")
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
