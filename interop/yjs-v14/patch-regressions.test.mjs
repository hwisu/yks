import assert from 'node:assert/strict'
import test from 'node:test'
import * as Y13 from 'yjs'
import * as Y14 from 'yjs14'

import '../yjs-v1/assert-yjs-version.mjs'
import './assert-yjs-version.mjs'

const orders = [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]]

for (const [name, Y, getText] of [
  ['Yjs 13', Y13, (doc, key) => doc.getText(key)],
  ['Yjs 14', Y14, (doc, key) => doc.get(key)],
]) {
  for (const v2 of [false, true]) {
    test(`${name} replays pending updates in all orders, V${v2 ? 2 : 1}`, () => {
      const source = new Y.Doc({ gc: false })
      source.clientID = 1
      const a = getText(source, 'a')
      const b = getText(source, 'b')
      const encode = v2 ? Y.encodeStateAsUpdateV2 : Y.encodeStateAsUpdate
      const apply = v2 ? Y.applyUpdateV2 : Y.applyUpdate
      const updates = [() => a.insert(0, 'A'), () => a.insert(1, 'B'), () => b.insert(0, 'C')].map(edit => {
        const before = Y.encodeStateVector(source)
        edit()
        return encode(source, before)
      })
      for (const order of orders) {
        const target = new Y.Doc({ gc: false })
        for (const index of order) apply(target, updates[index])
        assert.equal(getText(target, 'a').toString(), 'AB')
        assert.equal(getText(target, 'b').toString(), 'C')
        assert.equal(target.store.pendingStructs, null)
        assert.deepEqual(Y.encodeStateVector(target), Y.encodeStateVector(source))
        target.destroy()
      }
      source.destroy()
    })
  }

  test(`${name} retains cursor offsets through repeated undo and redo`, () => {
    const doc = new Y.Doc()
    const text = getText(doc, 'body')
    const undo = new Y.UndoManager(text)
    text.insert(0, 'hello world')
    const positions = Array.from({ length: 12 }, (_, index) => Y.createRelativePositionFromTypeIndex(text, index))
    for (let i = 0; i < 2; i++) {
      undo.undo()
      assert.equal(text.toString(), '')
      undo.redo()
      positions.forEach((position, index) => {
        assert.equal(Y.createAbsolutePositionFromRelativePosition(position, doc).index, index)
      })
    }
    doc.destroy()
  })

  test(`${name} splits surrogate pairs with replacement characters`, () => {
    const doc = new Y.Doc()
    const text = getText(doc, 'body')
    text.insert(0, '😀')
    text.insert(1, 'x')
    assert.equal(text.toString(), '\uFFFDx\uFFFD')
    doc.destroy()
  })

  test(`${name} empty-block behavior documents the stricter YKS input policy`, () => {
    // Yrs 0.28.1 and YKS reject these malformed structs. Yjs currently decodes
    // them; rc.29's multi-update merge throws. Do not mistake this for valid wire data.
    for (const ref of [0, 10]) {
      const bytes = Uint8Array.of(1, 1, 96, 0, ref, 0, 0)
      assert.equal(Y.decodeUpdate(bytes).structs[0].length, 0)
      const v2 = Y.convertUpdateFormatV1ToV2(bytes)
      assert.equal(Y.decodeUpdateV2(v2).structs[0].length, 0)
      const merge = () => Y.mergeUpdates([bytes, Uint8Array.of(0, 0)])
      if (Y === Y14) assert.throws(merge, TypeError)
      else assert.doesNotThrow(merge)
    }
  })
}
