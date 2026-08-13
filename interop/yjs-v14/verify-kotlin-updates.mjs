import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

import * as Y from 'yjs14'

import './assert-yjs-version.mjs'

const directory = process.argv[2]
if (directory == null) throw new Error('usage: node verify-kotlin-updates.mjs <directory>')

for (const format of ['v1', 'v2']) {
  const target = new Y.Doc({ gc: false })
  const update = fs.readFileSync(path.join(directory, `kotlin-${format}.bin`))
  if (format === 'v1') Y.applyUpdate(target, update)
  else Y.applyUpdateV2(target, update)

  assert.equal(target.get('body').toString(), 'B😀한')
  assert.deepEqual(target.get('items').toArray(), [2, 'y', false])
  assert.deepEqual(target.get('meta').getAttrs(), { title: 'world' })
  assert.equal(target.get('xml').toString(), '<q id="outro" />')
}
