import assert from 'node:assert/strict'
import test from 'node:test'
import * as Y from 'yjs14'

import './assert-yjs-version.mjs'

test('accepting a suggested node retains attribute history and nested children only', () => {
  const prev = new Y.Doc({ gc: false })
  const next = Y.cloneDoc(prev)
  const root = next.get('nodes')
  const node = new Y.Node()
  root.push([node])
  node.setAttr('src', 'old.png')
  node.setAttr('src', 'new.png')
  node.setAttr('temporary', true)
  node.deleteAttr('temporary')
  const caption = new Y.Node()
  node.setAttr('caption', caption)
  caption.insert(0, 'Hello!')
  caption.delete(5, 1)
  next.get('unrelated').insert(0, 'pending')
  const renderer = Y.createDiffRenderer(prev, next)

  renderer.acceptChanges(node._item.id)

  const acceptedRoot = prev.get('nodes')
  const acceptedNode = acceptedRoot.get(0)
  assert.equal(acceptedRoot.length, 1)
  assert.equal(acceptedNode.getAttr('src'), 'new.png')
  assert.equal(acceptedNode.hasAttr('temporary'), false)
  assert.equal(acceptedNode.getAttr('caption').toString(), 'Hello')
  assert.equal(prev.get('unrelated').toString(), '')
  assert.equal(next.get('unrelated').toString(), 'pending')
  renderer.destroy()
  next.destroy()
  prev.destroy()
})
