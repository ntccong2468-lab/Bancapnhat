import { appendFile, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const VERSION = /^\d{1,5}\.\d{1,5}\.\d{1,5}$/;
const RELEASE_TAG = /^v(\d{1,5}\.\d{1,5}\.\d{1,5})$/;

function requireVersion(value, label) {
  if (typeof value !== "string" || !VERSION.test(value)) {
    throw new Error(`${label} must declare a MAJOR.MINOR.PATCH version`);
  }
  return value;
}

function requireMatch(content, pattern, label) {
  const match = content.match(pattern);
  if (!match) throw new Error(`${label} version declaration was not found`);
  return requireVersion(match[1], label);
}

export async function readSourceVersions(root) {
  const pom = await readFile(path.join(root, "pom.xml"), "utf8");

  return {
    "pom.xml project": requireMatch(pom, /<project[\s\S]*?<version>([^<]+)<\/version>/, "pom.xml project"),
    "pom.xml app.version": requireMatch(
      pom,
      /<app\.version>([^<]+)<\/app\.version>/,
      "pom.xml app.version",
    ),
  };
}

export async function resolveReleaseVersion({
  root = process.cwd(),
  refType = "",
  refName = "",
} = {}) {
  const declarations = await readSourceVersions(path.resolve(root));
  const versions = new Set(Object.values(declarations));
  if (versions.size !== 1) {
    const detail = Object.entries(declarations)
      .map(([label, version]) => `${label}=${version}`)
      .join(", ");
    throw new Error(`Version declarations do not match: ${detail}`);
  }

  const [version] = versions;
  const tag = `v${version}`;
  const shouldPublish = refType === "tag";
  if (shouldPublish) {
    const match = RELEASE_TAG.exec(refName);
    if (!match) throw new Error("Release tags must use vMAJOR.MINOR.PATCH");
    if (match[1] !== version) {
      throw new Error(`Release tag ${refName} does not match source version ${version}`);
    }
  }

  return { version, tag, shouldPublish, declarations };
}

async function main() {
  const release = await resolveReleaseVersion({
    root: process.argv[2] ?? process.cwd(),
    refType: process.env.GITHUB_REF_TYPE ?? "",
    refName: process.env.GITHUB_REF_NAME ?? "",
  });
  if (process.env.GITHUB_OUTPUT) {
    await appendFile(
      process.env.GITHUB_OUTPUT,
      `version=${release.version}\ntag=${release.tag}\nshould_publish=${release.shouldPublish}\n`,
      "utf8",
    );
  }
  process.stdout.write(`${release.version}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    process.stderr.write(`Could not resolve the WCode release version: ${error.message}\n`);
    process.exitCode = 1;
  });
}
