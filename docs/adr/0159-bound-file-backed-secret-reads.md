---
status: accepted
date: 2026-11-01
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #834](https://github.com/martin-francois/symphony-trello/issues/834)"
  - "[ADR 0115](0115-resolve-fuzzed-workflow-configuration.md)"
informed: [Future maintainers, Contributors]
---

# Bound file-backed secret reads independently of file metadata

## Context and Problem Statement

`ConfigResolver` checks the 64 KiB secret limit with `Files.size` and then reads the entire
file as UTF-8. Devices and pipes can report zero bytes while supplying more content, and a regular
file can grow after the size check. The metadata check therefore does not bound memory use.
The existing fuzz seam only models accurate small sizes or an oversized file rejected before
reading, so it did not expose this mismatch.

Should the resolver reject non-regular files or bound the bytes read while retaining pipe support?

## Decision Drivers

* Enforce the existing 65,536-byte limit even when metadata is inaccurate or changes.
* Preserve finite pipe-backed secrets used by secret managers.
* Preserve strict UTF-8 decoding, trailing CR/LF removal, and setting-specific error codes.
* Keep the fuzz harness independent of host files and environment values.
* State the remaining blocking behavior explicitly.

## Considered Options

* Read at most 65,537 bytes through an input stream
* Reject non-regular files before reading
* Keep metadata as the only size bound

## Decision Outcome

Chosen option: "Read at most 65,537 bytes through an input stream".

Keep the metadata check as a fast rejection of known oversized files. For every accepted size,
open a stream and use the JDK `InputStream.readNBytes` API to read at most the limit plus one byte.
Reject overflow as `secret_file_too_large` before decoding. Decode accepted bytes with a fresh
UTF-8 decoder whose default error action reports malformed input, preserving
`secret_file_read_error`. Close the stream on success, overflow, and read or decoding failure.
The `SecretFiles` seam supplies input streams so the same bounded reader applies to production
and in-memory fuzz fixtures.

This is a compatible enforcement of the existing size contract; successful regular-file and
finite pipe inputs remain supported. There are no CLI, endpoint binding, credential-source,
deployment, release-artifact, or companion-repository changes.

### Consequences

* Good, because metadata cannot cause an unbounded allocation or an unbounded byte read.
* Good, because a pipe that supplies an accepted secret and reaches EOF remains usable.
* Good, because ordinary oversized files are rejected before opening their streams.
* Bad, because the byte bound is not a deadline. Opening a pipe with no writer or reading a
  stalled producer can still block. Operators must use a regular file or a producer that completes.
* Bad, because the public fuzz seam changes from a string reader to an input-stream factory.

### Confirmation

`ConfigResolverTest` covers zero and understated metadata, the exact byte boundary, overflowing
content, finite pipe-like reads, strict UTF-8 failures, line endings, and stream closure.
Its controlled oversized fixture must fail against the metadata-only implementation without
reading a device or opening a blocking pipe. Filesystem tests cover the host reader and multibyte
UTF-8 limits. `WorkflowConfigPromptTest` preserves workflow-level secret behavior and safe errors.
`WorkflowLoaderFuzzTest` confirms the adapted in-memory seam remains usable. `AdrConformanceTest`
and `TestConventionTest` enforce repository conventions.

## Pros and Cons of the Options

### Read at most 65,537 bytes through an input stream

Read only enough bytes to distinguish an accepted secret from overflow, then decode accepted
content as strict UTF-8. Retain the metadata check only as an early rejection.

* Good, because regular files, devices, and pipes share the same byte limit.
* Good, because the JDK supplies the bounded-read mechanism without a custom accumulation loop.
* Bad, because it cannot enforce a time limit on stream opening or a stalled read.

### Reject non-regular files before reading

Check `Files.isRegularFile` before accepting the secret path.

* Good, because it rejects ordinary device and pipe paths early.
* Bad, because it excludes working pipe-based secret delivery.
* Bad, because it still needs a bounded read to handle a growing file or a path changed after checking.

### Keep metadata as the only size bound

Continue checking the reported size before an unbounded string read.

* Good, because it requires no change to the existing seam.
* Bad, because zero or understated sizes still bypass the limit and can exhaust the heap.

## More Information

The normative behavior and validation inventory are in `SPEC.md` Sections 5.3.1 and 17.1.
The [Java 25 InputStream API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/InputStream.html#readNBytes(int))
defines the bounded read and its blocking behavior. The
[Java 25 CharsetDecoder API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/charset/CharsetDecoder.html)
defines the default reporting behavior for invalid input. A read deadline is outside this decision;
the implementation introduces no worker threads, cancellation policy, or timeouts.
