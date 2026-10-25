package ch.fmartin.symphony.trello.agent;

/// Owns one delimited workpad section without changing malformed or ambiguous content.
final class ManagedWorkpadSection {
    private final String startMarker;
    private final String endMarker;

    ManagedWorkpadSection(String startMarker, String endMarker) {
        this.startMarker = startMarker;
        this.endMarker = endMarker;
    }

    boolean containsManagedSectionMarker(String workpad) {
        return workpad != null && (workpad.contains(startMarker) || workpad.contains(endMarker));
    }

    String upsert(String workpad, String section) {
        if (hasMalformedManagedSectionMarkers(workpad)) {
            return workpad;
        }
        String withoutManagedSection = remove(workpad).stripTrailing();
        return withoutManagedSection + "\n\n" + section;
    }

    String remove(String workpad) {
        if (hasMalformedManagedSectionMarkers(workpad)) {
            return workpad;
        }
        String result = workpad;
        int start = result.indexOf(startMarker);
        while (start >= 0) {
            int end = result.indexOf(endMarker, start + startMarker.length());
            int after = end + endMarker.length();
            result = (result.substring(0, start).stripTrailing() + "\n\n"
                            + result.substring(after).stripLeading())
                    .stripTrailing();
            start = result.indexOf(startMarker);
        }
        return result;
    }

    String managedSection(String workpad) {
        if (workpad == null || hasMalformedManagedSectionMarkers(workpad)) {
            return null;
        }
        int start = workpad.indexOf(startMarker);
        if (start < 0) {
            return null;
        }
        int end = workpad.indexOf(endMarker, start + startMarker.length());
        return workpad.substring(start, end + endMarker.length());
    }

    boolean hasMalformedManagedSectionMarkers(String workpad) {
        if (workpad == null) {
            return false;
        }
        int cursor = 0;
        int sections = 0;
        while (cursor < workpad.length()) {
            int start = workpad.indexOf(startMarker, cursor);
            int unmatchedEnd = workpad.indexOf(endMarker, cursor);
            if (start < 0) {
                return unmatchedEnd >= 0;
            }
            if (unmatchedEnd >= 0 && unmatchedEnd < start) {
                return true;
            }
            int end = workpad.indexOf(endMarker, start + startMarker.length());
            if (end < 0) {
                return true;
            }
            int nestedStart = workpad.indexOf(startMarker, start + startMarker.length());
            if (nestedStart >= 0 && nestedStart < end) {
                return true;
            }
            sections++;
            if (sections > 1) {
                return true;
            }
            cursor = end + endMarker.length();
        }
        return false;
    }
}
