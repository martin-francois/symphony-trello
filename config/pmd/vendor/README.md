# Vendored PMD Rules

This directory contains third-party PMD rulesets that are intentionally vendored as source files.

## jPinpoint

`jpinpoint-java-rules.xml` is copied from
[PMD-jPinpoint-rules](https://github.com/jborgers/PMD-jPinpoint-rules):

- upstream branch: `pmd7`
- upstream commit: `306079521fd97152ada849c7bc4c53ccee6993c8`
- upstream path: `rulesets/java/jpinpoint-rules.xml`
- upstream license: Apache-2.0, copied in `LICENSE-PMD-jPinpoint-rules`

The file is used only by the report-only `jpinpoint` Maven profile. Keep local changes out of the
vendored file, except for stripping trailing whitespace during import so repository whitespace
checks stay clean. Update it by copying a newer upstream snapshot, stripping trailing whitespace, and
updating this README plus the ADR.
