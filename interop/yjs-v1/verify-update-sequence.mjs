import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

import { createScenarioDocument, materializeScenario } from './scenarios.mjs'

const scenario = process.argv[2]
const inputs = process.argv.slice(3)
if (scenario == null || inputs.length === 0) {
  throw new Error(
    'usage: node verify-update-sequence.mjs <scenario> <update.bin> [...]',
  )
}

const expected = createScenarioDocument(scenario)
const actual = new Y.Doc()
for (const input of inputs) {
  Y.applyUpdate(actual, fs.readFileSync(input))
}
materializeScenario(actual, scenario)

if (scenario !== 'subdoc-map' && scenario !== 'subdoc-array') {
  assert.deepEqual(actual.toJSON(), expected.toJSON())
}
assert.deepEqual(Y.encodeStateVector(actual), Y.encodeStateVector(expected))
if (scenario === 'formatted-text' || scenario === 'partial-formatted-text') {
  assert.deepEqual(actual.getText('body').toDelta(), expected.getText('body').toDelta())
}
if (scenario === 'subdoc-map') {
  assert.equal(actual.getMap('subs').get('child').guid, 'child')
}
if (scenario === 'subdoc-array') {
  const child = actual.getArray('subs').get(0)
  assert.equal(child.guid, 'child-guid')
  assert.equal(child.gc, false)
  assert.equal(child.autoLoad, true)
  assert.deepEqual(child.meta, { role: 'child' })
}
