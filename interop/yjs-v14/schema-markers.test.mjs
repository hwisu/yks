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

  assert.equal(Y14.$ytypeAny.check(type), true)
  assert.equal(Y14.$ytypeAny.check(doc), false)
  assert.equal(Y14.$ytype({}).check(type), true)

  assert.equal(Y14.$renderer.name, 'y:r')
  assert.equal(renderer.$type, Y14.$renderer)
  assert.equal(Y14.$renderer.check(renderer), true)
  assert.equal(Y14.$renderer.check(type), false)
})
