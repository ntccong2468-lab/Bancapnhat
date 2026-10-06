import { createHash, createPrivateKey, createPublicKey, sign, verify } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const VERSION = /^\d{1,5}\.\d{1,5}\.\d{1,5}$/;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const MIN_INSTALLER_BYTES = 1024 * 1024;
const MAX_INSTALLER_BYTES = 512 * 1024 * 1024;

function requireKey(value, name) {
  if (typeof value !== "string" || value.length < 40 || value.length > 256 || !BASE64.test(value)) {
    throw new Error(`${name} is not configured safely`);
  }
  return Buffer.from(value, "base64");
}

function normalizeTimestamp(value) {
  if (typeof value !== "string" || Number.isNaN(Date.parse(value))) {
    throw new Error("publishedAt is invalid");
  }
  return new Date(value).toISOString().replace(".000Z", "Z");
}

function validateNotes(notes) {
  if (!Array.isArray(notes) || notes.length > 20) throw new Error("release notes are invalid");
  for (const note of notes) {
    if (typeof note !== "string" || note.length === 0 || note.length > 500 || note !== note.trim()
      || Array.from(note).some((character) => character.charCodeAt(0) <= 31 || character.charCodeAt(0) === 127)) {
      throw new Error("release notes are invalid");
    }
  }
}

async function sha256(file) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest("hex");
}

export async function buildSignedUpdateManifest({
  installer,
  version,
  publishedAt,
  mandatory,
  notes,
  privateKey,
  publicKey,
}) {
  if (typeof installer !== "string" || path.basename(installer) !== "WCode.msi" || !VERSION.test(version)) {
    throw new Error("release identity is invalid");
  }
  if (typeof mandatory !== "boolean") throw new Error("mandatory must be a boolean");
  validateNotes(notes);
  const file = await stat(installer);
  if (!file.isFile() || file.size < MIN_INSTALLER_BYTES || file.size > MAX_INSTALLER_BYTES) {
    throw new Error("WCode.msi has an invalid size");
  }

  let signingKey;
  let verificationKey;
  try {
    signingKey = createPrivateKey({ key: requireKey(privateKey, "private key"), format: "der", type: "pkcs8" });
    verificationKey = createPublicKey({ key: requireKey(publicKey, "public key"), format: "der", type: "spki" });
  } catch {
    throw new Error("update signing keys are invalid");
  }
  if (signingKey.asymmetricKeyType !== "ed25519" || verificationKey.asymmetricKeyType !== "ed25519") {
    throw new Error("update signing keys must use Ed25519");
  }

  const payload = {
    schemaVersion: 1,
    version,
    publishedAt: normalizeTimestamp(publishedAt),
    mandatory,
    notes: [...notes],
    assets: [{
      platform: "windows-x64",
      kind: "msi",
      fileName: "WCode.msi",
      size: file.size,
      sha256: await sha256(installer),
      url: `https://github.com/rupphi/relatest-wcode/releases/download/v${version}/WCode.msi`,
    }],
  };
  const payloadBytes = Buffer.from(JSON.stringify(payload), "utf8");
  if (payloadBytes.length > 64 * 1024) throw new Error("update payload is too large");
  const signature = sign(null, payloadBytes, signingKey);
  if (signature.length !== 64 || !verify(null, payloadBytes, verificationKey, signature)) {
    throw new Error("update signing keys do not match");
  }
  const envelope = `${JSON.stringify({
    format: "wcode-update-envelope-v1",
    payload: payloadBytes.toString("base64"),
    signature: signature.toString("base64"),
  })}\n`;
  if (Buffer.byteLength(envelope, "utf8") > 128 * 1024) throw new Error("update envelope is too large");
  return envelope;
}

async function main() {
  const [installer, output, version] = process.argv.slice(2);
  if (!installer || !output || !version) throw new Error("usage: update-manifest.mjs <WCode.msi> <output> <version>");
  let notes;
  try {
    notes = JSON.parse(process.env.RELEASE_NOTES_JSON ?? "[]");
  } catch {
    throw new Error("RELEASE_NOTES_JSON is invalid");
  }
  const mandatoryText = process.env.UPDATE_MANDATORY ?? "false";
  if (mandatoryText !== "true" && mandatoryText !== "false") throw new Error("UPDATE_MANDATORY is invalid");
  const envelope = await buildSignedUpdateManifest({
    installer,
    version,
    publishedAt: process.env.RELEASE_PUBLISHED_AT ?? new Date().toISOString(),
    mandatory: mandatoryText === "true",
    notes,
    privateKey: process.env.UPDATE_MANIFEST_PRIVATE_KEY,
    publicKey: process.env.UPDATE_MANIFEST_PUBLIC_KEY,
  });
  await mkdir(path.dirname(path.resolve(output)), { recursive: true });
  await writeFile(output, envelope, { encoding: "utf8", mode: 0o600 });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    process.stderr.write(`Could not create the signed update manifest: ${error.message}\n`);
    process.exitCode = 1;
  });
}
