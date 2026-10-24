package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.InstanceOfAssertFactories.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

final class MavenCentralPublicationTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final Path RELEASE_WORKFLOW = Path.of(".github/workflows/release-please.yml");
    private static final Path POM = Path.of("pom.xml");
    private static final String RELEASE_JOB = "release-please";
    private static final String PUBLICATION_JOB = "maven-central";
    private static final String PUBLICATION_ENVIRONMENT = "maven-central";
    private static final String CHECKOUT_STEP = "Checkout release tag";
    private static final String STAGE_STEP = "Stage Maven Central artifacts";
    private static final String DEPLOY_STEP = "Deploy to Maven Central";
    private static final String REPOSITORY_URL = "https://github.com/martin-francois/symphony-trello";
    private static final String BUILD_PLUGINS = "/project/build/plugins/plugin";
    private static final String PUBLICATION_PROFILE = "/project/profiles/profile[id='maven-central']";
    private static final String JRELEASER_CONFIGURATION =
            PUBLICATION_PROFILE + "/build/plugins/plugin[artifactId='jreleaser-maven-plugin']/configuration/jreleaser";

    @Test
    void publicationRunsAfterThePublishedGitHubReleaseOnlyWhenTheOwnerEnablesIt() throws IOException {
        // given
        JsonNode jobs = releaseWorkflowJobs();

        // when
        JsonNode releaseJob = jobs.get(RELEASE_JOB);
        JsonNode publicationJob = jobs.get(PUBLICATION_JOB);

        // then
        assertThat(strings(releaseJob.get("outputs")))
                .contains(
                        entry("upload_assets", "${{ steps.release-assets.outputs.upload_assets }}"),
                        entry("checkout_ref", "${{ steps.release-assets.outputs.checkout_ref }}"));
        assertThat(releaseJob.get("steps"))
                .as("the GitHub Release job never runs the Maven Central steps")
                .extracting(MavenCentralPublicationTest::stepName)
                .doesNotContain(STAGE_STEP, DEPLOY_STEP);
        assertThat(publicationJob.get("needs").asText()).isEqualTo(RELEASE_JOB);
        assertThat(publicationJob.get("if").asText())
                .isEqualTo("${{ needs.release-please.outputs.upload_assets == 'true'"
                        + " && vars.MAVEN_CENTRAL_STAGE != '' }}");
        assertThat(publicationJob.get("environment").asText()).isEqualTo(PUBLICATION_ENVIRONMENT);
        assertThat(strings(publicationJob.get("permissions"))).containsOnly(entry("contents", "read"));
        assertThat(publicationJob.get("steps"))
                .extracting(MavenCentralPublicationTest::stepName)
                .containsSubsequence(CHECKOUT_STEP, STAGE_STEP, DEPLOY_STEP);
        assertThat(publicationJob.get("steps"))
                .first()
                .extracting(step -> strings(step.get("with")), as(map(String.class, String.class)))
                .containsOnly(
                        entry("ref", "${{ needs.release-please.outputs.checkout_ref }}"),
                        entry("persist-credentials", "false"));
    }

    @Test
    void onlyTheDeployStepReceivesPublicationSecrets() throws IOException {
        // given
        JsonNode jobs = releaseWorkflowJobs();

        // when
        JsonNode steps = jobs.get(PUBLICATION_JOB).get("steps");

        // then
        assertThat(steps)
                .filteredOn(step -> step.toString().contains("secrets."))
                .extracting(MavenCentralPublicationTest::stepName)
                .containsExactly(DEPLOY_STEP);
        assertThat(steps)
                .last()
                .extracting(step -> strings(step.get("env")), as(map(String.class, String.class)))
                .containsOnly(
                        entry("JRELEASER_MAVENCENTRAL_STAGE", "${{ vars.MAVEN_CENTRAL_STAGE }}"),
                        entry("JRELEASER_MAVENCENTRAL_USERNAME", "${{ secrets.JRELEASER_MAVENCENTRAL_USERNAME }}"),
                        entry("JRELEASER_MAVENCENTRAL_PASSWORD", "${{ secrets.JRELEASER_MAVENCENTRAL_PASSWORD }}"),
                        entry("JRELEASER_GPG_PUBLIC_KEY", "${{ secrets.JRELEASER_GPG_PUBLIC_KEY }}"),
                        entry("JRELEASER_GPG_SECRET_KEY", "${{ secrets.JRELEASER_GPG_SECRET_KEY }}"),
                        entry("JRELEASER_GPG_PASSPHRASE", "${{ secrets.JRELEASER_GPG_PASSPHRASE }}"));
    }

    @Test
    void jreleaserOnlyDeploysMavenArtifactsWithUploadAsThePomStage() throws Exception {
        // given
        Document pom = pom();

        // when
        Element jreleaser = element(pom, JRELEASER_CONFIGURATION);

        // then
        assertThat(childTexts(jreleaser).keySet())
                .as("GitHub Release assets, checksums.txt, and their attestation belong to the release job")
                .containsExactlyInAnyOrder("signing", "release", "deploy");
        assertThat(childTexts(element(jreleaser, "release/github")))
                .containsOnly(entry("skipTag", "true"), entry("skipRelease", "true"));
        assertThat(childTexts(element(jreleaser, "deploy/maven/mavenCentral/sonatype")))
                .containsOnly(
                        entry("active", "RELEASE"),
                        entry("url", "https://central.sonatype.com/api/v1/publisher"),
                        entry("stage", "UPLOAD"),
                        entry("stagingRepositories", "${maven-central.staging-directory}"));
    }

    /// Pull request CI never runs JReleaser's POM checks, so this test keeps the required metadata
    /// and the attached jars from disappearing between releases.
    @Test
    void pomCarriesMavenCentralMetadataAndAttachesSourcesAndJavadoc() throws Exception {
        // given
        Document pom = pom();

        // when
        Element project = pom.getDocumentElement();

        // then
        assertThat(childTexts(project)).containsEntry("url", REPOSITORY_URL);
        assertThat(childTexts(element(project, "licenses/license"))).containsEntry("name", "Apache-2.0");
        assertThat(childTexts(element(project, "developers/developer"))).containsKeys("id", "name", "url");
        assertThat(childTexts(element(project, "scm")))
                .containsOnly(
                        entry("connection", "scm:git:" + REPOSITORY_URL + ".git"),
                        entry("developerConnection", "scm:git:" + REPOSITORY_URL + ".git"),
                        entry("url", REPOSITORY_URL));
        assertThat(childTexts(element(project, "issueManagement"))).containsEntry("url", REPOSITORY_URL + "/issues");
        assertThat(childTexts(
                        element(pom, BUILD_PLUGINS + "[artifactId='maven-source-plugin']/executions/execution/goals")))
                .containsOnly(entry("goal", "jar-no-fork"));
        assertThat(childTexts(
                        element(pom, BUILD_PLUGINS + "[artifactId='maven-javadoc-plugin']/executions/execution/goals")))
                .containsOnly(entry("goal", "jar"));
        assertThat(childTexts(
                        element(pom, PUBLICATION_PROFILE + "/build/plugins/plugin[artifactId='maven-deploy-plugin']")))
                .as("a pinned deploy plugin lets Renovate track the version that fills the staging directory")
                .containsEntry("version", "${maven-deploy-plugin.version}");
        assertThat(childTexts(element(pom, PUBLICATION_PROFILE + "/properties")))
                .containsOnly(entry("altDeploymentRepository", "local::file:${maven-central.staging-directory}"));
    }

    private static JsonNode releaseWorkflowJobs() throws IOException {
        return YAML.readTree(RELEASE_WORKFLOW.toFile()).get("jobs");
    }

    private static String stepName(JsonNode step) {
        return step.path("name").asText();
    }

    private static Map<String, String> strings(JsonNode mapping) {
        return YAML.convertValue(mapping, STRING_MAP);
    }

    private static Document pom() throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(POM.toFile());
    }

    private static Element element(Node context, String path) throws XPathExpressionException {
        if (XPathFactory.newDefaultInstance().newXPath().evaluate(path, context, XPathConstants.NODE)
                instanceof Element element) {
            return element;
        }
        throw new AssertionError("pom.xml has no element " + path);
    }

    /// Maps each child element name to its text; callers pass elements whose child names are unique.
    private static Map<String, String> childTexts(Element parent) {
        Map<String, String> texts = new LinkedHashMap<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child) {
                texts.put(child.getTagName(), child.getTextContent().strip());
            }
        }
        return texts;
    }
}
