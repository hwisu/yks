import fs from 'node:fs'
import assert from 'node:assert/strict'

import { applyAndDescribe, readHelloExpected } from './fixture-helpers.mjs'

const input = process.argv[2]
if (input == null) {
  throw new Error('usage: npm run interop:verify -- <update.bin>')
}

const expected = readHelloExpected()
const actual = applyAndDescribe(fs.readFileSync(input))

assert.equal(actual.text, expected.text)
assert.deepEqual(actual.json, expected.json)
assert.equal(actual.stateVectorBase64, expected.stateVectorBase64)
