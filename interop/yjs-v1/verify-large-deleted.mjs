import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

const [input, expectedClock, format = 'v1'] = process.argv.slice(2)
if (input == null || expectedClock == null) {
  throw new Error('usage: node verify-large-deleted.mjs <update.bin> <expected-clock> [v1|v2]')
}

const doc = new Y.Doc()
const update = fs.readFileSync(input)
if (format === 'v2') Y.applyUpdateV2(doc, update)
else Y.applyUpdate(doc, update)

assert.equal(Y.decodeStateVector(Y.encodeStateVector(doc)).get(1), Number(expectedClock))
assert.equal(doc.getArray('a').length, 0)
