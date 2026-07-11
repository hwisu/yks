import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

import { applyAndDescribe, readHelloExpected } from './fixture-helpers.mjs'
import { createScenarioDocument, materializeScenario } from './scenarios.mjs'

const input = process.argv[2]
const scenario = process.argv[3] ?? 'hello'
if (input == null) {
  throw new Error('usage: npm run interop:verify -- <update.bin> [scenario]')
}

if (scenario === 'hello') {
  const expected = readHelloExpected()
  const actual = applyAndDescribe(fs.readFileSync(input))

  assert.equal(actual.text, expected.text)
  assert.deepEqual(actual.json, expected.json)
  assert.equal(actual.stateVectorBase64, expected.stateVectorBase64)
} else {
  const expected = createScenarioDocument(scenario)
  const actual = new Y.Doc()
  Y.applyUpdate(actual, fs.readFileSync(input))
  materializeScenario(actual, scenario)
  assert.deepEqual(actual.toJSON(), expected.toJSON())
  assert.deepEqual(Y.encodeStateVector(actual), Y.encodeStateVector(expected))
  if (scenario === 'nested-map') {
    assert.ok(actual.getMap('root').get('profile') instanceof Y.Map)
  }
  if (scenario === 'nested-text') {
    assert.ok(actual.getArray('nodes').get(0) instanceof Y.Text)
  }
}
