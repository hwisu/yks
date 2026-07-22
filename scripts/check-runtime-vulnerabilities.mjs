import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const repository = path.resolve(import.meta.dirname, "..");
const lockfile = path.join(repository, "gradle.lockfile");

assert.ok(fs.existsSync(lockfile), "the JVM dependency lockfile is missing");

const coordinates = new Map();
for (const line of fs.readFileSync(lockfile, "utf8").split(/\r?\n/u)) {
  const [coordinate, configurations = ""] = line.split("=", 2);
  const match = coordinate.match(/^([^:=]+):([^:=]+):([^=]+)$/u);
  if (match === null || !configurations.split(",").includes("runtimeClasspath")) continue;
  const [, group, artifact, version] = match;
  const id = `${group}:${artifact}:${version}`;
  coordinates.set(id, {
    package: {
      ecosystem: "Maven",
      name: `${group}:${artifact}`,
    },
    version,
  });
}

const entries = [...coordinates.entries()].sort(([left], [right]) => left.localeCompare(right));
assert.ok(entries.length > 0, "no locked JVM runtime dependencies were found");

const response = await fetch("https://api.osv.dev/v1/querybatch", {
  method: "POST",
  headers: {
    "content-type": "application/json",
  },
  body: JSON.stringify({
    queries: entries.map(([, query]) => query),
  }),
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
  for (const finding of findings) {
    process.stderr.write(`${finding.coordinate}: ${finding.id}\n`);
  }
  throw new Error(`${findings.length} known JVM runtime vulnerability finding(s)`);
}

process.stdout.write(
  `OSV runtime dependency check: ${entries.length} locked Maven coordinates, no known findings\n`,
);
