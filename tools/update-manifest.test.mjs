import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { buildSignedUpdateManifest } from "./update-manifest.mjs";

test("builds a canonical envelope whose signed MSI fields verify independently", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "wcode-manifest-test-"));
  try {
    const installer = path.join(directory, "WCode.msi");
    await writeFile(installer, Buffer.alloc(1024 * 1024, 0x5a), { mode: 0o600 });
    const keys = generateKeyPairSync("ed25519");
    const privateKey = keys.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
    const publicKey = keys.publicKey.export({ type: "spki", format: "der" }).toString("base64");

    const manifest = await buildSignedUpdateManifest({
      installer,
      version: "1.2.3",
      publishedAt: "2026-07-19T00:00:00.000Z",
      mandatory: false,
      notes: ["Signed JavaFX release"],
      privateKey,
      publicKey,
    });
    const envelope = JSON.parse(manifest);
    const payloadBytes = Buffer.from(envelope.payload, "base64");
    const payload = JSON.parse(payloadBytes.toString("utf8"));

    assert.equal(envelope.format, "wcode-update-envelope-v1");
    assert.equal(payload.version, "1.2.3");
    assert.deepEqual(payload.notes, ["Signed JavaFX release"]);
    assert.equal(payload.assets[0].fileName, "WCode.msi");
    assert.equal(payload.assets[0].size, 1024 * 1024);
    assert.match(payload.assets[0].sha256, /^[0-9a-f]{64}$/);
    assert.equal(
      payload.assets[0].url,
      "https://github.com/rupphi/relatest-wcode/releases/download/v1.2.3/WCode.msi",
    );
    assert.equal(verify(null, payloadBytes, keys.publicKey, Buffer.from(envelope.signature, "base64")), true);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("fails closed for mismatched keys, unsafe notes, versions, and installer sizes", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "wcode-manifest-invalid-"));
  try {
    const installer = path.join(directory, "WCode.msi");
    const undersizedInstaller = path.join(directory, "small", "WCode.msi");
    await writeFile(installer, Buffer.alloc(1024 * 1024, 0x2a), { mode: 0o600 });
    const keys = generateKeyPairSync("ed25519");
    const other = generateKeyPairSync("ed25519");
    const privateKey = keys.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
    const publicKey = other.publicKey.export({ type: "spki", format: "der" }).toString("base64");
    const common = {
      installer,
      version: "1.2.3",
      publishedAt: "2026-07-19T00:00:00.000Z",
      mandatory: false,
      notes: ["Signed JavaFX release"],
      privateKey,
      publicKey,
    };
    const matchingPublicKey = keys.publicKey.export({ type: "spki", format: "der" }).toString("base64");

    await assert.rejects(() => buildSignedUpdateManifest(common));
    await assert.rejects(() => buildSignedUpdateManifest({ ...common, publicKey: matchingPublicKey, version: "v1.2.3" }));
    await assert.rejects(() => buildSignedUpdateManifest({ ...common, publicKey: matchingPublicKey, notes: ["unsafe\nnote"] }));
    await mkdir(path.dirname(undersizedInstaller), { recursive: true });
    await writeFile(undersizedInstaller, Buffer.from("too small"), { mode: 0o600 });
    await assert.rejects(() => buildSignedUpdateManifest({
      ...common, installer: undersizedInstaller, publicKey: matchingPublicKey,
    }));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
