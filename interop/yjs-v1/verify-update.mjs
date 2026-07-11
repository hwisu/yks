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

if (scenario === 'subdoc-text' || scenario === 'subdoc-xml-text') {
  const actual = new Y.Doc()
  if (scenario === 'subdoc-text') actual.getText('body')
  Y.applyUpdate(actual, fs.readFileSync(input))
  const child = [...actual.subdocs][0]
  assert.ok(child instanceof Y.Doc)
  assert.equal(child.guid, scenario === 'subdoc-text' ? 'text-child' : 'xml-child')
  assert.deepEqual([...actual.subdocs], [child])
} else if (scenario === 'hello') {
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
  if (scenario !== 'subdoc-map' && scenario !== 'subdoc-array') {
    assert.deepEqual(actual.toJSON(), expected.toJSON())
  }
  assert.deepEqual(Y.encodeStateVector(actual), Y.encodeStateVector(expected))
  if (
    scenario === 'formatted-text' ||
    scenario === 'formatted-embed' ||
    scenario === 'concurrent-format' ||
    scenario === 'partial-formatted-text'
  ) {
    assert.deepEqual(actual.getText('body').toDelta(), expected.getText('body').toDelta())
  }
  if (scenario === 'nested-map') {
    assert.ok(actual.getMap('root').get('profile') instanceof Y.Map)
  }
  if (scenario === 'nested-text') {
    assert.ok(actual.getArray('nodes').get(0) instanceof Y.Text)
  }
  if (scenario === 'subdoc-map') {
    const child = actual.getMap('subs').get('child')
    assert.ok(child instanceof Y.Doc)
    assert.equal(child.guid, 'child')
  }
  if (scenario === 'subdoc-array') {
    const child = actual.getArray('subs').get(0)
    assert.ok(child instanceof Y.Doc)
    assert.equal(child.guid, 'child-guid')
    assert.equal(child.gc, false)
    assert.equal(child.shouldLoad, true)
    assert.equal(child.autoLoad, true)
    assert.deepEqual(child.meta, { role: 'child' })
  }
}
