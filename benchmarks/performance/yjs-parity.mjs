import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { performance } from 'node:perf_hooks'
import process from 'node:process'
import * as Y from 'yjs'

const projectDirectory = path.resolve(import.meta.dirname, '../..')
const outputDirectory = path.join(projectDirectory, 'build', 'performance')
const fixturePath = path.join(outputDirectory, 'alternating-text-5000-v1.bin')
const formattedFixturePath = path.join(outputDirectory, 'formatted-text-5000-v1.bin')
const nestedFixturePath = path.join(outputDirectory, 'nested-array-3000-v1.bin')
const mapFixturePath = path.join(outputDirectory, 'map-5000-v1.bin')
const mapHistoryFixturePath = path.join(outputDirectory, 'map-history-5000-v1.bin')
const arrayFixturePath = path.join(outputDirectory, 'array-5000-v1.bin')
const fragmentedFixturePath = path.join(outputDirectory, 'fragmented-text-5000-v1.bin')
const concurrentFixturePath = path.join(outputDirectory, 'concurrent-text-1000-v1.bin')
const incrementalFixturePath = path.join(outputDirectory, 'incremental-text-1000-v1.txt')
// The JVM tiered compiler needs more whole-workload invocations than V8 for the fragmented-edit
// path. Fifty keeps the process-to-process parity gate stable; JMH remains the authoritative
// steady-state CPU/allocation measurement.
const warmupIterations = Number.parseInt(process.env.BENCH_WARMUP ?? '50', 10)
const sampleIterations = Number.parseInt(process.env.BENCH_SAMPLES ?? '30', 10)
const selectedScenarioNames = (process.env.BENCH_SCENARIOS ?? 'all')
  .split(',')
  .map(name => name.trim())
  .filter(Boolean)
const assertParity = process.argv.includes('--assert-parity')

fs.mkdirSync(outputDirectory, { recursive: true })

const fixtureDoc = new Y.Doc({ gc: false })
fixtureDoc.clientID = 1
const fixtureLeft = fixtureDoc.getText('left')
const fixtureRight = fixtureDoc.getText('right')
for (let index = 0; index < 5_000; index += 1) {
  const text = index % 2 === 0 ? fixtureLeft : fixtureRight
  text.insert(text.length, 'x')
}
const fixture = Y.encodeStateAsUpdate(fixtureDoc)
const fixtureStructCount = Y.decodeUpdate(fixture).structs.length
assert.equal(fixtureStructCount, 5_000)
fs.writeFileSync(fixturePath, fixture)

const formattedFixtureDoc = new Y.Doc({ gc: false })
formattedFixtureDoc.clientID = 2
const formattedLeft = formattedFixtureDoc.getText('left')
const formattedRight = formattedFixtureDoc.getText('right')
for (let index = 0; index < 5_000; index += 1) {
  const text = index % 2 === 0 ? formattedLeft : formattedRight
  text.insert(text.length, 'x')
}
formattedLeft.format(0, formattedLeft.length, { bold: true })
formattedRight.format(0, formattedRight.length, { italic: true })
const formattedFixture = Y.encodeStateAsUpdate(formattedFixtureDoc)
assert.equal(Y.decodeUpdate(formattedFixture).structs.length, 5_004)
fs.writeFileSync(formattedFixturePath, formattedFixture)

const nestedFixtureDoc = new Y.Doc({ gc: false })
nestedFixtureDoc.clientID = 3
const nestedRoot = nestedFixtureDoc.getArray('root')
nestedFixtureDoc.transact(() => {
  for (let index = 0; index < 3_000; index += 1) nestedRoot.push([new Y.Map()])
})
const nestedFixture = Y.encodeStateAsUpdate(nestedFixtureDoc)
fs.writeFileSync(nestedFixturePath, nestedFixture)

const mapFixtureDoc = new Y.Doc({ gc: false })
mapFixtureDoc.clientID = 4
const fixtureMap = mapFixtureDoc.getMap('map')
mapFixtureDoc.transact(() => {
  for (let index = 0; index < 5_000; index += 1) fixtureMap.set(`key-${index}`, index)
})
const mapFixture = Y.encodeStateAsUpdate(mapFixtureDoc)
fs.writeFileSync(mapFixturePath, mapFixture)

