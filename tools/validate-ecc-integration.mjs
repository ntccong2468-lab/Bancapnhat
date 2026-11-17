import { constants } from 'node:fs';
import { lstat, open, readdir, realpath } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

export const ECC_REPOSITORY = 'https://github.com/affaan-m/ECC';
export const ECC_COMMIT = 'd2ef2680594f9a17526a6e1a2e18b3d242c5d17b';
const manifestPath = 'docs/engineering/ecc/integration-manifest.json';
const skillNames = ['vncode-ecc-review', 'vncode-ecc-security', 'vncode-ecc-tdd-verification'];
const selectedSources = [
  'agents/code-reviewer.md', 'skills/change-impact/SKILL.md', 'skills/module-regression/SKILL.md',
  'skills/security-review/SKILL.md', 'skills/mcp-dependency-review/SKILL.md',
  'skills/tdd-workflow/SKILL.md', 'skills/verification-loop/SKILL.md',
  'skills/agent-introspection-debugging/SKILL.md', 'skills/frontend-design-direction/SKILL.md',
  'skills/unified-memory/SKILL.md', 'LICENSE',
];
const reviewSources = ['agents/code-reviewer.md', 'skills/change-impact/SKILL.md',
  'skills/module-regression/SKILL.md', 'skills/agent-introspection-debugging/SKILL.md'];
const securitySources = ['skills/security-review/SKILL.md', 'skills/mcp-dependency-review/SKILL.md'];
const verificationSources = ['skills/tdd-workflow/SKILL.md', 'skills/verification-loop/SKILL.md',
  'skills/frontend-design-direction/SKILL.md', 'skills/module-regression/SKILL.md'];
// This validator covers the reviewed, pinned package, not arbitrary ECC imports.
// A future selection change needs a reviewed contract and provenance update.
const requiredSourceMappings = new Map([
  ['.agents/skills/vncode-ecc-review/SKILL.md', reviewSources],
  ['.agents/skills/vncode-ecc-security/SKILL.md', securitySources],
  ['.agents/skills/vncode-ecc-tdd-verification/SKILL.md', verificationSources],
  ['docs/engineering/ecc/README.md', selectedSources.filter(path => path !== 'LICENSE')],
  ['docs/engineering/ecc/review.md', [...reviewSources, 'skills/frontend-design-direction/SKILL.md', 'skills/unified-memory/SKILL.md']],
  ['docs/engineering/ecc/security.md', securitySources],
  ['docs/engineering/ecc/verification.md', verificationSources],
  ['docs/engineering/ecc/LICENSE.upstream.txt', ['LICENSE']],
]);
const requiredArtifacts = [
  ...skillNames.map(name => `.agents/skills/${name}/SKILL.md`),
  'docs/engineering/ecc/README.md', 'docs/engineering/ecc/review.md',
  'docs/engineering/ecc/security.md', 'docs/engineering/ecc/verification.md',
  'docs/engineering/ecc/LICENSE.upstream.txt',
  'tools/validate-ecc-integration.mjs', 'tools/validate-ecc-integration.test.mjs',
];
const maxBytes = 4 * 1024 * 1024;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);

class ValidationError extends Error {
  constructor(code) { super(code); this.code = code; }
}
const fail = code => { throw new ValidationError(code); };
const safeRelative = value => typeof value === 'string' && value.length > 0 &&
  !isAbsolute(value) && !/[\\\u0000-\u001f\u007f:#?]/u.test(value) &&
  value.split('/').every(part => part !== '' && part !== '.' && part !== '..');
const contained = (root, target) => {
  const rel = relative(root, target);
  return rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel);
};

