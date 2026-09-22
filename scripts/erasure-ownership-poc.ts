// Development-only authentication comparison. No captures, deletion calls or durable admission.
import {
  createHash,
  createHmac,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";

export type Candidate = "hmac" | "bearer";
export interface Credential {
  readonly id: string;
  readonly secret: string;
}
export interface Proof {
  readonly target: string;
  readonly operation: string;
}
export const UUID_PATTERN =
  "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const UUID = new RegExp(`^${UUID_PATTERN}$`);
const HEX = /^[0-9a-f]{64}$/;
const HMAC_ID = new RegExp(`^h1-k1-${UUID_PATTERN}$`);
export const WINDOW = 900;
export const SKEW = 60;

function audience(value: string): string {
  if (value.length < 1 || value.length > 80 || /[^a-z0-9:-]/.test(value))
    throw new Error("Invalid proof audience");
  return value;
}
function hmac(key: string, value: string): string {
  return createHmac("sha256", key).update(value, "utf8").digest("hex");
}
export function newMaster(): string {
  return randomBytes(32).toString("hex");
}
export function derivedKey(master: string, scope: string, id: string): string {
  if (!HEX.test(master) || !HMAC_ID.test(id))
    throw new Error("Invalid derivation input");
  return hmac(master, `symphony-trello/owner/v1|${audience(scope)}|${id}`);
}
export function issue(master: string, scope: string): Credential {
  const id = `h1-k1-${randomUUID()}`;
  return { id, secret: derivedKey(master, scope, id) };
}
export function bearerId(secret: string, scope: string): string {
  if (!HEX.test(secret)) throw new Error("Invalid bearer secret");
  return (
    "b1-" +
    createHash("sha256")
      .update(`symphony-trello/bearer/v1|${audience(scope)}|${secret}`)
      .digest("hex")
  );
}
export function newBearer(scope: string): Credential {
  const secret = newMaster();
  return { id: bearerId(secret, scope), secret };
}
export function payload(
  mode: Candidate,
  credential: Credential,
  scope: string,
  now: number,
  operation: string = randomUUID(),
  lifetime = WINDOW,
): string {
  const subject = mode === "hmac" ? credential.id : credential.secret;
  const unsigned = [
    mode === "hmac" ? "h1" : "b1",
    "erase",
    audience(scope),
    subject,
    operation,
    now,
    now + lifetime,
  ].join("|");
  return mode === "hmac"
    ? `${unsigned}|${hmac(credential.secret, unsigned)}`
    : unsigned;
}
export function envelope(value: string): string {
  return JSON.stringify({ payload: value });
}

/** Stateless by design: successful authentication is not acceptance of an erasure job. */
export function verify(
  mode: Candidate,
  raw: string,
  scope: string,
  master: string,
  now: number,
): Proof | null {
  if (raw.length > 1200) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (
    typeof value !== "object" ||
    value === null ||
    !("payload" in value) ||
    typeof value.payload !== "string" ||
    envelope(value.payload) !== raw
  )
    return null;
  if (/[^a-z0-9:|.-]/.test(value.payload)) return null;
  const parts = value.payload.split("|");
  if (parts.length !== (mode === "hmac" ? 8 : 7)) return null;
  const [
    version,
    action,
    aud,
    subject = "",
    operation = "",
    issued = "",
    expires = "",
    mac = "",
  ] = parts;
  if (
    version !== (mode === "hmac" ? "h1" : "b1") ||
    action !== "erase" ||
    aud !== audience(scope) ||
    !UUID.test(operation) ||
    !/^[0-9]{10}$/.test(issued) ||
    !/^[0-9]{10}$/.test(expires)
  )
    return null;
  const iat = Number(issued),
    exp = Number(expires);
  if (exp <= iat || exp - iat > WINDOW || iat > now + SKEW || exp < now - SKEW)
    return null;
  if (mode === "bearer")
    return HEX.test(subject)
      ? { target: bearerId(subject, scope), operation }
      : null;
  if (!HMAC_ID.test(subject) || !HEX.test(mac)) return null;
  const expected = hmac(
    derivedKey(master, scope, subject),
    parts.slice(0, 7).join("|"),
  );
  return timingSafeEqual(Buffer.from(mac, "hex"), Buffer.from(expected, "hex"))
    ? { target: subject, operation }
    : null;
}

/** Native source function. Only returns dry-run authorization, never calls privileged APIs. */
export function hog(mode: Candidate | "issue", scope: string): string {
  audience(scope);
  const reject =
    "return {'httpResponse': {'status': 401, 'body': {'authorized': false, 'dryRun': true}}};";
  const prefix = `
if (request.method != 'POST') { return {'httpResponse': {'status': 405, 'body': 'POST required'}}; }
if (length(request.stringBody) > 1200) { ${reject} }
if (typeof(request.body.payload) != 'string') { ${reject} }
let p := request.body.payload;
if (request.stringBody != concat('{"payload":"', p, '"}')) { ${reject} }
`;
  if (mode === "issue")
    return (
      prefix +
      `
if (p != 'issue-v1') { ${reject} }
let id := concat('h1-k1-', generateUUIDv4());
let secret := sha256HmacChainHex([inputs.master, concat('symphony-trello/owner/v1|${scope}|', id)]);
return {'httpResponse': {'status': 200, 'body': {'id': id, 'secret': secret, 'dryRun': true}}};
`
    );
  return (
    prefix +
    `
let parts := splitByString('|', p);
if (length(parts) != ${mode === "hmac" ? 8 : 7}) { ${reject} }
if (parts[1] != '${mode === "hmac" ? "h1" : "b1"}' or parts[2] != 'erase' or parts[3] != '${scope}') { ${reject} }
if (not match(parts[5], '^${UUID_PATTERN}$')) { ${reject} }
if (not match(parts[6], '^[0-9]{10}$') or not match(parts[7], '^[0-9]{10}$')) { ${reject} }
let iat := toInt(parts[6]);
let exp := toInt(parts[7]);
let current := toUnixTimestamp(now());
if (exp <= iat or exp - iat > ${WINDOW} or iat > current + ${SKEW} or exp < current - ${SKEW}) { ${reject} }
${
  mode === "hmac"
    ? `
if (not match(parts[4], '^h1-k1-${UUID_PATTERN}$') or not match(parts[8], '^[0-9a-f]{64}$')) { ${reject} }
let key := sha256HmacChainHex([inputs.master, concat('symphony-trello/owner/v1|${scope}|', parts[4])]);
let unsigned := arrayStringConcat([parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7]], '|');
let expected := sha256HmacChainHex([key, unsigned]);
// Blind both fixed-length MACs before equality; no raw expected-MAC prefix is compared.
if (sha256HmacChainHex([inputs.master, expected]) != sha256HmacChainHex([inputs.master, parts[8]])) { ${reject} }
let target := parts[4];
`
    : `
if (not match(parts[4], '^[0-9a-f]{64}$')) { ${reject} }
let target := concat('b1-', sha256Hex(concat('symphony-trello/bearer/v1|${scope}|', parts[4])));
`
}
return {'httpResponse': {'status': 200, 'body': {'authorized': true, 'dryRun': true, 'target': target, 'operation': parts[5]}}};
`
  );
}
