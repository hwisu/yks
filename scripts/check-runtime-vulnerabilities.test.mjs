import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { checkMavenDependencies, collectMavenDependencies } from "./check-runtime-vulnerabilities.mjs";

test("checks consumer plugins and build dependencies even when runtime dependencies are clean", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "yks-dependency-audit-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, "gradle.lockfile"), [
    "# generated lockfile",
    "org.jetbrains.kotlin:kotlin-stdlib:2.4.21=runtimeClasspath",
    "org.junit.jupiter:junit-jupiter:6.1.3=testRuntimeClasspath",
    "empty=annotationProcessor",
  ].join("\n"));
  for (const [relative, component] of [
    ["gradle", '<component group="org.jetbrains.kotlin" name="kotlin-stdlib" version="2.4.21"/>'],
    ["consumer-smoke/gradle", '<component group="org.jetbrains.kotlin" name="kotlin-gradle-plugin" version="2.3.21"/>'],
  ]) {
    fs.mkdirSync(path.join(root, relative), { recursive: true });
    fs.writeFileSync(path.join(root, relative, "verification-metadata.xml"), `<components>${component}</components>`);
  }
  const entries = collectMavenDependencies(root);
  assert.equal(entries.length, 3, "deduplicate artifacts shared by the lockfile and verification metadata");
  await assert.rejects(checkMavenDependencies(entries, async (_url, options) => {
    const { queries } = JSON.parse(options.body);
    assert.ok(queries.some((q) => q.package.name === "org.junit.jupiter:junit-jupiter"));
    return Response.json({ results: queries.map((q) => (
      q.package.name === "org.jetbrains.kotlin:kotlin-gradle-plugin"
        ? { vulns: [{ id: "GHSA-r937-wjx7-w2jp" }] }
        : {}
    )) });
  }), /org\.jetbrains\.kotlin:kotlin-gradle-plugin:2\.3\.21: GHSA-r937-wjx7-w2jp/u);
  fs.unlinkSync(path.join(root, "consumer-smoke/gradle/verification-metadata.xml"));
  assert.throws(() => collectMavenDependencies(root), /ENOENT/u);
});

test("fails closed on incomplete results or an unavailable advisory service", async () => {
  const entries = [["example:library:1.0", { package: { ecosystem: "Maven", name: "example:library" }, version: "1.0" }]];
  await checkMavenDependencies(entries, async () => Response.json({ results: [{}] }));
  await assert.rejects(
    checkMavenDependencies(entries, async () => Response.json({ results: [] })),
    /incomplete result set/u,
  );
  await assert.rejects(
    checkMavenDependencies(entries, async () => new Response(null, { status: 503 })),
    /HTTP 503/u,
  );
});
