// LOCAL reference for a proposed key-bound erasure protocol. This is not application code or
// proof of PostHog-native execution. It verifies signatures and target binding only. It has no
// durable acceptance, replay/conflict detection, lifecycle state, or privileged side effects.
//
// Prototype representation restriction: compact JWS segments use unpadded canonical base64url.
// Header JSON is exactly JSON.stringify({alg, typ, jwk: {kty, crv, x, y}}); claims JSON is exactly
// JSON.stringify({action, sub, aud, jti, iat, exp}) in those orders. Whitespace, duplicate members,
// alternate escapes/numeric encodings, and extra fields are rejected, even with valid signatures.
// JSON.parse plus an exact comparison against the fixed schema avoids a custom JSON parser.
// This is deliberately narrower than general JOSE JSON and is not an RFC 8785 implementation.

import {createHash, randomUUID} from "node:crypto";
import {calculateJwkThumbprint, compactVerify, CompactSign, decodeProtectedHeader, exportJWK, generateKeyPair, importJWK, type CryptoKey, type JWK} from "jose";

export const PROTOCOL_VERSION = "symphony-trello/installation-id/v1";
export const ALGORITHM = "ES256";
export const TOKEN_TYPE = "symphony-erasure+jwt";
export const MAX_TOKEN_BYTES = 4096;
export const MAX_LIFETIME_SECONDS = 15 * 60;
export const MAX_CLOCK_SKEW_SECONDS = 60;
export const ACTIONS = ["erase", "status"] as const;
export type Action = (typeof ACTIONS)[number];

export interface PublicJwk {
  readonly kty: "EC";
  readonly crv: "P-256";
  readonly x: string;
  readonly y: string;
}

/** installation_id = UUIDv8(first 16 bytes of SHA-256(namespace || 0x00 || RFC7638 thumbprint bytes)). */
export async function installationIdOf(publicJwk: PublicJwk): Promise<string> {
  const validatedJwk = publicParameters(publicJwk);
  await importJWK(validatedJwk, ALGORITHM);
  const thumbprintBase64Url = await calculateJwkThumbprint(validatedJwk, "sha256");
  const thumbprintBytes = Buffer.from(thumbprintBase64Url, "base64url");
  if (thumbprintBytes.length !== 32) {
    throw new Error("thumbprint must decode to 32 bytes");
  }
  const namespace = Buffer.from(`${PROTOCOL_VERSION}\u0000`, "utf8");
  const digest = createHash("sha256").update(Buffer.concat([namespace, thumbprintBytes])).digest();
  const bytes = Buffer.from(digest.subarray(0, 16));
  bytes[6] = ((bytes[6] as number) & 0x0f) | 0x80; // RFC 9562 version 8
  bytes[8] = ((bytes[8] as number) & 0x3f) | 0x80; // RFC 9562 variant
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export interface Claims {
  readonly action: Action;
  readonly sub: string;
  readonly aud: string;
  readonly jti: string;
  readonly iat: number;
  readonly exp: number;
}

export async function newInstallationKey(): Promise<{privateKey: CryptoKey; publicJwk: PublicJwk; installationId: string}> {
  const {privateKey, publicKey} = await generateKeyPair(ALGORITHM, {extractable: true});
  const exported = await exportJWK(publicKey);
  const publicJwk: PublicJwk = {kty: "EC", crv: "P-256", x: exported.x as string, y: exported.y as string};
  return {privateKey, publicJwk, installationId: await installationIdOf(publicJwk)};
}

export async function signRequest(privateKey: CryptoKey, publicJwk: PublicJwk, claims: Claims): Promise<string> {
  return new CompactSign(Buffer.from(JSON.stringify(orderedClaims(claims)), "utf8"))
    .setProtectedHeader({alg: ALGORITHM, typ: TOKEN_TYPE, jwk: publicParameters(publicJwk)})
    .sign(privateKey);
}

export interface Verified {
  readonly installationId: string;
  readonly claims: Claims;
}

export class RejectedRequest extends Error {}

function canonicalBase64Url(value: unknown, size?: number): value is string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) return false;
  const bytes = Buffer.from(value, "base64url");
  return bytes.toString("base64url") === value && (size === undefined || bytes.length === size);
}

