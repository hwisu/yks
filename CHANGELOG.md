# Changelog

## Unreleased — 0.2.15

- Updated the build to Kotlin 2.4.20 and Gradle 9.7.1, with JUnit 6.1.3 and Rust 1.98.1 for verification.
- Preserved the released JVM ABI and standalone consumption with Kotlin 2.3.21.
- Updated the self-produced release artifact verification rule to 0.2.15 and made CI exercise release-version consumers.
- Updated the Yrs 0.28 oracle integration while retaining byte-identical fixtures and the Yjs 13.6.32 / Yjs 14 rc.26 wire targets.

## 0.2.14 — 2026-09-10

- Avoided JVM stack growth when decoding, encoding, copying, comparing, and hashing deeply nested list/map values.
- Added the optional process-wide `dev.yks.maxDecodedNestingDepth` decoder policy while preserving the default nesting compatibility.
- Removed redundant collection allocations and reused state-vector encoding logic without changing public APIs or wire formats.

## 0.2.13 — 2026-09-08

- Updated the experimental Yjs 14 oracle to rc.26 and added the Kotlin `Node` alias and accessors while retaining the `Type` ABI.
- Added rc.26 `$doc`, `$nodeAny`, and name-aware `$node` schema markers.
- Updated the opt-in renderer marker to `y:renderer` while preserving the stable `y:r` aliases.
- Fixed left-associated relative positions in deleted content retained by a renderer.
- Verified accepted nested suggestions retain attributes and child content against the rc.26 oracle.

## 0.2.12 — 2026-08-30

- Completed the opt-in v14 `Type` collection and attribute helpers, including renderer detach and snapshot-aware attribute reads.
- Preserved nested shared-type identity when v14 values are read from list/map data containers.

## 0.2.11 — 2026-08-24

- Added unchanged full-state V2 caching and defensive storage of mutable inputs.
- Optimized hot paths for text editing, remote apply, and update codecs.
- Preserved the public ABI and Yjs/Yrs wire compatibility.

See [Git tags](https://github.com/hwisu/yks/tags) for earlier versions.
