#!/usr/bin/env bun

import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fstatSync } from 'node:fs';
import { stdin as input, stdout as output } from 'node:process';
import * as readline from 'node:readline/promises';

const rl = readline.createInterface({ input, output });
let pipedLines: string[] | null = null;
let pipedIdx = 0;
function isPipedInput(): boolean {
  try {
    const stat = fstatSync(0);
    return stat.isFIFO() || stat.isFile();
  } catch {
    return input.isTTY === false;
  }
}
function getPipedLines(): string[] {
  if (pipedLines !== null) return pipedLines;
  try {
    if (isPipedInput()) {
      const data = readFileSync(0, 'utf-8');
      pipedLines = data.split(/\r?\n/);
      if (pipedLines.length > 0 && pipedLines[pipedLines.length - 1] === '') pipedLines.pop();
    } else {
      pipedLines = [];
    }
  } catch {
    pipedLines = [];
  }
  return pipedLines;
}
const ask = async (q: string): Promise<string> => {
  if (isPipedInput()) {
    const lines = getPipedLines();
    const ans = pipedIdx < lines.length ? lines[pipedIdx++] : undefined;
    if (ans === undefined) {
      const err = new Error('Piped input exhausted — refusing to assume an answer');
      err.name = 'AbortError';
      throw err;
    }
    output.write(q);
    output.write(ans + '\n');
    return ans;
  }
  return rl.question(q);
};
const checkYesOrNo = (ans: string) => ans.trim().toLowerCase() === 'n';

const RED = '\x1b[0;31m';
const GREEN = '\x1b[0;32m';
const YELLOW = '\x1b[1;33m';
const BLUE = '\x1b[0;34m';
const DIM = '\x1b[2m';
const NC = '\x1b[0m';

function step(msg: string) {
  console.log(`\n${BLUE}==>${NC} ${msg}`);
}
function ok(msg: string) {
  console.log(`  ${GREEN}ok${NC} ${msg}`);
}
function fail(msg: string): never {
  revertPendingRelease();
  console.error(`  ${RED}error${NC} ${msg}`);
  process.exit(1);
}

let pendingRevert: { snapshot: Record<string, string | null>; next: string; didAutoStash: boolean } | null = null;

function revertPendingRelease(): void {
  const pending = pendingRevert;
  pendingRevert = null;
  if (pending === null) return;
  revertWorkingTree(pending.snapshot, pending.next, pending.didAutoStash);
}

function revertWorkingTree(
  fileSnapshot: Record<string, string | null>,
  next: string,
  didAutoStash: boolean,
): void {
  console.log(`\n${YELLOW}Reverting changes...${NC}`);
  for (const [file, content] of Object.entries(fileSnapshot)) {
    try {
      if (content === null) rmSync(file);
      else writeFileSync(file, content);
    } catch {
      console.log(`  ${YELLOW}warning${NC} Could not restore ${file} — check 'git diff'`);
    }
  }
  const changelog = `changelogs/v${next}.md`;
  if (existsSync(changelog)) {
    try {
      rmSync(changelog);
    } catch {}
    if (existsSync(changelog)) {
      console.log(`  ${YELLOW}warning${NC} Could not remove ${changelog} — delete it manually`);
    }
  }
  if (didAutoStash) {
    try {
      execSync('git stash pop', { stdio: 'inherit' });
      console.log(`  ${GREEN}ok${NC} Restored stashed changes`);
    } catch {
      console.log(`  ${YELLOW}warning${NC} Could not pop stash — check 'git stash list'`);
    }
  }
  console.log('Reverted to state before release.');
}

// ---------------------------------------------------------------------------
// Semantic versioning
// ---------------------------------------------------------------------------

interface VersionParsed {
  major: number;
  minor: number;
  patch: number;
  prerelease: string | null;
  prereleaseNum: number;
}

function tryParseVersion(v: string): VersionParsed | null {
  const match = v.match(/^(\d+)\.(\d+)\.(\d+)(?:-(.+?)\.(\d+))?$/);
  if (!match) return null;
  return {
    major: parseInt(match[1], 10),
    minor: parseInt(match[2], 10),
    patch: parseInt(match[3], 10),
    prerelease: match[4] ?? null,
    prereleaseNum: match[5] ? parseInt(match[5], 10) : 0,
  };
}