function publicParameters(value: unknown): PublicJwk {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new RejectedRequest("public key missing or of the wrong type");
  }
  const jwk = value as JWK;
  if (Object.keys(jwk).some(member => !["kty", "crv", "x", "y"].includes(member))) {
    throw new RejectedRequest("public key carries members beyond the public parameters");
  }
  if (jwk.kty !== "EC" || jwk.crv !== "P-256" || !canonicalBase64Url(jwk.x, 32) || !canonicalBase64Url(jwk.y, 32)) {
    throw new RejectedRequest("invalid public key type, curve, or coordinate encoding");
  }
  return {kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y};
}

function orderedClaims(claims: Claims): Claims {
  return {action: claims.action, sub: claims.sub, aud: claims.aud, jti: claims.jti, iat: claims.iat, exp: claims.exp};
}

/** Stateless local verification only. UUID syntax does not establish jti freshness. A hosted
 * implementation would still need trusted durable admission and conflict detection before work. */
export async function verifyRequest(token: string, expected: {readonly action: Action; readonly audience: string; readonly now: number}): Promise<Verified> {
  if (!Number.isSafeInteger(expected.now) || expected.now < 0 || !ACTIONS.includes(expected.action) || !expected.audience) {
    throw new RejectedRequest("invalid verifier policy or trusted time");
  }
  if (typeof token !== "string" || Buffer.byteLength(token, "utf8") > MAX_TOKEN_BYTES) {
    throw new RejectedRequest("oversized or non-string request");
  }
  const segments = token.split(".");
  if (segments.length !== 3 || !segments.every(segment => canonicalBase64Url(segment)) || !canonicalBase64Url(segments[2], 64)) {
    throw new RejectedRequest("malformed compact JWS encoding");
  }
  let header;
  try {
    header = decodeProtectedHeader(token);
  } catch {
    throw new RejectedRequest("malformed protected header");
  }
  if (header.alg !== ALGORITHM || header.typ !== TOKEN_TYPE) {
    throw new RejectedRequest("unsupported algorithm or type");
  }
  const publicJwk = publicParameters(header.jwk);
  const canonicalHeader = JSON.stringify({alg: ALGORITHM, typ: TOKEN_TYPE, jwk: publicJwk});
  if (Buffer.from(canonicalHeader).toString("base64url") !== segments[0]) {
    throw new RejectedRequest("noncanonical header or unsupported header directive");
  }
  let publicKey: CryptoKey | Uint8Array;
  try {
    publicKey = await importJWK(publicJwk, ALGORITHM);
  } catch {
    throw new RejectedRequest("invalid public key");
  }
  let payload: Uint8Array;
  try {
    ({payload} = await compactVerify(token, publicKey, {algorithms: [ALGORITHM]}));
  } catch {
    throw new RejectedRequest("signature verification failed");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.from(payload).toString("utf8"));
  } catch {
    throw new RejectedRequest("claims are not JSON");
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new RejectedRequest("claims are not an object");
  }
  const claims = parsed as Record<string, unknown>;
  const installationId = await installationIdOf(publicJwk);
  if (claims["sub"] !== installationId) {
    throw new RejectedRequest("subject is not the key's installation id");
  }
  if (claims["action"] !== expected.action || claims["aud"] !== expected.audience) {
    throw new RejectedRequest("action or audience mismatch");
  }
  if (typeof claims["jti"] !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(claims["jti"])) {
    throw new RejectedRequest("request id must be a canonical UUIDv4");
  }
  const iat = claims["iat"];
  const exp = claims["exp"];
  if (typeof iat !== "number" || typeof exp !== "number" || !Number.isSafeInteger(iat) || !Number.isSafeInteger(exp) || iat < 0 || exp < 0) {
    throw new RejectedRequest("time claims must be nonnegative safe integer seconds");
  }
  if (exp <= iat || exp - iat > MAX_LIFETIME_SECONDS) {
    throw new RejectedRequest("lifetime out of bounds");
  }
  if (iat > expected.now + MAX_CLOCK_SKEW_SECONDS || exp <= expected.now - MAX_CLOCK_SKEW_SECONDS) {
    throw new RejectedRequest("request not yet valid or expired");
  }
  const validated: Claims = {action: expected.action, sub: installationId, aud: expected.audience, jti: claims["jti"], iat, exp};
  if (!Buffer.from(JSON.stringify(validated), "utf8").equals(Buffer.from(payload))) {
    throw new RejectedRequest("noncanonical claims or unsupported field");
  }
  return {installationId, claims: validated};
}

export function freshClaims(action: Action, sub: string, audience: string, now: number): Claims {
  return {action, sub, aud: audience, jti: randomUUID(), iat: now, exp: now + MAX_LIFETIME_SECONDS};
}
