import assert from "node:assert/strict";
import {KeyObject, sign} from "node:crypto";
import test from "node:test";
import {calculateJwkThumbprint} from "jose";
import {
  ALGORITHM, freshClaims, installationIdOf, MAX_CLOCK_SKEW_SECONDS, MAX_LIFETIME_SECONDS,
  newInstallationKey, RejectedRequest, signRequest, TOKEN_TYPE, verifyRequest, type PublicJwk,
} from "./erasure-protocol-reference.ts";

const AUDIENCE = "symphony-trello/erasure/test-example";
const NOW = 1_790_078_400;
const EXPECTED = {action: "erase", audience: AUDIENCE, now: NOW} as const;
const key = await newInstallationKey();
const claims = freshClaims("erase", key.installationId, AUDIENCE, NOW);
const header = {alg: ALGORITHM, typ: TOKEN_TYPE, jwk: key.publicJwk};
const BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const noncanonicalCoordinate = key.publicJwk.x.slice(0, -1)
  + BASE64URL_ALPHABET[BASE64URL_ALPHABET.indexOf(key.publicJwk.x.at(-1)!) + 1];

// Node's maintained ECDSA implementation signs deliberately malformed JSON bytes in reject tests.
// No hand-written signature conversion or private key fixture is used.
function signedBytes(headerText = JSON.stringify(header), payloadText = JSON.stringify(claims)): string {
  const input = `${Buffer.from(headerText).toString("base64url")}.${Buffer.from(payloadText).toString("base64url")}`;
  const signature = sign("sha256", Buffer.from(input), {key: KeyObject.from(key.privateKey), dsaEncoding: "ieee-p1363"});
  return `${input}.${signature.toString("base64url")}`;
}

// RFC 7515 appendix A.3 public key. The expected thumbprint and ID are reusable language-neutral values.
const FIXED_JWK: PublicJwk = {
  kty: "EC", crv: "P-256",
  x: "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
  y: "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0",
};

test("RFC public-key vector derives the fixed thumbprint and UUIDv8", async () => {
  // given / when
  const thumbprint = await calculateJwkThumbprint(FIXED_JWK, "sha256");
  const id = await installationIdOf(FIXED_JWK);

  // then
  assert.equal(thumbprint, "oKIywvGUpTVTyxMQ3bwIIeQUudfr_CkLMjCE19ECD-U");
  assert.equal(id, "8f7c6b60-f496-8631-a144-8c0a1ab0c9b5");
});

for (const action of ["erase", "status"] as const) {
  test(`valid ${action} request binds the subject to its key`, async () => {
    // given
    const requestClaims = freshClaims(action, key.installationId, AUDIENCE, NOW);

    // when
    const token = await signRequest(key.privateKey, key.publicJwk, requestClaims);
    const verified = await verifyRequest(token, {...EXPECTED, action});

    // then
    assert.deepEqual(verified, {installationId: key.installationId, claims: requestClaims});
  });
}

for (const [name, headerText, payloadText] of [
  ["duplicate algorithm", JSON.stringify(header).replace('"alg":"ES256"', '"alg":"none","alg":"ES256"'), JSON.stringify(claims)],
  ["duplicate JWK coordinate", JSON.stringify(header).replace('"x":', '"x":"bad","x":'), JSON.stringify(claims)],
  ["duplicate subject", JSON.stringify(header), JSON.stringify(claims).replace('"sub":', '"sub":"victim","sub":')],
  ["escaped duplicate member", JSON.stringify(header), JSON.stringify(claims).replace('"sub":', '"\\u0073ub":"victim","sub":')],
  ["duplicate time", JSON.stringify(header), JSON.stringify(claims).replace('"iat":', '"iat":0,"iat":')],
  ["whitespace", JSON.stringify(header), JSON.stringify(claims, null, 2)],
  ["header member order", JSON.stringify({typ: TOKEN_TYPE, alg: ALGORITHM, jwk: key.publicJwk}), JSON.stringify(claims)],
  ["claim member order", JSON.stringify(header), JSON.stringify({iat: claims.iat, action: claims.action, sub: claims.sub, aud: claims.aud, jti: claims.jti, exp: claims.exp})],
  ["escaped value", JSON.stringify(header), JSON.stringify(claims).replace('"erase"', '"\\u0065rase"')],
  ["exponent time", JSON.stringify(header), JSON.stringify(claims).replace(String(NOW), `${NOW / 100}e2`)],
  ["malformed claims", JSON.stringify(header), "{"],
  ["array claims", JSON.stringify(header), "[]"],
  ["null claims", JSON.stringify(header), "null"],
] as const) {
  test(`rejects signed ${name}`, async () => {
    // given
    const token = signedBytes(headerText, payloadText);

    // when / then
    await assert.rejects(verifyRequest(token, EXPECTED), RejectedRequest);
  });
}