function parseVersion(v: string): VersionParsed {
  const parsed = tryParseVersion(v);
  if (!parsed) fail(`Invalid semantic version: "${v}" (expected MAJOR.MINOR.PATCH)`);
  return parsed;
}

function formatVersion(p: VersionParsed): string {
  const base = `${p.major}.${p.minor}.${p.patch}`;
  return p.prerelease ? `${base}-${p.prerelease}.${p.prereleaseNum}` : base;
}

function resolveNextVersion(current: string, bump: string): string {
  const parsed = parseVersion(current);
  switch (bump) {
    case 'major':
      return formatVersion({ major: parsed.major + 1, minor: 0, patch: 0, prerelease: null, prereleaseNum: 0 });
    case 'minor':
      return formatVersion({ major: parsed.major, minor: parsed.minor + 1, patch: 0, prerelease: null, prereleaseNum: 0 });
    case 'patch':
      return formatVersion({ major: parsed.major, minor: parsed.minor, patch: parsed.patch + 1, prerelease: null, prereleaseNum: 0 });
    default:
      return bump;
  }
}

// ---------------------------------------------------------------------------
// Version files (single source of truth: build.gradle, mirrored to yml)
// ---------------------------------------------------------------------------

const VERSION_FILES = ['build.gradle', 'src/main/resources/plugin.yml', 'src/main/resources/paper-plugin.yml'];

function readCurrentVersion(): string {
  const gradle = readFileSync('build.gradle', 'utf-8');
  const m = gradle.match(/^\s*version\s*=\s*['"]([^'"]+)['"]/m);
  if (!m) fail('Could not find version in build.gradle');
  return m[1];
}

function bumpVersionFiles(next: string): void {
  const gradle = readFileSync('build.gradle', 'utf-8');
  writeFileSync('build.gradle', gradle.replace(/^(\s*version\s*=\s*)['"][^'"]+['"]/m, `$1'${next}'`));
  for (const file of ['src/main/resources/plugin.yml', 'src/main/resources/paper-plugin.yml']) {
    const content = readFileSync(file, 'utf-8');
    writeFileSync(file, content.replace(/^(\s*version:\s*)['"]?[^'"\s]+['"]?/m, `$1'${next}'`));
  }
}

// ---------------------------------------------------------------------------
// Changelog generation (conventional commits)
// ---------------------------------------------------------------------------

