#!/usr/bin/env node
// Pure Java domain/API tests. Android UI and the real Pleos SDK are compiled by Gradle.
// Local: node scripts/test-java.mjs
// CI/without Android Studio: node scripts/test-java.mjs --download-json
// Or provide a real org.json implementation with SCENIC_TEST_JSON_JAR=/path/to/json.jar.
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { delimiter, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const cache = join(root, '.cache', 'java-tests');
const jsonVersion = '20240303';
const jsonSha256 = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed';
const jsonUrl = `https://repo.maven.apache.org/maven2/org/json/json/${jsonVersion}/json-${jsonVersion}.jar`;
const args = new Set(process.argv.slice(2));
if (args.has('--help')) {
  console.log('Usage: node scripts/test-java.mjs [--download-json]\nSet JAVA_HOME to a JDK and optionally SCENIC_TEST_JSON_JAR to a real org.json jar.\nWithout --download-json, Android Studio layoutlib.jar is detected locally.');
  process.exit(0);
}
if ([...args].some(value => value !== '--download-json')) throw new Error('Unknown argument; use --help.');

async function pinnedJson() {
  const directory = join(cache, 'deps');
  await mkdir(directory, { recursive: true });
  const target = join(directory, `json-${jsonVersion}.jar`);
  let bytes;
  if (existsSync(target)) bytes = await readFile(target);
  else {
    const response = await fetch(jsonUrl, { signal: AbortSignal.timeout(30_000) });
    if (!response.ok) throw new Error(`org.json download failed (HTTP ${response.status}).`);
    bytes = Buffer.from(await response.arrayBuffer());
  }
  if (createHash('sha256').update(bytes).digest('hex') !== jsonSha256) {
    throw new Error(`org.json checksum mismatch; remove ${target} and retry.`);
  }
  if (!existsSync(target)) await writeFile(target, bytes, { flag: 'wx' });
  return target;
}

async function findJson() {
  if (process.env.SCENIC_TEST_JSON_JAR) {
    const explicit = resolve(process.env.SCENIC_TEST_JSON_JAR);
    if (!existsSync(explicit)) throw new Error('SCENIC_TEST_JSON_JAR does not point to an existing jar.');
    return explicit;
  }
  if (args.has('--download-json')) return pinnedJson();
  const candidates = [
    join(process.env.ProgramFiles || 'C:\\Program Files', 'Android', 'Android Studio', 'plugins', 'design-tools', 'lib', 'layoutlib.jar'),
    '/Applications/Android Studio.app/Contents/plugins/design-tools/lib/layoutlib.jar',
    '/opt/android-studio/plugins/design-tools/lib/layoutlib.jar',
  ];
  const local = candidates.find(existsSync);
  if (local) return local;
  throw new Error('A real org.json jar is required. Set SCENIC_TEST_JSON_JAR or rerun with --download-json.');
}

function javaTool(name) {
  const executable = process.platform === 'win32' ? `${name}.exe` : name;
  if (process.env.JAVA_HOME) return join(process.env.JAVA_HOME, 'bin', executable);
  const studioJdks = [
    join(process.env.ProgramFiles || 'C:\\Program Files', 'Android', 'Android Studio', 'jbr'),
    '/Applications/Android Studio.app/Contents/jbr/Contents/Home',
    '/opt/android-studio/jbr',
  ];
  const bundled = studioJdks.map(home => join(home, 'bin', executable)).find(existsSync);
  return bundled || executable;
}

function run(command, arguments_) {
  const result = spawnSync(command, arguments_, { cwd: root, stdio: 'inherit', windowsHide: true, timeout: 120_000 });
  if (result.error) throw new Error(`Cannot run ${command}: ${result.error.message}. Set JAVA_HOME to a JDK.`);
  if (result.status !== 0) throw new Error(`${command} failed (${result.signal || result.status}).`);
}

async function testFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await testFiles(path));
    else if (entry.name.endsWith('Test.java')) files.push(path);
  }
  return files.sort();
}

// Keep this list restricted to production classes with no Android runtime dependency.
// New *Test.java files in these directories are automatically compiled and executed.
const domainClasses = [
  'ApiRequestGate', 'CheckpointTracker', 'CourseEngine', 'RouteCheckpoint', 'DestinationPlace', 'DestinationRoutePlan',
  'EndpointPolicy', 'DriveRegion', 'JourneyState', 'LivePlan', 'LivePlanOrigin', 'LocationFixPolicy',
  'MusicContext', 'MusicPlaylist', 'MusicRecommendationState', 'MusicWebPolicy', 'MusicWindowLayout', 'NationwideCatalog',
  'NavigationHandoffPlan', 'ParkingAvailability', 'ParkingEvidenceStore', 'ParkingReroutePolicy', 'PersonalVideoStore', 'RouteProjection',
  'ScenicApiClient', 'ScenicCaptureStore', 'ScenicEvidence', 'ScenicLayoutPolicy', 'ScenicPhotoCollection', 'RecapStoryPlanner', 'ScenicFrameSelector',
  'StrictJson', 'VehicleSafety',
];
const sourceDirectory = join(root, 'android', 'app', 'src', 'main', 'java', 'com', 'scenic', 'pleos');
const tests = (await Promise.all([
  testFiles(join(root, 'android', 'tests')),
  testFiles(join(root, 'tests', 'api-client')),
  testFiles(join(root, 'tests', 'music-web')),
  testFiles(join(root, 'android', 'app', 'src', 'test', 'java')),
])).flat().sort();
if (!tests.length) throw new Error('No Java tests were discovered.');
const mains = [];
for (const test of tests) {
  const source = await readFile(test, 'utf8');
  const packageName = source.match(/^package\s+([\w.]+)\s*;/m)?.[1];
  const className = source.match(/\bpublic\s+(?:final\s+)?class\s+(\w+)/)?.[1];
  if (!packageName || !className || !/\bstatic\s+void\s+main\s*\(/.test(source)) {
    throw new Error(`Expected an executable pure Java test: ${relative(root, test)}`);
  }
  mains.push(`${packageName}.${className}`);
}
const jsonJar = await findJson();
await mkdir(cache, { recursive: true });
const classes = await mkdtemp(join(cache, 'classes-'));
try {
  console.log(`Compiling ${tests.length} Java suites using real JSON implementation: ${jsonJar}`);
  run(javaTool('javac'), ['--release', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-cp', jsonJar, '-d', classes,
    ...domainClasses.map(name => join(sourceDirectory, `${name}.java`)), ...tests]);
  for (const main of mains) run(javaTool('java'), ['-ea', '-Dfile.encoding=UTF-8', '-cp', `${classes}${delimiter}${jsonJar}`, main]);
  console.log(`All ${mains.length} Java suites passed.`);
} finally {
  // Only remove this invocation's temporary classes under the repository cache.
  const child = relative(cache, classes);
  if (child && !isAbsolute(child) && child !== '..' && !child.startsWith(`..${sep}`) && child.startsWith('classes-')) {
    await rm(classes, { recursive: true, force: true });
  }
}
