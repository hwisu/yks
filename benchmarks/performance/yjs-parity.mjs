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
const warmupIterations = Number.parseInt(process.env.BENCH_WARMUP ?? '20', 10)
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

const yjsScenarios = {
  apply_5000_structs: () => {
    const doc = new Y.Doc()
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

const comparison = Object.fromEntries(
  Object.keys(yjsResults).map((name) => [name, {
    yjs: yjsResults[name],
    yks: yksResults[name],
    medianRatio: yksResults[name].medianMs / yjsResults[name].medianMs,
    parity: yksResults[name].medianMs / yjsResults[name].medianMs <= 1.5,
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
    'YKS parity requires every measured workload to remain within 1.5x of Yjs',
  )
}
if (sink === Number.MIN_SAFE_INTEGER) console.log('unreachable', sink)