function generateChangelog(next: string, baseTag?: string, endTag = 'HEAD'): { changelogEntry: string; releaseEntry: string } {
  let rangeStart = baseTag;
  if (rangeStart === undefined && endTag === 'HEAD') {
    try {
      rangeStart = execSync('git tag --list "v*" --sort=-creatordate', { encoding: 'utf-8' }).trim().split('\n')[0] ?? '';
    } catch {
      rangeStart = '';
    }
  }

  const range = rangeStart ? `${rangeStart}..${endTag}` : endTag;

  const log = execSync(`git log ${range} --pretty=format:"%s%n" --reverse`, { encoding: 'utf-8' });
  const lines = log.split('\n').filter(Boolean).filter((l) => !/^chore:\s*release\s+v/i.test(l));

  interface ScopedEntry {
    scope: string | null;
    msg: string;
  }

  const addedRaw: ScopedEntry[] = [];
  const fixedRaw: ScopedEntry[] = [];
  const changedRaw: ScopedEntry[] = [];
  const choreRaw: ScopedEntry[] = [];
  const otherRaw: ScopedEntry[] = [];

  const pattern = /^(\w+)(\(.*?\))?!?:\s(.+)$/;
  const revertPattern = /^[Rr]evert\s+"(.+)"$/;

  const reverted: string[] = [];
  function parseEntry(line: string): { type: string; entry: ScopedEntry } | null {
    const m = line.match(pattern);
    if (!m) return null;
    const [, type, scope, msg] = m;
    return { type, entry: { scope: scope ? scope.slice(1, -1) : null, msg } };
  }

  function normalize(s: string): string {
    return s
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\s]/gu, '')
      .replace(/\s+/g, ' ')
      .trim();
  }
  function dedupe(entries: string[]): string[] {
    const kept: { raw: string; norm: string }[] = [];
    for (const entry of entries) {
      const norm = normalize(entry);
      if (!norm) continue;
      let dominated = false;
      for (const k of kept) {
        if (k.norm === norm || k.norm.includes(norm) || norm.includes(k.norm)) {
          dominated = true;
          if (norm.length > k.norm.length) {
            k.raw = entry;
            k.norm = norm;
          }
          break;
        }
      }
      if (!dominated) kept.push({ raw: entry, norm });
    }
    return kept.map((k) => k.raw);
  }

  for (const line of lines) {
    const rm = line.match(revertPattern);
    if (rm) {
      const inner = parseEntry(rm[1]);
      if (inner) {
        const norm = normalize(`${inner.entry.scope ?? ''} ${inner.entry.msg}`);
        reverted.push(norm);
        const stillThere = [addedRaw, fixedRaw, changedRaw, choreRaw, otherRaw].some((list) =>
          list.some((e) => normalize(`${e.scope ?? ''} ${e.msg}`) === norm),
        );
        if (!stillThere) {
          const entry = { scope: inner.entry.scope, msg: `Revert: ${inner.entry.msg}` };
          (inner.type === 'feat' ? addedRaw : inner.type === 'fix' ? fixedRaw : inner.type === 'chore' ? choreRaw : 'refactor,perf,style'.includes(inner.type) ? changedRaw : otherRaw).push(entry);
        }
        continue;
      }
    }
    const parsed = parseEntry(line);
    if (parsed) {
      const { type, entry } = parsed;
      switch (type) {
        case 'feat':
          addedRaw.push(entry);
          break;
        case 'fix':
          fixedRaw.push(entry);
          break;
        case 'refactor':
        case 'perf':
        case 'style':
          changedRaw.push(entry);
          break;
        case 'chore':
          choreRaw.push(entry);
          break;
        default:
          otherRaw.push(entry);
          break;
      }
    } else {
      otherRaw.push({ scope: null, msg: line });
    }
  }

  for (const list of [addedRaw, fixedRaw, changedRaw, choreRaw, otherRaw]) {
    for (let i = list.length - 1; i >= 0; i--) {
      if (reverted.includes(normalize(`${list[i].scope ?? ''} ${list[i].msg}`))) {
        list.splice(i, 1);
      }
    }
  }

  function renderSection(entries: ScopedEntry[]): string {
    const byScope = new Map<string | null, string[]>();
    for (const e of entries) {
      const list = byScope.get(e.scope) ?? [];
      list.push(e.msg);
      byScope.set(e.scope, list);
    }
    const deduped = new Map<string | null, string[]>();
    for (const [scope, msgs] of byScope) {
      deduped.set(scope, dedupe(msgs));
    }
    const lines: string[] = [];
    for (const msg of deduped.get(null) ?? []) {
      lines.push(`- ${msg}`);
    }
    for (const [scope, msgs] of deduped) {
      if (scope !== null && msgs.length === 1) {
        lines.push(`- **${scope}**: \`\`\`${msgs[0]}\`\`\``);
      }
    }
    for (const [scope, msgs] of deduped) {
      if (scope !== null && msgs.length > 1) {
        if (lines.length) lines.push('');
        lines.push(`- ${scope}:`);
        lines.push('```');
        for (const msg of msgs) {
          lines.push(`- ${msg}`);
        }
        lines.push('```');
      }
    }
    return lines.join('\n');
  }

  const added = renderSection(addedRaw);
  const fixed = renderSection(fixedRaw);
  const changed = renderSection(changedRaw);
  const chores = renderSection(choreRaw);
  const other = renderSection(otherRaw);

  let body = '';
  if (added) body += '\n\n## Added\n\n' + added;
  if (fixed) body += '\n\n## Fixed\n\n' + fixed;
  if (changed) body += '\n\n## Changed\n\n' + changed;
  if (chores) body += '\n\n## Chores\n\n' + chores;
  if (other) body += '\n\n## Other\n\n' + other;
  if (!body) body = '\n\nMaintenance release.';

  const today = new Date().toISOString().slice(0, 10);
  const changelogEntry = `# [${next}] - ${today}${body}`;
  const releaseEntry = body.trimStart();

  return { changelogEntry, releaseEntry };
}

// ---------------------------------------------------------------------------
// Undo log
// ---------------------------------------------------------------------------