for (const [name, value] of Object.entries({
  project_id: "caller-project", person_id: "caller-person", url: "https://example.invalid/",
  query: "SELECT 1", callback: "https://example.invalid/", event_uuid: "injected-public-event",
  private_key: "inert-private-marker", nbf: NOW, timestamp: NOW,
})) {
  test(`rejects signed caller field ${name}`, async () => {
    // given
    const token = signedBytes(undefined, JSON.stringify({...claims, [name]: value}));

    // when / then
    await assert.rejects(verifyRequest(token, EXPECTED), RejectedRequest);
  });
}

for (const [name, value] of Object.entries({
  jku: "https://example.invalid/keys", x5u: "https://example.invalid/cert", kid: "untrusted",
  crit: [], b64: true, x5c: [], project_id: "caller-project", private_key: "inert-private-marker",
})) {
  test(`rejects signed header directive ${name}`, async () => {
    // given
    const token = signedBytes(JSON.stringify({...header, [name]: value}));

    // when / then
    await assert.rejects(verifyRequest(token, EXPECTED), RejectedRequest);
  });
}

for (const [name, jwk] of Object.entries({
  private: {...key.publicJwk, d: "inert-private-marker"}, optional: {...key.publicJwk, use: "sig"},
  padded: {...key.publicJwk, x: `${key.publicJwk.x}=`}, short: {...key.publicJwk, x: "AA"},
  noncanonical: {...key.publicJwk, x: noncanonicalCoordinate},
  wrongCurve: {...key.publicJwk, crv: "P-384"}, wrongType: {...key.publicJwk, kty: "RSA"},
  offCurve: {...key.publicJwk, x: "A".repeat(43), y: "A".repeat(43)},
  missing: undefined, null: null, array: [],
})) {
  test(`rejects ${name} public key`, async () => {
    // given
    const token = signedBytes(JSON.stringify({...header, jwk}));

    // when / then
    await assert.rejects(verifyRequest(token, EXPECTED), RejectedRequest);
  });
}

for (const [name, patch] of Object.entries({
  missingIat: {iat: undefined}, missingExp: {exp: undefined}, nullIat: {iat: null}, stringExp: {exp: String(NOW)},
  fractionalIat: {iat: NOW + 0.5}, negativeIat: {iat: -1, exp: 1},
  future: {iat: NOW + MAX_CLOCK_SKEW_SECONDS + 1, exp: NOW + 120},
  expired: {iat: NOW - 1000, exp: NOW - MAX_CLOCK_SKEW_SECONDS},
  zeroLifetime: {exp: NOW}, negativeLifetime: {exp: NOW - 1}, overlong: {exp: NOW + MAX_LIFETIME_SECONDS + 1},
  missingJti: {jti: undefined}, emptyJti: {jti: ""}, arbitraryJti: {jti: "request-1"},
  nilJti: {jti: "00000000-0000-0000-0000-000000000000"}, uppercaseJti: {jti: "A1234567-1234-4234-8234-123456789abc"},
  wrongVersionJti: {jti: "a1234567-1234-1234-8234-123456789abc"},
  wrongAudience: {aud: `${AUDIENCE}/different-deployment`}, wrongAction: {action: "status"},
  upperSubject: {sub: key.installationId.toUpperCase()},
})) {
  test(`rejects claims ${name}`, async () => {
    // given
    const token = signedBytes(undefined, JSON.stringify({...claims, ...patch}));

    // when / then
    await assert.rejects(verifyRequest(token, EXPECTED), RejectedRequest);
  });
}

for (const field of ["iat", "exp"] as const) {
  test(`rejects non-finite ${field}`, async () => {
    // given
    const payload = JSON.stringify(claims).replace(new RegExp(`"${field}":\\d+`), `"${field}":1e999`);

    // when / then
    await assert.rejects(verifyRequest(signedBytes(undefined, payload), EXPECTED), RejectedRequest);
  });
}

for (const now of [NaN, Infinity, -Infinity, -1]) {
  test(`rejects invalid trusted time ${now}`, async () => {
    // given
    const token = signedBytes();

    // when / then
    await assert.rejects(verifyRequest(token, {...EXPECTED, now}), RejectedRequest);
  });
}

