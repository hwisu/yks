# Yjs performance parity benchmark

Run both warmed runtimes with the same logical workloads:

```sh
npm run benchmark:performance
```

The runner creates the same 5,000-struct V1 fixture for both implementations,
uses separate warmup and sample iterations, and writes raw medians, p95 values,
and YKS/Yjs ratios to `build/performance/comparison.json`.

Override the default 8 warmups and 15 samples when profiling:

```sh
BENCH_WARMUP=3 BENCH_SAMPLES=5 npm run benchmark:performance
```

The benchmark covers remote apply, repeated append, repeated middle edits,
maintained length reads, text rendering, and full-state encoding. It is not part
of `check`; CI performance gates should run on a dedicated stable runner.
