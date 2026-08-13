import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'
import {
  Awareness,
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
} from 'y-protocols/awareness.js'

import './assert-version.mjs'

test('pinned awareness oracle uses monotonic clocks and JSON state', () => {
  const sourceDoc = new Y.Doc()
  sourceDoc.clientID = 7
  const source = new Awareness(sourceDoc)
  source.setLocalState({ name: 'Ada 😀', cursor: [3, 9] })
  const targetDoc = new Y.Doc()
  targetDoc.clientID = 8
  const target = new Awareness(targetDoc)

  applyAwarenessUpdate(target, encodeAwarenessUpdate(source, [7]), 'test')

  assert.deepEqual(target.getStates().get(7), { name: 'Ada 😀', cursor: [3, 9] })
  assert.equal(target.meta.get(7).clock, 1)
  source.destroy()
  target.destroy()
})
