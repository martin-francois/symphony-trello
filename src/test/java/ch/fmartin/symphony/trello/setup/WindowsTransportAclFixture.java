package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;

final class WindowsTransportAclFixture {
    private WindowsTransportAclFixture() {}

    static UserPrincipal otherPrincipal(Path directory, UserPrincipal owner) throws IOException {
        for (AclEntry entry : Files.getFileAttributeView(directory, AclFileAttributeView.class)
                .getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)) {
                return entry.principal();
            }
        }
        throw new IOException("cannot create ACL fixture: expected another inherited principal");
    }

    static AclEntry transportGrant(UserPrincipal principal, AclEntryPermission permission) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(permission)
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                .build();
    }

    static void addGrant(Path path, AclEntry grant) throws IOException {
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        List<AclEntry> entries = new ArrayList<>(view.getAcl());
        entries.add(grant);
        view.setAcl(entries);
    }
}
