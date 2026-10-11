import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const repository = path.resolve(import.meta.dirname, "..");

export function collectMavenDependencies(root) {
  const lockfile = path.join(root, "gradle.lockfile");
  assert.ok(fs.existsSync(lockfile), "the JVM dependency lockfile is missing");

  const coordinates = new Map();
  function add(group, artifact, version) {
    coordinates.set(`${group}:${artifact}:${version}`, {
      package: { ecosystem: "Maven", name: `${group}:${artifact}` },
      version,
    });
  }
  for (const line of fs.readFileSync(lockfile, "utf8").split(/\r?\n/u)) {
    const match = line.match(/^([^:#=]+):([^:=]+):([^=]+)=/u);
    if (match !== null) add(...match.slice(1));
  }
  assert.ok(coordinates.size > 0, "no locked JVM dependencies were found");

  // Gradle plugins are not part of the runtime lockfile. Include both builds'
  // verified artifacts so the consumer/compiler checks cannot hide advisories.
  for (const relative of [
    "gradle/verification-metadata.xml",
    "consumer-smoke/gradle/verification-metadata.xml",
  ]) {
    const metadata = fs.readFileSync(path.join(root, relative), "utf8");
    const components = [...metadata.matchAll(/<component group="([^"]+)" name="([^"]+)" version="([^"]+)"/gu)];
    assert.ok(components.length > 0, `no verified Maven components found in ${relative}`);
    for (const match of components) add(...match.slice(1));
  }
  return [...coordinates.entries()].sort(([left], [right]) => left.localeCompare(right));
}

export async function checkMavenDependencies(entries, fetchImplementation = fetch) {
  const response = await fetchImplementation("https://api.osv.dev/v1/querybatch", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ queries: entries.map(([, query]) => query) }),
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) {
    throw new Error(`OSV query failed with HTTP ${response.status}`);
  }

  const body = await response.json();
  assert.equal(body.results.length, entries.length, "OSV returned an incomplete result set");
  const findings = body.results.flatMap((result, index) =>
    (result.vulns ?? []).map((vulnerability) => ({
      coordinate: entries[index][0],
      id: vulnerability.id,
    })),
  );

  if (findings.length > 0) {
    const details = findings.map(({ coordinate, id }) => `${coordinate}: ${id}`).join("\n");
    throw new Error(`${findings.length} known JVM dependency vulnerability finding(s)\n${details}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const entries = collectMavenDependencies(repository);
  await checkMavenDependencies(entries);
  process.stdout.write(
    `OSV dependency check: ${entries.length} locked/verified Maven coordinates (runtime, test, build and consumer), no known findings\n`,
  );
}
