import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import {
  bearerId,
  derivedKey,
  envelope,
  hog,
  issue,
  newBearer,
  newMaster,
  payload,
  verify,
  type Candidate,
} from "./erasure-ownership-poc.ts";

const scope = "test:ownership-poc";
const now = 1_790_078_400;
const master = newMaster();

for (const mode of ["hmac", "bearer"] as const) {
  const owner = mode === "hmac" ? issue(master, scope) : newBearer(scope);
  const raw = envelope(payload(mode, owner, scope, now));
  test(`${mode}: possession authorizes precisely the credential-derived target`, () => {
    assert.equal(verify(mode, raw, scope, master, now)?.target, owner.id);
  });
  const malformed = [
    ["empty request", ""],
    ["missing credential", "{}"],
    ["public ID alone", envelope(owner.id)],
    [
      "duplicate JSON member",
      raw.replace('{"payload":', '{"payload":"ignored","payload":'),
    ],
    ["target override", raw.replace(/}$/, ',"target":"another-installation"}')],
    ["noncanonical whitespace", " " + raw],
    ["escaped newline", envelope(JSON.parse(raw).payload + "\n")],
    ["extra protocol field", envelope(JSON.parse(raw).payload + "|extra")],
    ["oversized request", "x".repeat(1201)],
    ["wrong audience", envelope(payload(mode, owner, "other:deployment", now))],
    ["expired request", envelope(payload(mode, owner, scope, now - 1000))],
    ["future request", envelope(payload(mode, owner, scope, now + 1000))],
    [
      "excess lifetime",
      envelope(payload(mode, owner, scope, now, randomUUID(), 901)),
    ],
    [
      "invalid operation",
      envelope(payload(mode, owner, scope, now, "not-a-uuid")),
    ],
  ];
  for (const [name, request] of malformed)
    test(`${mode}: rejects ${name}`, () => {
      assert.equal(verify(mode, request!, scope, master, now), null);
    });
  test(`${mode}: a persisted credential survives 400 days with no enrollment or event history`, () => {
    const restored = JSON.parse(JSON.stringify(owner));
    const later = now + 400 * 86400;
    assert.equal(
      verify(
        mode,
        envelope(payload(mode, restored, scope, later)),
        scope,
        master,
        later,
      )?.target,
      owner.id,
    );
  });
  test(`${mode}: stateless verification explicitly accepts an exact replay`, () => {
    assert.deepEqual(
      verify(mode, raw, scope, master, now + 1),
      verify(mode, raw, scope, master, now),
    );
  });
  test(`${mode}: native PoC contains no capture or privileged API operation`, () => {
    assert.doesNotMatch(hog(mode, scope), /\b(fetch|postHogCapture)\s*\(/);
  });
}

test("HMAC: changing the target or operation invalidates the proof", () => {
  const owner = issue(master, scope),
    other = issue(master, scope);
  const operation = randomUUID();
  const raw = envelope(payload("hmac", owner, scope, now, operation));
  assert.equal(
    verify("hmac", raw.replace(owner.id, other.id), scope, master, now),
    null,
  );
  assert.equal(
    verify("hmac", raw.replace(operation, randomUUID()), scope, master, now),
    null,
  );
  assert.equal(raw.includes(owner.secret), false);
});

test("HMAC: lost issuance response is abandoned before reporting; retry issues a fresh identity", () => {
  const abandoned = issue(master, scope),
    saved = issue(master, scope);
  assert.notEqual(abandoned.id, saved.id);
  assert.equal(saved.secret, derivedKey(master, scope, saved.id));
  assert.notEqual(saved.secret, derivedKey(newMaster(), scope, saved.id));
});

test("bearer: a random secret authorizes its own hash, never the victim's public ID", () => {
  const victim = newBearer(scope),
    attacker = newBearer(scope);
  const result = verify(
    "bearer",
    envelope(payload("bearer", attacker, scope, now)),
    scope,
    master,
    now,
  );
  assert.equal(result?.target, attacker.id);
  assert.notEqual(result?.target, victim.id);
  assert.notEqual(bearerId(victim.secret, "other:deployment"), victim.id);
});

test("bearer: a copied secret authorizes fresh requests, an explicit accepted tradeoff", () => {
  const owner = newBearer(scope);
  const original = payload("bearer", owner, scope, now);
  const copied = original.split("|")[3]!;
  const fresh = envelope(
    payload("bearer", { id: owner.id, secret: copied }, scope, now + 2000),
  );
  assert.equal(
    verify("bearer", fresh, scope, master, now + 2000)?.target,
    owner.id,
  );
});

test("configuration cannot inject code through audience", () => {
  for (const mode of ["hmac", "bearer"] as Candidate[])
    assert.throws(() => hog(mode, "';print('oops')"));
});
