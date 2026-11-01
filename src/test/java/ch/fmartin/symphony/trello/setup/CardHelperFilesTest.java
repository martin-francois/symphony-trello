package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.WindowsTransportAclFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class CardHelperFilesTest {
    @TempDir
    Path temporary;

    @Test
    void stoppedReadinessCleanupCannotDeleteALiveHelpersRecord() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        var files = new CardHelperFiles(temporary.resolve("state/card-helper/installation"));
        // The live server holds its process lock throughout the deletion attempt.
        try (@SuppressWarnings("PMD.UnusedLocalVariable")
                var server = new CardHelperServer(config, files, (request, out) -> 0)) {
            byte[] original = Files.readAllBytes(files.ready());
            var ready = CardHelperProtocol.json().readValue(original, CardHelperProtocol.Ready.class);

            // when
            files.removeStoppedReadiness(ready);

            // then
            assertThat(files.ready()).hasBinaryContent(original);
        }
    }

    @Test
    void stoppedReadinessCleanupPreservesAReplacementGeneration() throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("config"));
        var files = new CardHelperFiles(temporary.resolve("state/card-helper/installation"));
        files.initialize(config);
        Files.createFile(files.processLock());
        var stopped = new CardHelperProtocol.Ready(CardHelperProtocol.VERSION, UUID.randomUUID(), 12345);
        byte[] replacement = CardHelperProtocol.json()
                .writeValueAsBytes(new CardHelperProtocol.Ready(CardHelperProtocol.VERSION, UUID.randomUUID(), 67890));
        Files.write(files.ready(), replacement);

        // when
        files.removeStoppedReadiness(stopped);

        // then
        assertThat(files.ready()).hasBinaryContent(replacement);
    }

    @Test
    void windowsCreationProtectsPrivateDirectoriesFromInheritedGrants() throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("acl"), "Windows ACL support required");
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path state = Files.createDirectory(temporary.resolve("state"));
        UserPrincipal owner = Files.getOwner(config);
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                .build();
        var stateAcl = Files.getFileAttributeView(state, AclFileAttributeView.class);
        List<AclEntry> inheritable = new ArrayList<>(List.of(ownerEntry));
        List<UserPrincipal> otherPrincipals = new ArrayList<>();
        for (AclEntry entry : stateAcl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
                inheritable.add(AclEntry.newBuilder(entry)
                        .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                        .build());
                otherPrincipals.add(entry.principal());
            }
        }
        assertThat(otherPrincipals)
                .as("native fixture has inheritable grants for another principal")
                .isNotEmpty();
        stateAcl.setAcl(inheritable);
        var files = new CardHelperFiles(state.resolve("card-helper/installation"));

        // when
        files.initialize(config);

        // then
        assertThat(List.of(
                        state.resolve("card-helper"),
                        files.root(),
                        files.requests(),
                        files.submissions(),
                        files.responses(),
                        files.privateState()))
                .allSatisfy(directory -> assertOwnerOnly(directory, owner));
    }

    @Test
    void windowsRestartPreservesTransportGrantsWithoutExposingPrivateState() throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("acl"), "Windows ACL support required");
        var fixture = initializedFixture();
        Path config = fixture.config();
        CardHelperFiles files = fixture.files();
        UserPrincipal reader = otherPrincipal(temporary, Files.getOwner(config));
        AclEntry read = transportGrant(reader, AclEntryPermission.READ_DATA);
        AclEntry write = transportGrant(reader, AclEntryPermission.WRITE_DATA);
        addGrant(files.root(), read);
        addGrant(files.responses(), read);
        addGrant(files.requests(), write);

        // when
        files.initialize(config);

        // then
        assertThat(Files.getFileAttributeView(files.requests(), AclFileAttributeView.class)
                        .getAcl())
                .contains(write);
        assertOwnerOnly(files.privateState(), Files.getOwner(config));
    }

    @CsvSource({
        "root,WRITE_DATA",
        "responses,WRITE_DATA",
        "requests,WRITE_ACL",
        "requests,WRITE_OWNER",
        "private,READ_DATA",
        "submissions,WRITE_DATA"
    })
    @ParameterizedTest
    void windowsRejectsGrantsOutsideTheTransportRole(String directory, AclEntryPermission permission) throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("acl"), "Windows ACL support required");
        var fixture = initializedFixture();
        Path config = fixture.config();
        CardHelperFiles files = fixture.files();
        Path target =
                switch (directory) {
                    case "root" -> files.root();
                    case "responses" -> files.responses();
                    case "requests" -> files.requests();
                    case "submissions" -> files.submissions();
                    default -> files.privateState();
                };
        addGrant(target, transportGrant(otherPrincipal(temporary, Files.getOwner(config)), permission));

        // when
        Throwable failure = catchThrowable(() -> files.initialize(config));

        // then
        assertThat(failure).isInstanceOf(IOException.class).hasMessageContaining("remove access grants");
    }

    private Fixture initializedFixture() throws IOException {
        Path config = Files.createDirectory(temporary.resolve("config"));
        var files = new CardHelperFiles(temporary.resolve("state/card-helper/installation"));
        files.initialize(config);
        return new Fixture(config, files);
    }

    private record Fixture(Path config, CardHelperFiles files) {}

    private static void assertOwnerOnly(Path directory, UserPrincipal owner) throws IOException {
        assertThat(Files.getOwner(directory)).isEqualTo(owner);
        assertThat(Files.getFileAttributeView(directory, AclFileAttributeView.class)
                        .getAcl())
                .filteredOn(entry -> entry.type() == AclEntryType.ALLOW)
                .extracting(AclEntry::principal)
                .containsOnly(owner);
    }

    @Test
    void refusesSharedWritableStateParent() throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"), "POSIX permissions required");
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path state = Files.createDirectory(temporary.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxrwx---"));
        var files = new CardHelperFiles(state.resolve("card-helper").resolve("installation"));

        // when
        Throwable thrown = catchThrowable(() -> files.initialize(config));

        // then
        assertThat(thrown)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("remove write permissions for other users");
    }

    @Test
    void refusesLinkedPrivateState() throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"), "POSIX links required");
        Path config = Files.createDirectory(temporary.resolve("config"));
        var files = new CardHelperFiles(temporary.resolve("state/card-helper/installation"));
        files.initialize(config);
        Files.delete(files.privateState());
        Files.createSymbolicLink(files.privateState(), config);

        // when
        Throwable thrown = catchThrowable(() -> files.initialize(config));

        // then
        assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("expected an unlinked directory");
    }
}
