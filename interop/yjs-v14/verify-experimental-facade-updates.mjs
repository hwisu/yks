import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

import * as Y from 'yjs14'
import * as LegacyY from 'yjs'

import './assert-yjs-version.mjs'

const directory = process.argv[2]
if (directory == null) throw new Error('usage: node verify-experimental-facade-updates.mjs <directory>')

for (const format of ['v1', 'v2']) {
  const doc = new Y.Doc({ gc: false })
  const update = fs.readFileSync(path.join(directory, `facade-kotlin-${format}.bin`))
  const legacyDoc = new LegacyY.Doc({ gc: false })
  const legacyFormatted = legacyDoc.getArray('formatted')
  if (format === 'v1') LegacyY.applyUpdate(legacyDoc, update)
  else LegacyY.applyUpdateV2(legacyDoc, update)
  assert.deepEqual(legacyFormatted.toArray(), ['A', 1])
  if (format === 'v1') Y.applyUpdate(doc, update)
  else Y.applyUpdateV2(doc, update)

  const body = doc.get('body')
  const items = doc.get('items')
  const meta = doc.get('meta')
  const mixed = doc.get('mixed')
  const formatted = doc.get('formatted')
  assert.equal(body.toString(), 'A😀한')
  assert.deepEqual(body.toDelta().toJSON(), {
    type: 'delta',
    children: [{ type: 'insert', insert: 'A😀한', format: { bold: true } }],
  })
  assert.deepEqual(items.toArray(), [1, 'x', true])
  assert.deepEqual(meta.getAttrs(), { title: 'hello' })
  assert.deepEqual(mixed.toArray(), ['A', null, 7])
  assert.deepEqual(mixed.toDelta().toJSON(), {
    type: 'delta',
    children: [
      { type: 'insert', insert: 'A' },
      { type: 'insert', insert: [null, 7] },
    ],
  })
  assert.deepEqual(formatted.toArray(), ['A', 1])
  assert.deepEqual(formatted.toDelta().toJSON(), {
    type: 'delta',
    children: [
      { type: 'insert', insert: 'A', format: { bold: true } },
      { type: 'insert', insert: [1], format: { color: 'red' } },
    ],
  })
  assert.equal(doc.get('xml').toString(), '<p>hello</p>')

  body.applyDelta(body.change.retain(1).insert('!').done())
  items.applyDelta(items.change.retain(1).delete(1).insert(['y']).done())
  meta.applyDelta(meta.change.setAttr('verified', true).done())
  mixed.applyDelta(mixed.change.retain(2).delete(1).insert([8]).done())
  formatted.applyDelta(
    formatted.change
      .retain(1, { bold: null, italic: true })
      .retain(1, { color: 'blue' })
      .done(),
  )
  const paragraph = doc.get('xml').get(0)
  paragraph.applyDelta(paragraph.change.retain(paragraph.length).insert('!').done())

  const roundTrip = format === 'v1' ? Y.encodeStateAsUpdate(doc) : Y.encodeStateAsUpdateV2(doc)
  fs.writeFileSync(path.join(directory, `facade-yjs14-${format}.bin`), roundTrip)
}
