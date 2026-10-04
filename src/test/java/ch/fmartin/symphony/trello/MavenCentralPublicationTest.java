package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MavenCentralPublicationTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path RELEASE_WORKFLOW = Path.of(".github/workflows/release-please.yml");
    private static final Path POM = Path.of("pom.xml");
    private static final String RELEASE_JOB = "release-please";
    private static final String PUBLICATION_JOB = "maven-central";
    private static final String PUBLICATION_ENVIRONMENT = "maven-central";
    private static final String STAGE_STEP = "Stage Maven Central artifacts";
    private static final String DEPLOY_STEP = "Deploy to Maven Central";

    @Test
    void publicationRunsAfterThePublishedGitHubReleaseOnlyWhenTheOwnerEnablesIt() throws IOException {
        // given
        JsonNode jobs = releaseWorkflowJobs();

        // when
        JsonNode publicationJob = jobs.get(PUBLICATION_JOB);

        // then
        assertThat(jobs.get(RELEASE_JOB).get("outputs").properties())
                .extracting(MavenCentralPublicationTest::keyValue)
                .contains(
                        "upload_assets=${{ steps.release-assets.outputs.upload_assets }}",
                        "checkout_ref=${{ steps.release-assets.outputs.checkout_ref }}");
        assertThat(jobs.get(RELEASE_JOB).get("steps"))
                .as("the GitHub Release job never runs the Maven Central steps")
                .extracting(step -> step.path("name").asText())
                .doesNotContain(STAGE_STEP, DEPLOY_STEP);
        assertThat(publicationJob.get("needs").asText()).isEqualTo(RELEASE_JOB);
        assertThat(publicationJob.get("if").asText())
                .isEqualTo("${{ needs.release-please.outputs.upload_assets == 'true'"
                        + " && vars.MAVEN_CENTRAL_STAGE != '' }}");
        assertThat(publicationJob.get("environment").asText()).isEqualTo(PUBLICATION_ENVIRONMENT);
        assertThat(publicationJob.get("permissions")).hasToString("{\"contents\":\"read\"}");
        assertThat(publicationJob.get("steps"))
                .extracting(step -> step.path("name").asText())
                .containsSubsequence("Checkout release tag", STAGE_STEP, DEPLOY_STEP);
        assertThat(publicationJob.get("steps").get(0).get("with"))
                .hasToString("{\"ref\":\"${{ needs.release-please.outputs.checkout_ref }}\","
                        + "\"persist-credentials\":false}");
    }

    @Test
    void onlyTheDeployStepReceivesPublicationSecrets() throws IOException {
        // given
        JsonNode steps = releaseWorkflowJobs().get(PUBLICATION_JOB).get("steps");

        // when
        JsonNode deployEnvironment = steps.get(steps.size() - 1).get("env");

        // then
        assertThat(steps)
                .filteredOn(step -> step.toString().contains("secrets."))
                .extracting(step -> step.path("name").asText())
                .containsExactly(DEPLOY_STEP);
        assertThat(deployEnvironment.properties())
                .extracting(MavenCentralPublicationTest::keyValue)
                .containsExactlyInAnyOrder(
                        "JRELEASER_MAVENCENTRAL_STAGE=${{ vars.MAVEN_CENTRAL_STAGE }}",
                        "JRELEASER_MAVENCENTRAL_USERNAME=${{ secrets.JRELEASER_MAVENCENTRAL_USERNAME }}",
                        "JRELEASER_MAVENCENTRAL_PASSWORD=${{ secrets.JRELEASER_MAVENCENTRAL_PASSWORD }}",
                        "JRELEASER_GPG_PUBLIC_KEY=${{ secrets.JRELEASER_GPG_PUBLIC_KEY }}",
                        "JRELEASER_GPG_SECRET_KEY=${{ secrets.JRELEASER_GPG_SECRET_KEY }}",
                        "JRELEASER_GPG_PASSPHRASE=${{ secrets.JRELEASER_GPG_PASSPHRASE }}");
    }

    @Test
    void jreleaserOnlyDeploysMavenArtifactsWithUploadAsThePomStage() throws IOException {
        // given
        String pom = Files.readString(POM);

        // when
        String jreleaser = pom.substring(pom.indexOf("<jreleaser>"), pom.indexOf("</jreleaser>"));

        // then
        assertThat(jreleaser)
                .contains(
                        "<skipTag>true</skipTag>",
                        "<skipRelease>true</skipRelease>",
                        "<active>RELEASE</active>",
                        "<stage>UPLOAD</stage>",
                        "<url>https://central.sonatype.com/api/v1/publisher</url>",
                        "<stagingRepositories>${maven-central.staging-directory}</stagingRepositories>")
                .as("GitHub Release assets, checksums.txt, and their attestation belong to the release job")
                .doesNotContain("<files>", "<distributions>", "<checksum>", "<upload>", "<assemble>");
    }

    /// Pull request CI never runs JReleaser's POM checks, so this test keeps the required metadata
    /// and the attached jars from disappearing between releases.
    @Test
    void pomCarriesMavenCentralMetadataAndAttachesSourcesAndJavadoc() throws IOException {
        // given
        String pom = Files.readString(POM);

        // when
        String header = pom.substring(0, pom.indexOf("<properties>"));

        // then
        assertThat(header)
                .contains(
                        "<url>https://github.com/martin-francois/symphony-trello</url>",
                        "<name>Apache-2.0</name>",
                        "<developers>",
                        "<connection>scm:git:https://github.com/martin-francois/symphony-trello.git</connection>",
                        "<url>https://github.com/martin-francois/symphony-trello/issues</url>");
        assertThat(pom)
                .contains(
                        "<artifactId>maven-source-plugin</artifactId>",
                        "<goal>jar-no-fork</goal>",
                        "<artifactId>maven-javadoc-plugin</artifactId>",
                        "<altDeploymentRepository>local::file:${maven-central.staging-directory}"
                                + "</altDeploymentRepository>");
    }

    private static JsonNode releaseWorkflowJobs() throws IOException {
        return YAML.readTree(RELEASE_WORKFLOW.toFile()).get("jobs");
    }

    private static String keyValue(Map.Entry<String, JsonNode> entry) {
        return entry.getKey() + "=" + entry.getValue().asText();
    }
}