interface UndoLog {
  version: { from: string; to: string };
  timestamp: string;
  branch: string;
  commit: string;
  tag: string;
  files: Record<string, string | null>;
  created: string[];
}

const UNDO_FILE = '.release-undo.json';

function captureFileSnapshot(files: string[]): Record<string, string | null> {
  const snapshot: Record<string, string | null> = {};
  for (const file of files) {
    snapshot[file] = existsSync(file) ? readFileSync(file, 'utf-8') : null;
  }
  return snapshot;
}

function saveUndoLog(log: UndoLog) {
  writeFileSync(UNDO_FILE, JSON.stringify(log, null, 2) + '\n');
}

function loadUndoLog(): UndoLog | null {
  if (!existsSync(UNDO_FILE)) return null;
  try {
    return JSON.parse(readFileSync(UNDO_FILE, 'utf-8')) as UndoLog;
  } catch {
    return null;
  }
}

async function undoRelease(dryRun = false, force = false) {
  const log = loadUndoLog();
  if (log === null) fail('No undo log found — nothing to undo.');

  const headRev = execSync('git rev-parse HEAD', { encoding: 'utf-8' }).trim();
  if (headRev !== log.commit && !force) {
    fail(
      `HEAD (${headRev.substring(0, 7)}) is past the release commit (${log.commit.substring(0, 7)}). ` +
        'Undo only works with nothing committed after the release (or pass --force).',
    );
  }
  console.log(`\nWill undo release v${log.version.to}:`);
  console.log(`  commit : ${log.commit.substring(0, 7)}`);
  console.log(`  tag    : ${log.tag}`);
  console.log(`  branch : ${log.branch}`);

  const proceed = await ask(`\nProceed? This will delete the local/remote tag and the GitHub release. (y/N) `);
  if (proceed.toLowerCase() !== 'y') {
    console.log('Aborted.');
    process.exit(0);
  }

  const branch = execSync('git branch --show-current', { encoding: 'utf-8' }).trim();
  if (branch !== log.branch) {
    const sw = await ask(`  Not on '${log.branch}' (on '${branch}'). Switch? (y/N) `);
    if (sw.toLowerCase() !== 'y') {
      console.log('Aborted.');
      process.exit(0);
    }
    if (!dryRun) execSync(`git checkout ${log.branch}`, { encoding: 'utf-8' });
  }

  if (dryRun) {
    console.log(`\n${YELLOW}DRY RUN${NC} — no changes will be made\n`);
  }

  step('Deleting local tag');
  if (!dryRun) execSync(`git tag -d "${log.tag}"`, { stdio: 'ignore', encoding: 'utf-8' });
  ok(`Deleted local tag ${log.tag}`);

  step('Deleting remote tag');
  if (!dryRun) {
    try {
      execSync(`git push origin :refs/tags/${log.tag}`, { stdio: 'ignore', encoding: 'utf-8' });
    } catch {
      console.log(`  ${YELLOW}warning${NC} Could not delete remote tag (it may never have been pushed).`);
    }
  }
  ok(`Deleted remote tag ${log.tag}`);

  step('Deleting GitHub release');
  if (!dryRun) {
    try {
      execSync(`gh release delete "v${log.version.to}" --yes`, { stdio: 'ignore', encoding: 'utf-8' });
    } catch {
      console.log(`  ${YELLOW}warning${NC} Could not delete GitHub release.`);
    }
  }
  ok('GitHub release deleted');

  if (!dryRun) {
    rmSync(UNDO_FILE);
  }
  ok(`Cleaned up ${UNDO_FILE}`);

  console.log(`\n${GREEN}${'='.repeat(40)}${NC}`);
  console.log(`${GREEN}  Undid release v${log.version.to}${NC}`);
  console.log(`${GREEN}${'='.repeat(40)}${NC}\n`);
}

// ---------------------------------------------------------------------------
// Jar handling
// ---------------------------------------------------------------------------

function findBuiltJar(next: string): string {
  const candidates = [
    `build/libs/GlovedPlugin-${next}.jar`,
  ];
  for (const c of candidates) {
    if (existsSync(c)) return c;
  }
  // Fallback: newest GlovedPlugin-*.jar
  try {
    const out = execSync('ls -t build/libs/GlovedPlugin-*.jar', { encoding: 'utf-8' }).trim().split('\n').filter(Boolean);
    if (out.length) return out[0];
  } catch {}
  fail(`Built jar not found. Expected ${candidates[0]} — run ./gradlew build first.`);
}