// Reject links in every path component; the validator never follows a manifest
// path outside its selected root. This is a static audit, not a sandbox against
// another process concurrently replacing ancestor directories.
async function checkedPath(root, path, directory = false) {
  if (!safeRelative(path)) fail('unsafe-path');
  let current = root;
  const parts = path.split('/');
  for (let i = 0; i < parts.length; i++) {
    current = resolve(current, parts[i]);
    const stat = await lstat(current).catch(() => fail('missing-path'));
    if (stat.isSymbolicLink()) fail('symbolic-link');
    if (i < parts.length - 1 || directory) {
      if (!stat.isDirectory()) fail('not-directory');
    } else if (!stat.isFile()) fail('not-regular-file');
  }
  if (!contained(root, await realpath(current))) fail('outside-root');
  return current;
}

async function readChecked(root, path) {
  const target = await checkedPath(root, path);
  const handle = await open(target, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) |
    (constants.O_NONBLOCK ?? 0)).catch(() => fail('file-open-failed'));
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > maxBytes) fail('invalid-file-size');
    const chunks = [];
    let used = 0;
    while (used <= maxBytes) {
      const chunk = Buffer.allocUnsafe(Math.min(64 * 1024, maxBytes + 1 - used));
      const { bytesRead } = await handle.read(chunk, 0, chunk.length, null);
      if (bytesRead === 0) break;
      chunks.push(chunk.subarray(0, bytesRead)); used += bytesRead;
    }
    if (used > maxBytes) fail('invalid-file-size');
    const bytes = Buffer.concat(chunks, used);
    if (bytes.includes(0)) fail('nul-byte');
    let text;
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
    catch { fail('invalid-utf8'); }
    return { bytes, text };
  } finally { await handle.close(); }
}