const mapHistoryFixtureDoc = new Y.Doc({ gc: false })
mapHistoryFixtureDoc.clientID = 7
const fixtureMapHistory = mapHistoryFixtureDoc.getMap('map')
mapHistoryFixtureDoc.transact(() => {
  for (let index = 0; index < 5_000; index += 1) fixtureMapHistory.set('key', index)
})
const mapHistoryFixture = Y.encodeStateAsUpdate(mapHistoryFixtureDoc)
assert.equal(Y.decodeUpdate(mapHistoryFixture).structs.length, 2)
fs.writeFileSync(mapHistoryFixturePath, mapHistoryFixture)

const arrayFixtureDoc = new Y.Doc({ gc: false })
arrayFixtureDoc.clientID = 5
arrayFixtureDoc.getArray('array').insert(0, Array.from({ length: 5_000 }, (_, index) => index))
const arrayFixture = Y.encodeStateAsUpdate(arrayFixtureDoc)
assert.equal(Y.decodeUpdate(arrayFixture).structs.length, 1)
fs.writeFileSync(arrayFixturePath, arrayFixture)

const fragmentedFixtureDoc = new Y.Doc({ gc: false })
fragmentedFixtureDoc.clientID = 6
const fragmentedFixtureText = fragmentedFixtureDoc.getText('body')
for (let index = 0; index < 5_000; index += 1) fragmentedFixtureText.insert(0, 'x')
const fragmentedFixture = Y.encodeStateAsUpdate(fragmentedFixtureDoc)
fs.writeFileSync(fragmentedFixturePath, fragmentedFixture)

const concurrentFixture = Y.mergeUpdates(Array.from({ length: 1_000 }, (_, index) => {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = index + 10_000
  doc.getText('body').insert(0, 'x')
  return Y.encodeStateAsUpdate(doc)
}))
assert.equal(Y.decodeUpdate(concurrentFixture).structs.length, 1_000)
fs.writeFileSync(concurrentFixturePath, concurrentFixture)

const incrementalFixtureDoc = new Y.Doc({ gc: false })
incrementalFixtureDoc.clientID = 8
const incrementalFixtureText = incrementalFixtureDoc.getText('body')
const incrementalUpdates = []
incrementalFixtureDoc.on('update', update => {
  incrementalUpdates.push(Buffer.from(update).toString('base64'))
})
for (let index = 0; index < 1_000; index += 1) {
  incrementalFixtureText.insert(incrementalFixtureText.length, 'x')
}
assert.equal(incrementalUpdates.length, 1_000)
fs.writeFileSync(incrementalFixturePath, `${incrementalUpdates.join('\n')}\n`)
const decodedIncrementalUpdates = incrementalUpdates.map(update => Buffer.from(update, 'base64'))

let sink = 0
function benchmark(scenario, repeatCount) {
  const hasPreparation = scenario.prepare !== undefined
  const prepare = scenario.prepare ?? (() => {})
  const operation = scenario.operation ?? scenario
  for (let index = 0; index < warmupIterations; index += 1) {
    for (let repeat = 0; repeat < repeatCount; repeat += 1) {
      prepare()
      sink = operation()
    }
  }
  const samples = []
  for (let index = 0; index < sampleIterations; index += 1) {
    global.gc?.()
    if (hasPreparation) {
      let elapsedMs = 0
      for (let repeat = 0; repeat < repeatCount; repeat += 1) {
        prepare()
        const started = performance.now()
        sink = operation()
        elapsedMs += performance.now() - started
      }
      samples.push(elapsedMs)
    } else {
      const started = performance.now()
      for (let repeat = 0; repeat < repeatCount; repeat += 1) {
        sink = operation()
      }
      samples.push(performance.now() - started)
    }
  }
  samples.sort((left, right) => left - right)
  const batchMedianMs = samples[Math.floor(samples.length / 2)]
  const batchP95Ms = samples[Math.floor((samples.length - 1) * 0.95)]
  return {
    repeatCount,
    batchMedianMs,
    batchP95Ms,
    medianMs: batchMedianMs / repeatCount,
    p95Ms: batchP95Ms / repeatCount,
  }
}

const lengthDoc = new Y.Doc()
const lengthText = lengthDoc.getText('body')
lengthText.insert(0, 'x'.repeat(5_000))