function buildJar(dryRun: boolean): void {
  step('Building plugin jar');
  if (dryRun) {
    ok('SKIP — dry run');
    return;
  }
  try {
    execSync('export JAVA_HOME=/usr/lib/jvm/java-17-openjdk; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew clean build --no-daemon', { stdio: 'inherit' });
  } catch {
    fail('Gradle build failed.');
  }
  ok('Jar built');
}

// ---------------------------------------------------------------------------
// Usage
// ---------------------------------------------------------------------------

function showUsage() {
  console.log(`
Usage: ./scripts/release.ts [<bump>] [flags]

Semantic-version bumps:
  major                Bump major (e.g. 0.1.0 -> 1.0.0)
  minor                Bump minor (e.g. 0.1.0 -> 0.2.0)
  patch                Bump patch (e.g. 0.1.0 -> 0.1.1)
  X.Y.Z                Explicit version

Flags:
  --no-changelog     Skip changelog generation
  --no-push          Commit and tag locally, skip git push
  --no-release       Skip creating the GitHub release (commit+tag only)
  --undo             Revert the most recent release (also: \`undo\`)
  --force, -f        With undo: skip the no-commits-after-release check
  --dry-run          Show what would be done without making changes
  --help, -h, help   Show this help

  Uncommitted changes are handled interactively: [s] stash & continue, [c] continue anyway, [a] abort.

Changelog preview:
  changelog                      Preview changelog for current version
  changelog patch                Preview changelog for next patch
  rechangelog                    Regenerate all changelogs/ + CHANGELOG.md from tags

Examples:
  ./scripts/release.ts patch              # 0.1.0 -> 0.1.1
  ./scripts/release.ts minor              # 0.1.0 -> 0.2.0
  ./scripts/release.ts major              # 0.1.0 -> 1.0.0
  ./scripts/release.ts 1.2.3              # exact version
  ./scripts/release.ts changelog patch    # preview changelog for next patch
  ./scripts/release.ts --undo             # revert the most recent release
`);
}