// Deliberately supports a narrow Markdown-skill YAML subset, without a package
// install or YAML evaluation. Existing skills may retain their other keys.
function frontmatter(text, strict) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  if (lines[0] !== '---') fail('missing-frontmatter');
  const end = lines.indexOf('---', 1);
  if (end < 0) fail('unclosed-frontmatter');
  const fields = new Map();
  let previous;
  for (const line of lines.slice(1, end)) {
    if (!line.trim() || /^\s*#/u.test(line)) continue;
    if (/^\s/u.test(line)) {
      if (!previous) fail('invalid-frontmatter');
      const old = fields.get(previous);
      // An empty YAML value followed by an indented mapping/list is an object,
      // not a folded string. Only scalar continuations are supported here.
      if (strict && !nonempty(old)) fail('invalid-frontmatter-scalar');
      fields.set(previous, `${old}\n${line.trim()}`);
      continue;
    }
    const match = /^([A-Za-z][A-Za-z0-9_-]*):(?:[ \t]*(.*))?$/u.exec(line);
    if (!match || fields.has(match[1])) fail('invalid-frontmatter');
    previous = match[1];
    const value = match[2] ?? '';
    fields.set(previous, strict && /^\s*#/u.test(value) ? '' : value);
  }
  if (strict && [...fields.keys()].some(key => !['name', 'description'].includes(key))) fail('unsupported-frontmatter-key');
  const scalar = raw => {
    if (!nonempty(raw)) return '';
    let value = raw.trim();
    if (/^[>|][+-]?(?:\n|$)/u.test(value)) value = value.includes('\n') ? value.slice(value.indexOf('\n') + 1).trim() : '';
    else if (/^['"]/u.test(value)) {
      if (value.at(-1) !== value[0] || value.length < 2) fail('invalid-frontmatter-scalar');
      if (value[0] === "'") value = value.slice(1, -1).replace(/''/g, "'");
      else {
        try { value = JSON.parse(value); } catch { fail('invalid-frontmatter-scalar'); }
      }
    }
    else if (strict && (/^(?:null|true|false|~|[-+]?\d+(?:\.\d+)?)$/iu.test(value) || /^[\[\]{&*!]/u.test(value))) fail('invalid-frontmatter-scalar');
    if (!nonempty(value)) return '';
    return value;
  };
  const name = scalar(fields.get('name'));
  const description = scalar(fields.get('description'));
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(name) || !description) fail('invalid-skill-metadata');
  return { name, description };
}

function withoutFences(text) {
  let fence;
  return text.split(/\r?\n/u).map(line => {
    const marker = /^\s{0,3}(`{3,}|~{3,})/u.exec(line)?.[1];
    if (marker && (!fence || marker[0] === fence[0] && marker.length >= fence.length)) {
      fence = fence ? undefined : marker; return '';
    }
    return fence ? '' : line;
  }).join('\n');
}

// Check ordinary inline/image links and reference definitions, excluding fenced
// examples. This is intentionally not a complete CommonMark/HTML renderer.
function markdownTargets(text) {
  const content = withoutFences(text);
  const links = [];
  const inline = /!?\[[^\]\n]*\]\(\s*(<[^>\n]*>|(?:\\.|[^()\s])+)(?:\s+["'][^\n]*?["'])?\s*\)/gu;
  const references = /^\s{0,3}\[[^\]\n]+\]:\s*(<[^>\n]*>|\S+)/gmu;
  for (const pattern of [inline, references]) {
    for (const match of content.matchAll(pattern)) links.push(match[1].replace(/^<|>$/gu, ''));
  }
  return links;
}

function headingAnchors(text) {
  const counts = new Map();
  const anchors = new Set();
  for (const line of withoutFences(text).split('\n')) {
    const match = /^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$/u.exec(line);
    if (!match) continue;
    const base = match[1].replace(/<[^>]*>/gu, '').toLowerCase()
      .replace(/[^\p{L}\p{N}\p{M}_\-\s]/gu, '').replace(/\s/gu, '-');
    const count = counts.get(base) ?? 0;
    counts.set(base, count + 1); anchors.add(count ? `${base}-${count}` : base);
  }
  for (const match of text.matchAll(/\b(?:id|name)=["']([^"']+)["']/gu)) anchors.add(match[1]);
  return anchors;
}

/** Read-only structural/provenance audit; no subprocesses, network or writes. */
export async function validateECCIntegration(root, { upstreamRoot } = {}) {
  const errors = [];
  const counts = { existingSkills: 0, integratedSkills: 0, artifacts: 0, sources: 0, links: 0 };
  const add = (code, file) => errors.push({ code, file });
  const attempt = async (file, action) => {
    try { return await action(); }
    catch (error) { add(error instanceof ValidationError ? error.code : 'io-failed', safeRelative(file) ? file : '<invalid-path>'); return undefined; }
  };
  const rootPath = await attempt('<root>', () => realpath(resolve(root)));
  if (!rootPath) return { ok: false, errors, counts, sourceHashesVerified: false };
  const manifestFile = await attempt(manifestPath, () => readChecked(rootPath, manifestPath));
  let manifest;
  try { manifest = manifestFile && JSON.parse(manifestFile.text); }
  catch { add('invalid-manifest-json', manifestPath); }
  if (!record(manifest) || manifest.schemaVersion !== 1 || !record(manifest.upstream) ||
      !Array.isArray(manifest.skills) || !Array.isArray(manifest.artifacts) || !Array.isArray(manifest.excluded)) {
    if (manifestFile) add('invalid-manifest-schema', manifestPath);
    return { ok: false, errors, counts, sourceHashesVerified: false };
  }
  const upstream = manifest.upstream;
  let validSourceDeclarations = upstream.repository === ECC_REPOSITORY && upstream.commit === ECC_COMMIT &&
    upstream.license === 'MIT' && upstream.licenseFile === 'docs/engineering/ecc/LICENSE.upstream.txt' &&
    Array.isArray(upstream.sources) && upstream.sources.length > 0;
  if (upstream.repository !== ECC_REPOSITORY || upstream.commit !== ECC_COMMIT ||
      upstream.license !== 'MIT' || upstream.licenseFile !== 'docs/engineering/ecc/LICENSE.upstream.txt' ||
      !Array.isArray(upstream.sources) || !upstream.sources.length) add('invalid-source-provenance', manifestPath);
  const sources = new Map();
  for (const source of Array.isArray(upstream.sources) ? upstream.sources : []) {
    if (!record(source) || !safeRelative(source.path) || !selectedSources.includes(source.path) ||
        !/^[a-f0-9]{64}$/u.test(source.sha256 ?? '') || sources.has(source.path)) {
      validSourceDeclarations = false;
      add('invalid-source-entry', manifestPath); continue;
    }
    sources.set(source.path, source.sha256);
  }
  counts.sources = sources.size;
  for (const path of selectedSources) if (!sources.has(path)) {
    validSourceDeclarations = false; add('missing-required-source', manifestPath);
  }
  if (!sources.has('LICENSE')) add('missing-license-source', manifestPath);
  if (!manifest.excluded.length || manifest.excluded.some(item => !record(item) || !nonempty(item.component) || !nonempty(item.reason))) add('invalid-exclusions', manifestPath);

  const artifacts = new Map();
  for (const artifact of manifest.artifacts) {
    if (!record(artifact) || !safeRelative(artifact.path) || artifacts.has(artifact.path) ||
        !Array.isArray(artifact.sources) || artifact.sources.some(path => !sources.has(path)) ||
        !Array.isArray(artifact.adaptations) || !artifact.adaptations.length || !artifact.adaptations.every(nonempty)) {
      add('invalid-artifact-entry', manifestPath); continue;
    }
    artifacts.set(artifact.path, artifact);
    const requiredSources = requiredSourceMappings.get(artifact.path);
    const originalTool = ['tools/validate-ecc-integration.mjs', 'tools/validate-ecc-integration.test.mjs'].includes(artifact.path);
    if (requiredSources ? requiredSources.some(path => !artifact.sources.includes(path)) :
        !originalTool && !artifact.sources.some(path => path !== 'LICENSE')) add('incomplete-artifact-provenance', artifact.path);
  }
  for (const path of requiredArtifacts) if (!artifacts.has(path)) add('missing-required-artifact', path);
  const texts = new Map();
  for (const path of artifacts.keys()) {
    const content = await attempt(path, () => readChecked(rootPath, path));
    if (content) texts.set(path, content);
  }
  counts.artifacts = texts.size;
  const license = texts.get(upstream.licenseFile)?.text ?? '';
  if (!['MIT License', 'Copyright (c) 2026 Affaan Mustafa', 'Permission is hereby granted, free of charge',
    'The above copyright notice and this permission notice', 'THE SOFTWARE IS PROVIDED "AS IS"'].every(part => license.includes(part))) {
    add('missing-or-invalid-license-notice', 'docs/engineering/ecc/LICENSE.upstream.txt');
  }

  const declared = new Map();
  for (const skill of manifest.skills) {
    if (!record(skill) || !skillNames.includes(skill.name) || skill.path !== `.agents/skills/${skill.name}/SKILL.md` || declared.has(skill.name)) {
      add('invalid-skill-declaration', manifestPath); continue;
    }
    declared.set(skill.name, skill.path);
  }
  for (const name of skillNames) if (!declared.has(name)) add('missing-skill-declaration', manifestPath);

  const skillsDirectory = await attempt('.agents/skills', () => checkedPath(rootPath, '.agents/skills', true));
  const names = new Set();
  for (const entry of skillsDirectory ? await readdir(skillsDirectory, { withFileTypes: true }) : []) {
    const path = `.agents/skills/${entry.name}/SKILL.md`;
    if (entry.isSymbolicLink()) { add('symbolic-link', '.agents/skills'); continue; }
    if (!entry.isDirectory()) continue;
    if (entry.name.startsWith('vncode-ecc-') && !skillNames.includes(entry.name)) add('undeclared-integrated-skill', path);
    const content = texts.get(path) ?? await attempt(path, () => readChecked(rootPath, path));
    if (!content) continue;
    const strict = skillNames.includes(entry.name);
    const metadata = await attempt(path, () => frontmatter(content.text, strict));
    if (!metadata) continue;
    if (metadata.name !== entry.name) add('skill-directory-name-mismatch', path);
    if (names.has(metadata.name)) add('duplicate-skill-name', '.agents/skills');
    names.add(metadata.name);
    if (strict) {
      counts.integratedSkills++;
      if (!artifacts.has(path)) add('undeclared-skill-artifact', path);
      if (/\bCLAUDE_[A-Z0-9_]+\b|\$ARGUMENTS\b/u.test(content.text)) add('claude-runtime-placeholder', path);
      if (/danger-full-access|bypassPermissions|approval_policy\s*=\s*["']never|\b(?:ignore|override)\s+(?:the\s+)?(?:system|developer|host)\s+(?:instructions|policy|rules)\b/iu.test(content.text)) add('permission-override-instruction', path);
    } else counts.existingSkills++;
  }
  if (counts.integratedSkills !== skillNames.length) add('incomplete-integrated-skills', '.agents/skills');

  for (const [path, content] of texts) {
    if (!path.endsWith('.md')) continue;
    for (const rawTarget of markdownTargets(content.text)) {
      // Network URLs are references only: do not fetch or execute them.
      if (/^[A-Za-z][A-Za-z0-9+.-]*:/u.test(rawTarget)) {
        if (!/^(?:https?|mailto):/iu.test(rawTarget)) add('unsupported-link-scheme', path);
        continue;
      }
      counts.links++;
      await attempt(path, async () => {
        let target;
        try { target = decodeURIComponent(rawTarget); } catch { fail('invalid-link-encoding'); }
        const split = target.indexOf('#');
        const file = split < 0 ? target : target.slice(0, split);
        const fragment = split < 0 ? '' : target.slice(split + 1);
        if (file.startsWith('/') || file.includes('\\') || file.includes('?') || /[\u0000-\u001f\u007f]/u.test(target)) fail('unsafe-markdown-link');
        const absolute = resolve(rootPath, dirname(path), file || '.');
        const destination = file ? relative(rootPath, absolute).split(sep).join('/') : path;
        if (!contained(rootPath, absolute) || !safeRelative(destination)) fail('unsafe-markdown-link');
        if (fragment && destination.endsWith('.md')) {
          const targetContent = texts.get(destination) ?? await readChecked(rootPath, destination);
          if (!headingAnchors(targetContent.text).has(fragment)) fail('missing-markdown-anchor');
        } else await checkedPath(rootPath, destination);
      });
    }
  }

  let sourceHashesVerified = false;
  if (upstreamRoot !== undefined) {
    const sourceRoot = await attempt('<upstream-root>', () => realpath(resolve(upstreamRoot)));
    let verified = 0;
    if (sourceRoot) for (const [path, hash] of sources) {
      const content = await attempt(`upstream/${path}`, () => readChecked(sourceRoot, path));
      if (!content) continue;
      if (sha256(content.bytes) !== hash) { add('source-hash-mismatch', manifestPath); continue; }
      if (path === 'LICENSE' && texts.get(upstream.licenseFile) &&
          !content.bytes.equals(texts.get(upstream.licenseFile).bytes)) add('license-copy-mismatch', upstream.licenseFile);
      verified++;
    }
    sourceHashesVerified = validSourceDeclarations && sources.size > 0 && verified === sources.size;
  }
  return { ok: errors.length === 0, errors, counts, sourceHashesVerified };
}

async function main() {
  const options = {};
  let root = process.cwd();
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--help' && args.length === 1) {
      console.log('Usage: node tools/validate-ecc-integration.mjs [--root path] [--upstream-root path]'); return;
    }
    if (!['--root', '--upstream-root'].includes(args[i]) || !args[i + 1] || args[i + 1].startsWith('--')) fail('invalid-arguments');
    if (args[i] === '--root') root = args[++i]; else options.upstreamRoot = args[++i];
  }
  const result = await validateECCIntegration(root, options);
  console.log(JSON.stringify(result, null, 2));
  if (!result.ok) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(JSON.stringify({ ok: false, error: error instanceof ValidationError ? error.code : 'validation-failed' }));
    process.exitCode = 1;
  });
}