const stringDoc = new Y.Doc()
const stringText = stringDoc.getText('body')
stringText.insert(0, 'x'.repeat(5_000))

const encodeDoc = new Y.Doc()
Y.applyUpdate(encodeDoc, fixture)

const standardTransactionDoc = new Y.Doc()
Y.applyUpdate(standardTransactionDoc, fixture)
standardTransactionDoc.on('update', () => {})

const arrayReadDoc = new Y.Doc()
Y.applyUpdate(arrayReadDoc, arrayFixture)
const arrayRead = arrayReadDoc.getArray('array')

const clockRangeDoc = new Y.Doc()
const clockRangeText = clockRangeDoc.getText('body')
clockRangeText.insert(0, 'x'.repeat(20_000))
const clockRangeBefore = Y.snapshot(clockRangeDoc)
clockRangeText.insert(10_000, 'y')
const clockRangeAfter = Y.snapshot(clockRangeDoc)

const alternatingSnapshotDoc = new Y.Doc({ gc: false })
const alternatingSnapshotText = alternatingSnapshotDoc.getText('body')
alternatingSnapshotText.insert(0, 'x'.repeat(2_000))
const alternatingSnapshotBefore = Y.snapshot(alternatingSnapshotDoc)
for (let index = 999; index >= 0; index -= 1) {
  alternatingSnapshotText.delete(index * 2, 1)
}
const alternatingSnapshotAfter = Y.snapshot(alternatingSnapshotDoc)

const localFormatDoc = new Y.Doc({ gc: false })
const localFormatText = localFormatDoc.getText('body')
for (let index = 0; index < 50_000; index += 1) localFormatText.insert(0, 'x')
let localFormatValue = 0

const unrelatedObserverDoc = new Y.Doc({ gc: false })
const unrelatedObservedText = unrelatedObserverDoc.getText('observed')
const unrelatedTargetText = unrelatedObserverDoc.getText('target')
for (let index = 0; index < 50_000; index += 1) unrelatedTargetText.insert(0, 'x')
let unrelatedObserverEvents = 0
unrelatedObservedText.observe(() => { unrelatedObserverEvents += 1 })

const wideDeepDoc = new Y.Doc({ gc: false })
const wideDeepRoot = wideDeepDoc.getArray('root')
const wideDeepChildren = []
wideDeepDoc.transact(() => {
  for (let index = 0; index < 10_000; index += 1) {
    const child = new Y.Map()
    wideDeepRoot.push([child])
    wideDeepChildren.push(child)
  }
})
let wideDeepEvents = 0
wideDeepRoot.observeDeep(() => { wideDeepEvents += 1 })
let wideDeepValue = 0

let nestedDeleteDoc
let observedFragmentedState
function prepareNestedDelete() {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, nestedFixture)
  nestedDeleteDoc = doc
}
function prepareObservedFragmented() {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, fragmentedFixture)
  const text = doc.getText('body')
  let observed = 0
  doc.on('afterTransaction', () => { observed += 1 })
  observedFragmentedState = { text, observed: () => observed }
}
const observedMapDoc = new Y.Doc({ gc: false })
Y.applyUpdate(observedMapDoc, mapFixture)
const observedMap = observedMapDoc.getMap('map')
let observedMapEvents = 0
observedMap.observe(() => { observedMapEvents += 1 })
let observedMapIndex = 0