for (const [name, token] of Object.entries({
  oversized: "a".repeat(4097), empty: "", segments: "a.b", invalidBase64: "!.a.b", malformedHeader: `ew.e30.${Buffer.alloc(64).toString("base64url")}`,
  zeroSignature: `${signedBytes().split(".").slice(0, 2).join(".")}.${Buffer.alloc(64).toString("base64url")}`,
  emptySignature: `${signedBytes().split(".").slice(0, 2).join(".")}.`,
  shortSignature: `${signedBytes().split(".").slice(0, 2).join(".")}.AA`,
  paddedSignature: `${signedBytes()}=`,
  none: signedBytes(JSON.stringify({...header, alg: "none"})),
  hmacConfusion: signedBytes(JSON.stringify({...header, alg: "HS256"})),
  wrongPurpose: signedBytes(JSON.stringify({...header, typ: "JWT"})),
})) {
  test(`rejects ${name} token`, async () => {
    // given / when / then
    await assert.rejects(verifyRequest(token, EXPECTED), RejectedRequest);
  });
}

test("attacker key and injected event IDs cannot authorize a victim subject", async () => {
  // given
  const victim = await newInstallationKey();
  const attackerClaims = {...claims, sub: victim.installationId};
  const signed = await signRequest(key.privateKey, key.publicJwk, attackerClaims);
  const substituted = await signRequest(key.privateKey, victim.publicJwk, attackerClaims);
  const injected = signedBytes(undefined, JSON.stringify({...attackerClaims, event_uuid: "attacker-chosen-public-event"}));

  // when / then
  await assert.rejects(verifyRequest(signed, EXPECTED), /subject is not the key's installation id/);
  await assert.rejects(verifyRequest(substituted, EXPECTED), /signature verification failed/);
  await assert.rejects(verifyRequest(injected, EXPECTED), RejectedRequest);
});

test("payload tampering fails the signature check", async () => {
  // given
  const [protectedHeader, , signature] = signedBytes().split(".");
  const tampered = `${protectedHeader}.${Buffer.from(JSON.stringify({...claims, action: "status"})).toString("base64url")}.${signature}`;

  // when / then
  await assert.rejects(verifyRequest(tampered, EXPECTED), /signature verification failed/);
});

for (const now of [NOW - MAX_CLOCK_SKEW_SECONDS, NOW + MAX_LIFETIME_SECONDS + MAX_CLOCK_SKEW_SECONDS - 1]) {
  test(`accepts time inside the admission window at ${now}`, async () => {
    // given
    const token = signedBytes();

    // when
    const result = await verifyRequest(token, {...EXPECTED, now});

    // then
    assert.deepEqual(result.claims, claims);
  });
}

test("stateless verification accepts replay and re-signing; it supplies no durable admission guarantee", async () => {
  // given
  const first = signedBytes();
  const resigned = signedBytes();

  // when
  const results = await Promise.all([first, first, resigned].map(token => verifyRequest(token, EXPECTED)));

  // then
  assert.deepEqual(results, Array(3).fill({installationId: key.installationId, claims}));
});

// Stable, synthetic signed vector for another implementation to consume. The generating key was
// ephemeral and its private half was never written. The token is historical test data only.
const SIGNED_VECTOR = {
  publicJwk: {
    kty: "EC", crv: "P-256",
    x: "J-mr8991N3te6j7UxEoghkneZVHQ7IU3DoTaNXlhupo",
    y: "eNa0zzp9hpNG-9l434-QMPUFDb5LKYgYScJd5rkfh_Y",
  },
  claims: {
    action: "erase", sub: "51e1c990-c4f6-8194-9f0c-13c664db85e7", aud: AUDIENCE,
    jti: "a1234567-1234-4234-8234-123456789abc", iat: NOW, exp: NOW + MAX_LIFETIME_SECONDS,
  },
  signature: "o5RB0ockAHHmJN-Spaxw5Xiw83spLUlkHeH6n8DSlykHlkT4Qq9m12dxvWeJuWYeI4o5m1Ku_1fgj6c95PgiKA",
} as const;

test("fixed ES256 token and public key verify at the fixture time", async () => {
  // given / when
  const id = await installationIdOf(SIGNED_VECTOR.publicJwk);
  const header = {alg: ALGORITHM, typ: TOKEN_TYPE, jwk: SIGNED_VECTOR.publicJwk};
  const token = [header, SIGNED_VECTOR.claims].map(value => Buffer.from(JSON.stringify(value)).toString("base64url")).join(".") + "." + SIGNED_VECTOR.signature;
  const result = await verifyRequest(token, EXPECTED);

  // then
  assert.equal(id, SIGNED_VECTOR.claims.sub);
  assert.deepEqual(result, {installationId: id, claims: SIGNED_VECTOR.claims});
});
