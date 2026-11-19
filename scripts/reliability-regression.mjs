import assert from "node:assert/strict";
import { recoveryAction } from "../admin-web/src/api/chatRecovery.ts";

const FIXTURE = "MTc2MzQyNDAwMDAwMHwwMDAwMDAwMC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDI";
const SAME = 1_763_424_000_000;
const LOWER = "00000000-0000-4000-8000-000000000001";
const HIGHER = "00000000-0000-4000-8000-000000000002";

function encode(millis, id) {
  return Buffer.from(`${millis}|${id}`, "utf8").toString("base64url");
}

function decode(cursor) {
  const raw = Buffer.from(cursor, "base64url").toString("utf8");
  const bar = raw.indexOf("|");
  if (bar <= 0) throw new Error("invalid cursor");
  return { millis: Number(raw.slice(0, bar)), id: raw.slice(bar + 1) };
}

const cursor = encode(SAME, HIGHER);
assert.equal(cursor, FIXTURE);
const decoded = decode(cursor);
assert.equal(decoded.millis, SAME);
assert.equal(decoded.id, HIGHER);
assert.ok(LOWER < HIGHER, "same timestamp orders by id");
assert.equal(decode(encode(SAME, LOWER)).id, LOWER);

assert.equal(recoveryAction("complete"), "load");
assert.equal(recoveryAction("running"), "wait");
assert.equal(recoveryAction("interrupted"), "wait");
assert.notEqual(recoveryAction("complete"), "resend");

console.log("reliability-regression ok");
