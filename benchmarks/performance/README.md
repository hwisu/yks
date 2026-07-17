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

The 28 scenarios cover:

- 5,000-struct remote apply into unopened and already-open roots, repeated
  append, GC/non-GC/batched middle edits, cached length, rendering, full-state
  encoding, and empty transactions;
- a 5,004-struct formatted update, deletion of 3,000 nested types, a 5,000-key
  map update, a packed 5,000-replacement single-key map history, a packed
  5,000-value array update/local batch insert, 5,000 same-key local map sets,
  3,000 nested-type apply, 5,000 prepended-text apply and 1,000 concurrent
  same-position inserts into both unopened and already-open roots,
  creation/readback of 10,000 roots, and 1,000 edits after materializing those
  10,000 roots;
- 100,000 indexed array reads, 500 observed edits in a 5,000-struct fragmented
  text, one observed edit in a 5,000-key map, and snapshot delta traversal
  across a packed 20,000-code-unit clock range.

`benchmark:performance:check` requires each YKS median to be no greater than
`max(Yjs * 1.5, 6 ms)`. The absolute budget is limited to process-level
single-digit-millisecond workloads where V8/JVM startup and tiered-compilation
noise dominate the ratio. The JVM quiescence window after warmup is outside the
measured interval. JMH remains the authoritative CPU/allocation benchmark.

The cross-runtime benchmark is not part of `check`; CI performance gates should
run on a dedicated stable runner.
