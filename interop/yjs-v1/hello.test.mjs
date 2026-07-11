import fs from 'node:fs'
import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'

import {
  applyAndDescribe,
  createHelloDocument,
  describeDocument,
  helloFixturePath,
  readHelloExpected,
} from './fixture-helpers.mjs'

test('committed hello fixture is deterministic', () => {
  const expected = readHelloExpected()
  const generated = Y.encodeStateAsUpdate(createHelloDocument())
  const committed = fs.readFileSync(helloFixturePath)

  assert.deepEqual(Buffer.from(generated), committed)
  assert.equal(Buffer.from(committed).toString('base64'), expected.updateBase64)
})

test('upstream Yjs applies the committed V1 fixture', () => {
  const expected = readHelloExpected()
  assert.deepEqual(applyAndDescribe(fs.readFileSync(helloFixturePath)), {
    text: expected.text,
    json: expected.json,
    stateVectorBase64: expected.stateVectorBase64,
  })
})

test('the fixture describes the source document', () => {
  const expected = readHelloExpected()
  assert.deepEqual(describeDocument(createHelloDocument()), {
    text: expected.text,
    json: expected.json,
    stateVectorBase64: expected.stateVectorBase64,
  })
})