const yjsScenarios = {
  apply_5000_structs: () => {
    const doc = new Y.Doc()
    Y.applyUpdate(doc, fixture)
    return doc.getText('left').length + doc.getText('right').length
  },
  apply_5000_structs_open_roots: () => {
    const doc = new Y.Doc()
    doc.getText('left').toString()
    doc.getText('right').toString()
    Y.applyUpdate(doc, fixture)
    return doc.getText('left').length + doc.getText('right').length
  },
  append_5000: () => {
    const doc = new Y.Doc()
    const text = doc.getText('body')
    for (let index = 0; index < 5_000; index += 1) text.insert(text.length, 'x')
    return text.length
  },
  middle_edit_1000: () => {
    const doc = new Y.Doc()
    const text = doc.getText('body')
    text.insert(0, 'x'.repeat(5_000))
    for (let index = 0; index < 1_000; index += 1) {
      const middle = Math.floor(text.length / 2)
      text.insert(middle, 'y')
      text.delete(middle, 1)
    }
    return text.length
  },
  middle_edit_1000_no_gc: () => {
    const doc = new Y.Doc({ gc: false })
    const text = doc.getText('body')
    text.insert(0, 'x'.repeat(5_000))
    for (let index = 0; index < 1_000; index += 1) {
      const middle = Math.floor(text.length / 2)
      text.insert(middle, 'y')
      text.delete(middle, 1)
    }
    return text.length
  },
  middle_edit_1000_batched: () => {
    const doc = new Y.Doc()
    const text = doc.getText('body')
    text.insert(0, 'x'.repeat(5_000))
    doc.transact(() => {
      for (let index = 0; index < 1_000; index += 1) {
        const middle = Math.floor(text.length / 2)
        text.insert(middle, 'y')
        text.delete(middle, 1)
      }
    })
    return text.length
  },
  length_read_200000: () => {
    let sum = 0
    for (let index = 0; index < 200_000; index += 1) sum += lengthText.length
    return sum
  },
  string_read_50000: () => {
    let sum = 0
    for (let index = 0; index < 50_000; index += 1) sum += stringText.toString().length
    return sum
  },
  encode_5000_structs: () => Y.encodeStateAsUpdate(encodeDoc).length,
  standard_empty_tx_5000: () => {
    for (let index = 0; index < 1_000; index += 1) standardTransactionDoc.transact(() => {})
    return standardTransactionDoc.store.clients.size
  },
  formatted_apply_5004: () => {
    const doc = new Y.Doc()
    Y.applyUpdate(doc, formattedFixture)
    return doc.getText('left').length + doc.getText('right').length
  },
  nested_delete_3000: {
    prepare: prepareNestedDelete,
    operation: () => {
      const doc = nestedDeleteDoc
      const root = doc.getArray('root')
      root.delete(0, root.length)
      return root.length
    },
  },
  nested_apply_3000: () => {
    const doc = new Y.Doc({ gc: false })
    Y.applyUpdate(doc, nestedFixture)
    return doc.getArray('root').length
  },
  map_apply_5000: () => {
    const doc = new Y.Doc()
    Y.applyUpdate(doc, mapFixture)
    return doc.getMap('map').size
  },
  map_history_apply_5000: () => {
    const doc = new Y.Doc({ gc: false })
    Y.applyUpdate(doc, mapHistoryFixture)
    return doc.getMap('map').get('key')
  },
  array_apply_5000: () => {
    const doc = new Y.Doc({ gc: false })
    Y.applyUpdate(doc, arrayFixture)
    return doc.getArray('array').get(2_500)
  },
  array_insert_5000: () => {
    const doc = new Y.Doc({ gc: false })
    const array = doc.getArray('array')
    array.insert(0, Array.from({ length: 5_000 }, (_, index) => index))
    return array.get(2_500)
  },
  map_history_set_5000: () => {
    const doc = new Y.Doc({ gc: false })
    const map = doc.getMap('map')
    doc.transact(() => {
      for (let index = 0; index < 5_000; index += 1) map.set('key', index)
    })
    return map.get('key')
  },
  fragmented_apply_5000: () => {
    const doc = new Y.Doc({ gc: false })
    Y.applyUpdate(doc, fragmentedFixture)
    return doc.getText('body').length
  },
  fragmented_apply_5000_open_root: () => {
    const doc = new Y.Doc({ gc: false })
    doc.getText('body').toString()
    Y.applyUpdate(doc, fragmentedFixture)
    return doc.getText('body').length
  },
  concurrent_insert_apply_1000: () => {
    const doc = new Y.Doc({ gc: false })
    Y.applyUpdate(doc, concurrentFixture)
    return doc.getText('body').length
  },
  concurrent_insert_apply_1000_open_root: () => {
    const doc = new Y.Doc({ gc: false })
    doc.getText('body').toString()
    Y.applyUpdate(doc, concurrentFixture)
    return doc.getText('body').length
  },
  incremental_apply_1000: () => {
    const doc = new Y.Doc({ gc: false })
    for (const update of decodedIncrementalUpdates) Y.applyUpdate(doc, update)
    return doc.getText('body').length
  },
  roots_create_read_10000: () => {
    const doc = new Y.Doc()
    for (let index = 0; index < 10_000; index += 1) doc.getText(`root-${index}`)
    let sum = 0
    for (let index = 0; index < 10_000; index += 1) sum += doc.getText(`root-${index}`).length
    return doc.share.size + sum
  },
  edit_1000_with_10000_roots: () => {
    const doc = new Y.Doc()
    for (let index = 0; index < 10_000; index += 1) doc.getText(`root-${index}`)
    const text = doc.getText('root-0')
    for (let index = 0; index < 1_000; index += 1) {
      text.insert(0, 'x')
      text.delete(0, 1)
    }
    return doc.share.size + text.length
  },
  array_index_read_100000: () => {
    let sum = 0
    for (let index = 0; index < 100_000; index += 1) {
      sum += arrayRead.length
      sum += arrayRead.get(0)
    }
    return sum
  },
  observed_fragmented_edit_500: {
    prepare: prepareObservedFragmented,
    operation: () => {
      const state = observedFragmentedState
      const text = state.text
      text.doc.transact(() => {
        for (let index = 0; index < 500; index += 1) {
          const middle = Math.floor(text.length / 2)
          text.insert(middle, 'y')
          text.delete(middle, 1)
        }
      })
      return state.observed() + text.length
    },
  },
  observed_map_edit_1_on_5000: () => {
    const key = `key-${observedMapIndex % 5_000}`
    observedMapIndex += 1
    observedMap.set(key, -observedMapIndex)
    return observedMapEvents + observedMap.get(key)
  },
  clock_range_snapshot_delta: () => clockRangeText.toDelta(clockRangeAfter, clockRangeBefore).length,
  alternating_delete_snapshot_delta_1000: () =>
    alternatingSnapshotText.toDelta(alternatingSnapshotAfter, alternatingSnapshotBefore).length,
  local_format_first_char_on_50000: () => {
    localFormatValue += 1
    localFormatText.format(0, 1, { audit: localFormatValue })
    return localFormatText.length
  },
  unrelated_observer_edit_on_50000: () => {
    const middle = unrelatedTargetText.length >> 1
    unrelatedTargetText.insert(middle, 'y')
    unrelatedTargetText.delete(middle, 1)
    return unrelatedObserverEvents + unrelatedTargetText.length
  },
  deep_first_child_edit_10_on_10000: () => {
    for (let index = 0; index < 10; index += 1) {
      wideDeepValue += 1
      wideDeepChildren[0].set('value', wideDeepValue)
    }
    return wideDeepEvents + wideDeepValue
  },
}

