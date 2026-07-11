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

assert.deepEqual(actual.toJSON(), expected.toJSON())
assert.deepEqual(Y.encodeStateVector(actual), Y.encodeStateVector(expected))
