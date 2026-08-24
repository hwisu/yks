# Yjs performance parity benchmark

Run both warmed runtimes with the same logical workloads:

```sh
npm run benchmark:performance
```

The runner creates the same 5,000-struct V1 fixture for both implementations,
uses separate warmup and sample iterations, and writes raw medians, p95 values,
and YKS/Yjs ratios to `build/performance/comparison.json`.

The default is 50 warmups and 30 samples. Override it for a quick local
profiling pass:

```sh
BENCH_WARMUP=3 BENCH_SAMPLES=5 npm run benchmark:performance
```

The 38 scenarios cover:

- 5,000-struct remote apply into unopened and already-open roots, repeated
  append, GC/non-GC/batched middle edits, cached length, rendering, full-state
  V1/V2 encoding, and empty transactions;
- a 5,004-struct formatted update, deletion of 3,000 nested types, a 5,000-key
  map update, a packed 5,000-replacement single-key map history, a packed
  5,000-value array update/local batch insert, 5,000 same-key local map sets,
  3,000 nested-type apply, 5,000 prepended-text apply and 1,000 concurrent
  same-position inserts into both unopened and already-open roots,
  creation/readback of 10,000 roots, and 1,000 edits after materializing those
  10,000 roots;
- 100,000 indexed array reads, 500 observed edits in a 5,000-struct fragmented
  text, one observed edit in a 5,000-key map, and snapshot delta traversal
  across packed and alternating delete-set clock ranges;
- fragmented-prefix formatting, an unrelated observer, a wide deep observer,
  XML construction/rendering, relative-position resolution, V2 merge/diff,
  and 1,000 undo plus 1,000 redo operations.

`benchmark:performance:check` requires every YKS median to satisfy both
`YKS/Yjs <= 1.5x` and, when the Yjs median is at most 6 ms, `YKS <= 6 ms`.
Neither condition is a fallback for the other. Each JVM scenario collects its
warmup garbage before an out-of-measurement quiescence window so queued C2
compilation and prior scenario order do not distort the samples. JMH remains
the authoritative CPU/allocation benchmark.

`benchmark:performance:advanced` enforces the same gate for the four advanced
paths. Cross-runtime performance is intentionally separate from Gradle
`check`; CI performance gates should run on a dedicated stable runner.

JMH additionally measures direct mixed-string encoding, cached V2 full-state
encoding, first-time middle formatting in an unformatted fragmented text, and
remote apply for 5,000 nested maps with one key each so allocation-sensitive
shape regressions remain visible without adding unstable cross-runtime ratio
gates.