// Each measured sample should be long enough that process/runtime timer noise cannot hide a large
// ratio. Counts target an approximately 8 ms Yjs batch while retaining the per-operation scenario
// contract. Warmups use the same amplified batch as samples: warming only one micro-operation per
// iteration leaves the JVM in C1/interpreted code while measuring thousands of operations.
const repeatCounts = {
  apply_5000_structs: 10,
  apply_5000_structs_open_roots: 10,
  append_5000: 1,
  middle_edit_1000: 2,
  middle_edit_1000_no_gc: 3,
  middle_edit_1000_batched: 1,
  length_read_200000: 80,
  string_read_50000: 50,
  encode_5000_structs: 16,
  standard_empty_tx_5000: 6,
  formatted_apply_5004: 6,
  nested_delete_3000: 32,
  nested_apply_3000: 16,
  map_apply_5000: 2,
  map_history_apply_5000: 128,
  array_apply_5000: 96,
  array_insert_5000: 40,
  map_history_set_5000: 1,
  fragmented_apply_5000: 4,
  fragmented_apply_5000_open_root: 4,
  concurrent_insert_apply_1000: 8,
  concurrent_insert_apply_1000_open_root: 8,
  incremental_apply_1000: 4,
  roots_create_read_10000: 5,
  edit_1000_with_10000_roots: 2,
  array_index_read_100000: 16,
  observed_fragmented_edit_500: 3,
  observed_map_edit_1_on_5000: 4_000,
  clock_range_snapshot_delta: 8_192,
  alternating_delete_snapshot_delta_1000: 12,
  local_format_first_char_on_50000: 512,
  unrelated_observer_edit_on_50000: 2_048,
  deep_first_child_edit_10_on_10000: 128,
}
assert.deepEqual(
  Object.keys(repeatCounts).sort(),
  Object.keys(yjsScenarios).sort(),
  'every performance scenario must have an explicit noise-amplifying repeat count',
)
const selectedYjsScenarios = selectedScenarioNames.includes('all')
  ? yjsScenarios
  : Object.fromEntries(selectedScenarioNames.map(name => {
      assert.ok(yjsScenarios[name], `unknown performance scenario: ${name}`)
      return [name, yjsScenarios[name]]
    }))