async function rechangelog(dryRun = false) {
  const tags = execSync('git tag --list "v*" --sort=creatordate', { encoding: 'utf-8' })
    .trim()
    .split('\n')
    .filter((t) => /^v\d+\.\d+\.\d+(-.+?\.\d+)?$/.test(t));
  if (!tags.length) fail('No version tags found.');

  if (dryRun) {
    console.log(`\n${YELLOW}DRY RUN${NC} — no changes will be made\n`);
  }
  console.log(`  tags    : ${tags.length} (${tags[0]}..${tags[tags.length - 1]})`);

  const newest = tags[tags.length - 1];
  const newestVer = newest.replace(/^v/, '');
  const { changelogEntry: preview } = generateChangelog(
    newestVer,
    tags.length > 1 ? tags[tags.length - 2] : undefined,
    newest,
  );
  console.log(`\n${BLUE}=== Preview (${newest}) ===${NC}\n`);
  console.log(preview);

  if (!dryRun) {
    const looksGood = await ask('\nRegenerate all per-version changelogs + CHANGELOG.md? (Y/n) ');
    if (checkYesOrNo(looksGood)) {
      console.log('Aborted.');
      process.exit(0);
    }
  }

  const built: { tag: string; ver: string; date: string; body: string }[] = [];
  let prev = '';
  for (const tag of tags) {
    const ver = tag.replace(/^v/, '');
    const date =
      execSync(`git log -1 --format=%cs ${tag}`, { encoding: 'utf-8' }).trim() ||
      new Date().toISOString().slice(0, 10);
    const { releaseEntry } = generateChangelog(ver, prev || undefined, tag);
    built.push({ tag, ver, date, body: releaseEntry });
    if (!dryRun) {
      if (!existsSync('changelogs')) mkdirSync('changelogs', { recursive: true });
      writeFileSync(`changelogs/${tag}.md`, releaseEntry);
    }
    prev = tag;
  }

  if (!dryRun) {
    const current = existsSync('CHANGELOG.md') ? readFileSync('CHANGELOG.md', 'utf-8') : '# Changelog\n';
    const at = current.search(/^#{1,2} \[/m);
    const preamble = (at === -1 ? current : current.slice(0, at)).trimEnd();
    const entries = [...built]
      .reverse()
      .map((b) => `# [${b.ver}] - ${b.date}\n\n${b.body}`);
    writeFileSync('CHANGELOG.md', preamble ? `${preamble}\n\n${entries.join('\n\n')}\n` : `${entries.join('\n\n')}\n`);
  }
  ok(dryRun ? 'Preview only — nothing written' : `Rewrote ${built.length} changelogs + CHANGELOG.md`);
}

async function main() {
  const args = process.argv.slice(2);

  if (args.includes('--help') || args.includes('-h') || args.includes('help')) {
    showUsage();
    rl.close();
    process.exit(0);
  }

  if (args.includes('--undo') || args[0] === 'undo') {
    const dryRun = args.includes('--dry-run');
    const force = args.includes('--force') || args.includes('-f');
    await undoRelease(dryRun, force);
    rl.close();
    process.exit(0);
  }

  if (args[0] === 'rechangelog') {
    await rechangelog(args.includes('--dry-run'));
    rl.close();
    process.exit(0);
  }

  const dryRun = args.includes('--dry-run');

  const skipChangelog = args.includes('--no-changelog');
  const noPush = args.includes('--no-push');
  const noRelease = args.includes('--no-release');
  const flags = new Set(['--undo', '--no-changelog', '--no-push', '--no-release', '--dry-run', '--help', '-h']);
  const positional = args.filter((a) => !flags.has(a));

  const isChangelogMode = positional[0] === 'changelog';
  const releaseArgs = isChangelogMode ? positional.slice(1) : positional;

  const bumpArg = releaseArgs[0] ?? 'patch';

  for (const cmd of ['git', 'gh']) {
    try {
      execSync(`command -v ${cmd}`, { stdio: 'ignore' });
    } catch {
      fail(`Required tool not found: ${cmd}`);
    }
  }

  const current = readCurrentVersion();
  parseVersion(current);

  let next: string;
  if (['major', 'minor', 'patch'].includes(bumpArg)) {
    next = resolveNextVersion(current, bumpArg);
  } else if (/^\d+\.\d+\.\d+(-.+?\.\d+)?$/.test(bumpArg)) {
    parseVersion(bumpArg);
    next = bumpArg;
  } else {
    fail(`Invalid version or bump type: "${bumpArg}" (use major|minor|patch|X.Y.Z)`);
  }

  console.log(`  bump type : ${DIM}${bumpArg}${NC}`);
  console.log(`  current   : ${DIM}${current}${NC}`);
  console.log(`  next      : ${GREEN}${next}${NC}\n`);

  if (isChangelogMode) {
    step('Generating changelog preview');

    execSync('git fetch --tags --force', { stdio: 'ignore' });

    const { changelogEntry, releaseEntry } = generateChangelog(next);

    console.log(`\n${BLUE}=== CHANGELOG.md entry ===${NC}\n`);
    console.log(changelogEntry);
    console.log(`\n${BLUE}=== Release body (changelogs/v${next}.md) ===${NC}\n`);
    console.log(releaseEntry);
    rl.close();
    process.exit(0);
  }

  step('Running pre-flight checks');

  let didAutoStash = false;
  if (!dryRun) {
    const status = execSync('git status --porcelain', { encoding: 'utf-8' }).trim();
    if (status) {
      if (process.stdin.isTTY === false) {
        fail('Uncommitted changes detected. Commit or stash them first.');
      }
      console.log(`  ${YELLOW}warning${NC} Uncommitted changes detected:`);
      try {
        const short = execSync('git status --short', { encoding: 'utf-8' }).trim();
        if (short) console.log(`${DIM}${short}${NC}`);
      } catch {}
      const ans = await ask('  [s] Stash & continue  [c] Continue anyway  [a] Abort [s/c/a] (a): ');
      const v = ans.trim().toLowerCase();
      if (v === 's') {
        try {
          execSync('git stash push -m "release: autostash before v' + next + '" --include-untracked', {
            stdio: 'inherit',
          });
          didAutoStash = true;
          ok('Stashed working tree');
        } catch (e) {
          fail('Failed to stash changes: ' + e);
        }
      } else if (v === 'c') {
        ok('Continuing with dirty tree');
      } else {
        console.log('Aborted.');
        process.exit(0);
      }
    } else {
      ok('Working tree clean');
    }
  } else {
    ok('Working tree clean (dry run — ignoring dirty check)');
  }

  const branch = execSync('git branch --show-current', { encoding: 'utf-8' }).trim();
  if (branch !== 'main' && branch !== 'master') {
    console.log(`  ${YELLOW}warning${NC} You are on branch '${branch}', not 'main'/'master'.`);
    const reply = await ask('  Continue anyway? (y/N) ');
    if (reply.toLowerCase() !== 'y') {
      if (didAutoStash) {
        try {
          execSync('git stash pop', { stdio: 'inherit' });
        } catch {}
      }
      console.log('Aborted.');
      process.exit(0);
    }
  }

  if (!dryRun) {
    const proceed = await ask(`Proceed with release v${next}? (Y/n) `);
    if (checkYesOrNo(proceed)) {
      if (didAutoStash) {
        try {
          execSync('git stash pop', { stdio: 'inherit' });
        } catch {}
      }
      console.log('Aborted.');
      process.exit(0);
    }
    pendingRevert = { snapshot: {}, next, didAutoStash };
  } else {
    console.log(`\n${YELLOW}DRY RUN${NC} — no changes will be made\n`);
  }

  const fileSnapshot =
    dryRun ?
      {}
    : captureFileSnapshot([
        ...VERSION_FILES,
        ...(skipChangelog ? [] : ['CHANGELOG.md']),
      ]);
  if (pendingRevert !== null) pendingRevert.snapshot = fileSnapshot;

  step('Updating version numbers');

  if (!dryRun) {
    bumpVersionFiles(next);
  }
  ok(`build.gradle -> ${next}`);
  ok('plugin.yml + paper-plugin.yml');

  if (skipChangelog) {
    ok('SKIP — changelog generation disabled');
  } else {
    step('Generating changelog');

    const { changelogEntry, releaseEntry } = generateChangelog(next);

    if (!dryRun) {
      const changelogPath = 'CHANGELOG.md';
      if (existsSync(changelogPath)) {
        const changelog = readFileSync(changelogPath, 'utf-8');
        if (changelog.startsWith('# [')) {
          writeFileSync(changelogPath, changelogEntry + '\n\n' + changelog);
        } else {
          const at = changelog.search(/^#{1,2} \[/m);
          if (at !== -1) {
            const before = changelog.slice(0, at);
            const after = changelog.slice(at);
            writeFileSync(changelogPath, before.trimEnd() + '\n\n' + changelogEntry + '\n\n' + after);
          } else {
            writeFileSync(changelogPath, changelog.trimEnd() + '\n\n' + changelogEntry + '\n');
          }
        }
      } else {
        writeFileSync(changelogPath, '# Changelog\n\n' + changelogEntry + '\n');
      }
      ok('CHANGELOG.md');
    } else {
      ok('CHANGELOG.md');
    }

    const changelogsDir = 'changelogs';
    if (!dryRun) {
      if (!existsSync(changelogsDir)) {
        mkdirSync(changelogsDir, { recursive: true });
      }
      writeFileSync(`${changelogsDir}/v${next}.md`, releaseEntry);
    }
    ok(`${changelogsDir}/v${next}.md`);

    console.log(`\n${BLUE}=== CHANGELOG.md entry ===${NC}\n`);
    console.log(changelogEntry);
    console.log(`\n${BLUE}=== Release body (changelogs/v${next}.md) ===${NC}\n`);
    console.log(releaseEntry);

    if (!dryRun) {
      const looksGood = await ask('Does the changelog look good? (Y/n) ');
      if (checkYesOrNo(looksGood)) {
        revertPendingRelease();
        console.log(`
If you want to edit manually, run:
  git diff
and re-run: ./scripts/release.ts ${bumpArg} ${noPush ? '--no-push' : ''}
`);
        process.exit(0);
      }
    }
  }

  buildJar(dryRun);
  const jarPath = dryRun ? `build/libs/GlovedPlugin-${next}.jar` : findBuiltJar(next);

  step('Creating release commit');

  const filesToAdd = [
    ...VERSION_FILES,
    ...(skipChangelog ? [] : [`changelogs/v${next}.md`]),
    ...(skipChangelog ? [] : ['CHANGELOG.md']),
  ];
  if (!dryRun) {
    execSync(`git add ${filesToAdd.join(' ')}`, { encoding: 'utf-8' });
    execSync(`git commit -m "chore: release v${next}"`, { encoding: 'utf-8' });
    pendingRevert = null;
  }
  ok(`Committed release v${next}`);

  step('Saving undo log');
  if (!dryRun) {
    const commitHash = execSync('git rev-parse HEAD', { encoding: 'utf-8' }).trim();
    const createdFiles: string[] = skipChangelog ? [] : [`changelogs/v${next}.md`];
    saveUndoLog({
      version: { from: current, to: next },
      timestamp: new Date().toISOString(),
      branch,
      commit: commitHash,
      tag: `v${next}`,
      files: fileSnapshot,
      created: createdFiles,
    });
  }
  ok(`.release-undo.json`);

  step(`Creating tag v${next}`);
  if (!dryRun) {
    execSync(`git tag -a "v${next}" -m "Release v${next}"`, { encoding: 'utf-8' });
  }
  ok('Tagged');

  if (noPush) {
    console.log(`\n${YELLOW}SKIP — push disabled by --no-push${NC}`);
    console.log(`${DIM}To push manually:${NC}`);
    console.log(`  git push origin ${branch} --tags`);
  } else {
    step(`Pushing to origin/${branch}`);
    if (!dryRun) {
      try {
        execSync(`git push origin ${branch} --tags`, { encoding: 'utf-8' });
      } catch (e) {
        const out = (e as { stderr?: unknown; stdout?: unknown; message?: unknown });
        const text = [out.stderr, out.stdout, out.message].map((x) => String(x ?? '')).join('\n');
        console.error(`\n${RED}Push to origin/${branch} failed.${NC}`);
        for (const line of text.split('\n').slice(0, 8)) {
          if (line.trim()) console.error(`    ${line.trim()}`);
        }
        console.error('\n  To retry:');
        console.error(`    git push origin ${branch} --tags`);
        console.error('  To throw this release away entirely:');
        console.error('    ./scripts/release.ts --undo');
        process.exit(1);
      }
    }
    ok('Pushed');
  }

  if (!noRelease) {
    step(`Creating GitHub release v${next}`);
    if (!dryRun) {
      try {
        execSync(
          `gh release create "v${next}" "${jarPath}" --title "v${next}" --notes-file "changelogs/v${next}.md"`,
          { stdio: 'inherit' },
        );
      } catch {
        fail('gh release create failed. The commit and tag are intact — retry with: gh release create "v' + next + '" --notes-file "changelogs/v' + next + '.md"');
      }
    }
    ok(`Release v${next} with ${jarPath}`);
  } else {
    console.log(`\n${YELLOW}SKIP — release creation disabled by --no-release${NC}`);
    console.log(`${DIM}To create manually:${NC}`);
    console.log(`  gh release create "v${next}" "${jarPath}" --title "v${next}" --notes-file "changelogs/v${next}.md"`);
  }

  if (didAutoStash && !dryRun) {
    step('Restoring stashed changes');
    try {
      execSync('git stash pop', { stdio: 'inherit' });
      ok('Restored stashed changes');
    } catch {
      console.log(`  ${YELLOW}warning${NC} Could not pop stash — check 'git stash list'`);
    }
  }

  if (!dryRun) {
    console.log(`\n${GREEN}========================================${NC}`);
    console.log(`${GREEN}  Released v${next}${NC}`);
    console.log(`${GREEN}========================================${NC}\n`);
    console.log(`Jar: ${jarPath}`);
    console.log('To undo this release:');
    console.log('  ./scripts/release.ts --undo');
  }
}

main()
  .catch((err) => {
    if (pendingRevert !== null) revertPendingRelease();
    if ((err as { name?: string })?.name === 'AbortError') {
      console.log('Aborted.');
      process.exit(130);
    }
    console.error('Release script failed:', err);
    process.exit(1);
  })
  .finally(() => rl.close());
