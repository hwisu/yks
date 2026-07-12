import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

const [input, format = 'v1'] = process.argv.slice(2)
if (input == null) throw new Error('usage: node verify-codec-values.mjs <update.bin> [v1|v2]')

const doc = new Y.Doc()
const update = fs.readFileSync(input)
if (format === 'v2') Y.applyUpdateV2(doc, update)
else Y.applyUpdate(doc, update)

assert.equal(doc.getText('body').toString(), 'A😀B')
const value = doc.getArray('values').get(0)
assert.deepEqual(Object.keys(value), ['2', '10', 'b', 'a', 'undef', 'big', 'negzero', 'nan', 'positiveInfinity', 'negativeInfinity'])
assert.equal(value.undef, undefined)
assert.equal(value.big, 9007199254740993n)
assert.ok(Object.is(value.negzero, -0))
assert.ok(Number.isNaN(value.nan))
assert.equal(value.positiveInfinity, Infinity)
assert.equal(value.negativeInfinity, -Infinity)
const child = doc.getArray('subs').get(0)
assert.ok(child instanceof Y.Doc)
assert.equal(child.guid, 'default-child')
assert.equal(child.shouldLoad, false)
