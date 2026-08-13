import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

import * as Y from 'yjs14'

import './assert-yjs-version.mjs'

const directory = process.argv[2]
if (directory == null) throw new Error('usage: node verify-roundtrip-updates.mjs <directory>')

for (const format of ['v1', 'v2']) {
  const target = new Y.Doc({ gc: false })
  const update = fs.readFileSync(path.join(directory, `roundtrip-${format}.bin`))
  if (format === 'v1') Y.applyUpdate(target, update)
  else Y.applyUpdateV2(target, update)

  const body = target.get('body')
  assert.equal(body.toString(), 'A😀한')
  assert.deepEqual(body.toDelta().toJSON(), {
    type: 'delta',
    children: [{ type: 'insert', insert: 'A😀한', format: { bold: true } }],
  })
  assert.deepEqual(target.get('items').toArray(), [1, 'x', true])
  assert.deepEqual(target.get('meta').getAttrs(), { title: 'hello' })
  assert.equal(target.get('xml').toString(), '<p id="intro">hello</p>')
}