const yjsResults = Object.fromEntries(
  Object.entries(selectedYjsScenarios).map(([name, operation]) => [
    name,
    benchmark(operation, repeatCounts[name]),
  ]),
)

const encodedRepeatCounts = Object.entries(repeatCounts)
  .map(([name, count]) => `${name}=${count}`)
  .join(',')
const gradle = spawnSync(
  path.join(projectDirectory, 'gradlew'),
  [
    '-q',
    'performanceBenchmark',
    '--no-daemon',
    `-PperformanceFixture=${fixturePath}`,
    `-PperformanceWarmup=${warmupIterations}`,
    `-PperformanceSamples=${sampleIterations}`,
    `-PperformanceScenarios=${selectedScenarioNames.join(',')}`,
    `-PperformanceRepeatCounts=${encodedRepeatCounts}`,
  ],
  { cwd: projectDirectory, encoding: 'utf8', env: process.env },
)
if (gradle.status !== 0) {
  process.stderr.write(gradle.stdout)
  process.stderr.write(gradle.stderr)
  process.exit(gradle.status ?? 1)
}
const marker = 'YKS_BENCHMARK_JSON='
const resultLine = gradle.stdout.split(/\r?\n/u).find((line) => line.startsWith(marker))
assert.ok(resultLine, `missing YKS benchmark result in:\n${gradle.stdout}`)
const yksResults = JSON.parse(resultLine.slice(marker.length))

// Ratio parity is mandatory now that every sample is amplified above timer noise. Keep the
// per-operation absolute latency budget as an independent safety condition for micro workloads;
// it is no longer an escape hatch for an arbitrarily large ratio.
const absoluteLatencyBudgetMs = 6
const comparison = Object.fromEntries(
  Object.keys(yjsResults).map((name) => {
    const medianRatio = yksResults[name].medianMs / yjsResults[name].medianMs
    const ratioParity = medianRatio <= 1.5
    const absoluteLatencyApplies = yjsResults[name].medianMs <= absoluteLatencyBudgetMs
    const absoluteLatencyPass =
      !absoluteLatencyApplies || yksResults[name].medianMs <= absoluteLatencyBudgetMs
    return [name, {
      yjs: yjsResults[name],
      yks: yksResults[name],
      medianRatio,
      ratioParity,
      absoluteLatencyApplies,
      absoluteLatencyPass,
      gatePass: ratioParity && absoluteLatencyPass,
    }]
  }),
)
fs.writeFileSync(
  path.join(outputDirectory, 'comparison.json'),
  `${JSON.stringify({ fixtureBytes: fixture.length, fixtureStructs: fixtureStructCount, comparison }, null, 2)}\n`,
)

console.log(`fixture: ${fixture.length.toLocaleString()} bytes, ${fixtureStructCount.toLocaleString()} structs`)
console.table(Object.fromEntries(Object.entries(comparison).map(([name, result]) => [name, {
  'Yjs median ms': result.yjs.medianMs.toFixed(3),
  'YKS median ms': result.yks.medianMs.toFixed(3),
  repeats: result.yjs.repeatCount,
  'Yjs batch ms': result.yjs.batchMedianMs.toFixed(1),
  'YKS/Yjs': result.medianRatio.toFixed(2),
  ratio: result.ratioParity ? 'pass' : 'over',
  latency: result.absoluteLatencyPass ? 'pass' : 'over',
  gate: result.gatePass ? 'pass' : 'FAIL',
}])) )
if (assertParity) {
  const failures = Object.entries(comparison).filter(([, result]) => !result.gatePass)
  assert.deepEqual(
    failures.map(([name]) => name),
    [],
    'YKS performance gate requires every amplified workload to remain within 1.5x of Yjs and every micro workload to remain within the independent 6 ms per-operation budget',
  )
}
if (sink === Number.MIN_SAFE_INTEGER) console.log('unreachable', sink)
