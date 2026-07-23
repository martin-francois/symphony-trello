# `UseJavaUtilBase64` Leaves Calls That Do Not Compile

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-migrate-java>
- Artifact: `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Pinned commit: `658481254a6ee678f5f162e51d8d49ee01c75877`
- Current `main` reproduced at: `9b7a874bfe860b22c9a103dadb1c55719fdfd39c`
- Recipe: `org.openrewrite.java.migrate.UseJavaUtilBase64`
- Implementation: `src/main/java/org/openrewrite/java/migrate/UseJavaUtilBase64.java`

## Reproduction

Add the fixture below to `UseJavaUtilBase64OverloadProbeTest` and run:

```text
./gradlew test \
  --tests '*UseJavaUtilBase64OverloadProbeTest' \
  --no-daemon --configure-on-demand
```

The output assertion passes on both tested revisions. Compiling the actual output with
`javac --release 11` reports 13 missing or incompatible methods.

The test parser supplies equivalent `CharacterEncoder`, `CharacterDecoder`, `BASE64Encoder`, and
`BASE64Decoder` stubs under `test.sun.misc`, then passes that package to the recipe's test-only
constructor. This avoids relying on JDK-internal classes while exercising the production visitor.

## Failure case

**Before**

```java
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import test.sun.misc.BASE64Decoder;
import test.sun.misc.BASE64Encoder;

class Test {
    void encode(InputStream stream, OutputStream output, byte[] bytes, ByteBuffer buffer)
            throws IOException {
        BASE64Encoder encoder = new BASE64Encoder();
        encoder.encode(stream, output);
        encoder.encode(bytes, output);
        encoder.encode(buffer, output);
        String encoded = encoder.encode(buffer);
        encoder.encodeBuffer(stream, output);
        encoder.encodeBuffer(bytes, output);
        encoder.encodeBuffer(buffer, output);
        encoded += encoder.encodeBuffer(buffer);
    }

    void decode(InputStream stream, OutputStream output, String text) throws IOException {
        BASE64Decoder decoder = new BASE64Decoder();
        decoder.decode(stream, output);
        decoder.decode(text, output);
        decoder.decodeBuffer(stream, output);
        decoder.decodeBuffer(text, output);
        byte[] first = decoder.decodeBuffer(stream);
    }
}
```

**Actual output**

```java
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;

class Test {
    void encode(InputStream stream, OutputStream output, byte[] bytes, ByteBuffer buffer)
            throws IOException {
        Base64.Encoder encoder = Base64.getEncoder();
        encoder.encode(stream, output);
        encoder.encode(bytes, output);
        encoder.encode(buffer, output);
        String encoded = encoder.encode(buffer);
        encoder.encodeBuffer(stream, output);
        encoder.encodeBuffer(bytes, output);
        encoder.encodeBuffer(buffer, output);
        encoded += encoder.encodeBuffer(buffer);
    }

    void decode(InputStream stream, OutputStream output, String text) throws IOException {
        Base64.Decoder decoder = Base64.getDecoder();
        decoder.decode(stream, output);
        decoder.decode(text, output);
        decoder.decodeBuffer(stream, output);
        decoder.decodeBuffer(text, output);
        byte[] first = decoder.decodeBuffer(stream);
    }
}
```

**Expected output**

The recipe MUST either translate every supported legacy overload to a compiling JDK equivalent or
leave the compilation unit unchanged. It MUST NOT change the declared receiver types while leaving
calls that do not exist on `Base64.Encoder` or `Base64.Decoder`.

## Root cause

The visitor changes the encoder and decoder types for the compilation unit, but its method rewrites
cover only a subset of the inherited legacy overloads. The remaining calls stay attached to the new
JDK receiver types.

## Regression test plan

Add one upstream `RewriteTest` containing the complete overload matrix above. Compile the generated
source or keep after-recipe type validation enabled. Add no-change controls for compilation units
that use an unsupported overload until the recipe implements its replacement.

## Symphony acceptance criteria

Keep the recipe inactive until a released version handles the complete overload matrix, the focused
fixture passes, the generated source compiles, and Symphony for Trello's complete ordered maintenance
lane is green and idempotent.
