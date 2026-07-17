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
// The JVM tiered compiler needs more whole-workload invocations than V8 for the fragmented-edit
// path. Fifty keeps the process-to-process parity gate stable; JMH remains the authoritative
// steady-state CPU/allocation measurement.
const warmupIterations = Number.parseInt(process.env.BENCH_WARMUP ?? '50', 10)
const sampleIterations = Number.parseInt(process.env.BENCH_SAMPLES ?? '30', 10)
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

let sink = 0
function benchmark(operation) {
  for (let index = 0; index < warmupIterations; index += 1) sink = operation()
  const samples = []
  for (let index = 0; index < sampleIterations; index += 1) {
    global.gc?.()
    const started = performance.now()
    sink = operation()
    samples.push(performance.now() - started)
  }
  samples.sort((left, right) => left - right)
  return {
    medianMs: samples[Math.floor(samples.length / 2)],
    p95Ms: samples[Math.floor((samples.length - 1) * 0.95)],
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

const invocationCount = warmupIterations + sampleIterations
const nestedDeleteDocs = Array.from({ length: invocationCount }, () => {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, nestedFixture)
  return doc
})
let nestedDeleteIndex = 0
const observedFragmentedDocs = Array.from({ length: invocationCount }, () => {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, fragmentedFixture)
  const text = doc.getText('body')
  let observed = 0
  doc.on('afterTransaction', () => { observed += 1 })
  return { text, observed: () => observed }
})
let observedFragmentedIndex = 0
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
  nested_delete_3000: () => {
    const doc = nestedDeleteDocs[nestedDeleteIndex++]
    const root = doc.getArray('root')
    root.delete(0, root.length)
    return root.length
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
  observed_fragmented_edit_500: () => {
    const state = observedFragmentedDocs[observedFragmentedIndex++]
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
  observed_map_edit_1_on_5000: () => {
    const key = `key-${observedMapIndex % 5_000}`
    observedMapIndex += 1
    observedMap.set(key, -observedMapIndex)
    return observedMapEvents + observedMap.get(key)
  },
  clock_range_snapshot_delta: () => clockRangeText.toDelta(clockRangeAfter, clockRangeBefore).length,
}

const yjsResults = Object.fromEntries(
  Object.entries(yjsScenarios).map(([name, operation]) => [name, benchmark(operation)]),
)

const gradle = spawnSync(
  path.join(projectDirectory, 'gradlew'),
  [
    '-q',
    'performanceBenchmark',
    '--no-daemon',
    `-PperformanceFixture=${fixturePath}`,
    `-PperformanceWarmup=${warmupIterations}`,
    `-PperformanceSamples=${sampleIterations}`,
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

// Separate V8 and JVM processes have unstable ratios in the single-digit millisecond range.
// Keep those cases bounded by a strict absolute latency budget; workloads above this floor must
// still remain within 1.5x of Yjs.
const absoluteNoiseFloorMs = 6
const comparison = Object.fromEntries(
  Object.keys(yjsResults).map((name) => [name, {
    yjs: yjsResults[name],
    yks: yksResults[name],
    medianRatio: yksResults[name].medianMs / yjsResults[name].medianMs,
    parity: yksResults[name].medianMs <= Math.max(yjsResults[name].medianMs * 1.5, absoluteNoiseFloorMs),
  }]),
)
fs.writeFileSync(
  path.join(outputDirectory, 'comparison.json'),
  `${JSON.stringify({ fixtureBytes: fixture.length, fixtureStructs: fixtureStructCount, comparison }, null, 2)}\n`,
)

console.log(`fixture: ${fixture.length.toLocaleString()} bytes, ${fixtureStructCount.toLocaleString()} structs`)
console.table(Object.fromEntries(Object.entries(comparison).map(([name, result]) => [name, {
  'Yjs median ms': result.yjs.medianMs.toFixed(3),
  'YKS median ms': result.yks.medianMs.toFixed(3),
  'YKS/Yjs': result.medianRatio.toFixed(2),
  parity: result.parity ? 'pass' : 'FAIL',
}])) )
if (assertParity) {
  const failures = Object.entries(comparison).filter(([, result]) => !result.parity)
  assert.deepEqual(
    failures.map(([name]) => name),
    [],
    'YKS parity requires every workload to remain within 1.5x of Yjs or the 6 ms microbenchmark budget',
  )
}
if (sink === Number.MIN_SAFE_INTEGER) console.log('unreachable', sink)
