import assert from 'node:assert/strict'
import test from 'node:test'
import * as Y from 'yjs14'

import './assert-yjs-version.mjs'

test('snapshot renderers retain cursor offsets inside deleted nodes', () => {
  const doc = new Y.Doc({ gc: false })
  const root = doc.get('nodes')
  const child = new Y.Node()
  root.push([child])
  child.insert(0, 'abc')
  const renderer = Y.createSnapshotRenderer(Y.snapshot(doc))
  const positions = [-1, 0, 1].map(assoc => Y.createRelativePositionFromTypeIndex(child, 1, assoc))
  root.delete(0, 1)

  for (const position of positions) {
    assert.equal(Y.createAbsolutePositionFromRelativePosition(position, doc).index, 0)
    const rendered = Y.createAbsolutePositionFromRelativePosition(position, doc, true, renderer)
    assert.equal(rendered.type, child)
    assert.equal(rendered.index, 1)
    assert.equal(rendered.assoc, position.assoc)
  }
  doc.destroy()
})
