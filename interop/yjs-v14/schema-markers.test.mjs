import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y14 from 'yjs14'

import './assert-yjs-version.mjs'

test('pinned Yjs 14 schema marker identities and checks remain stable', () => {
  const doc = new Y14.Doc({ gc: false })
  const type = doc.get('body')
  const renderer = new Y14.AbstractRenderer()

  assert.equal(doc.$type.name, 'y:doc')
  assert.equal(doc.$type.check(doc), true)
  assert.equal(doc.$type.check(type), false)

  assert.equal(Y14.$nodeAny.check(type), true)
  assert.equal(Y14.$nodeAny.check(doc), false)
  assert.equal(Y14.$node({}).check(type), true)
  assert.equal(Y14.$doc, doc.$type)
  assert.equal(type.$type, Y14.$nodeAny)
  assert.equal(type.$type.name, 'y:node')
  const paragraph = new Y14.Node('p')
  assert.equal(Y14.$node({ name: 'p' }).check(paragraph), true)
  assert.equal(Y14.$node({ name: 'p' }).check(type), false)
  assert.equal(Y14.$node({}).check(paragraph), true)
  assert.equal(Y14.$node({ name: 'q' }).check(paragraph), false)

  assert.equal(Y14.$renderer.name, 'y:renderer')
  assert.equal(renderer.$type, Y14.$renderer)
  assert.equal(Y14.$renderer.check(renderer), true)
  assert.equal(Y14.$renderer.check(type), false)
})
