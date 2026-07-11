import fs from 'node:fs'
import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'

import { fixtureDirectory } from './fixture-helpers.mjs'
import { materializeScenario } from './scenarios.mjs'

const fixture = name =>
  fs.readFileSync(`${fixtureDirectory}/${name}.bin`)

const apply = (names, scenario) => {
  const doc = new Y.Doc({ gc: false })
  for (const name of names) {
    Y.applyUpdate(doc, fixture(name))
  }
  materializeScenario(doc, scenario)
  return doc
}

test('packed array decoder fixture contains binary content', () => {
  const doc = apply(['array-v1'], 'array')
  const values = doc.getArray('items').toArray()

  assert.deepEqual(values.slice(0, 4), ['a', 42, true, null])
  assert.deepEqual(values[4], new Uint8Array([1, 2]))
})

for (const names of [
  ['array-base-v1', 'array-append-v1'],
  ['array-append-v1', 'array-base-v1'],
]) {
  test(`array fixtures converge in order ${names.join(', ')}`, () => {
    assert.deepEqual(apply(names, 'array').getArray('numbers').toArray(), [1, 2, 3, 4])
  })
}

test('a child arriving after its parent was deleted is deleted too', () => {
  const doc = new Y.Doc({ gc: false })
  for (const name of ['nested-owner-v1', 'nested-owner-delete-v1', 'nested-child-v1']) {
    Y.applyUpdate(doc, fixture(name))
  }

  assert.deepEqual(doc.getMap('root').toJSON(), {})
  assert.equal(doc.store.clients.get(2)[0].deleted, true)
  assert.deepEqual(Array.from(Y.decodeStateVector(Y.encodeStateVector(doc))), [[2, 1], [1, 1]])
})

test('GC struct fixture preserves clocks without exposing deleted nested content', () => {
  const doc = new Y.Doc()
  Y.applyUpdate(doc, fixture('gc-nested-delete-v1'))
  assert.deepEqual(doc.getArray('gc-root').toJSON(), [])
  assert.deepEqual(Array.from(Y.decodeStateVector(Y.encodeStateVector(doc))), [[1, 2]])
})

test('Skip leaves the following item pending behind the unavailable clock range', () => {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, fixture('skip-then-text-v1'))

  assert.equal(doc.getText('body').toString(), '')
  assert.deepEqual(Array.from(Y.decodeStateVector(Y.encodeStateVector(doc))), [])
  assert.equal(doc.store.clients.has(1), false)
  assert.ok(doc.store.pendingStructs)
  assert.deepEqual(Array.from(doc.store.pendingStructs.missing), [[1, 1]])
})

test('GC owns its clock range so the following item integrates', () => {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, fixture('gc-then-text-v1'))

  assert.equal(doc.getText('body').toString(), 'x')
  assert.deepEqual(Array.from(Y.decodeStateVector(Y.encodeStateVector(doc))), [[1, 3]])
  assert.deepEqual(
    doc.store.clients.get(1).map(struct => [struct.constructor.name, struct.id.clock, struct.length]),
    [['GC', 0, 2], ['Item', 2, 1]],
  )
})

test('deleted text content does not hide the replacement text kind', () => {
  const doc = new Y.Doc()
  Y.applyUpdate(doc, fixture('text-replace-full-v1'))

  assert.equal(doc.getText('body').toString(), 'new')
  assert.deepEqual(Array.from(Y.decodeStateVector(Y.encodeStateVector(doc))), [[1, 6]])
})

test('content targeting a GC parent becomes GC instead of a synthetic root', () => {
  const doc = new Y.Doc({ gc: false })
  Y.applyUpdate(doc, fixture('gc-then-text-v1'))
  Y.applyUpdate(doc, fixture('nested-child-v1'))

  assert.deepEqual([...doc.share.keys()], ['body'])
  assert.deepEqual(Array.from(Y.decodeStateVector(Y.encodeStateVector(doc))), [[2, 1], [1, 3]])
  assert.deepEqual(
    doc.store.clients.get(2).map(struct => struct.constructor.name),
    ['GC'],
  )
})

for (const names of [
  ['array-interior-base-v1', 'array-interior-insert-v1'],
  ['array-interior-insert-v1', 'array-interior-base-v1'],
]) {
  test(`interior-anchor fixtures converge in order ${names.join(', ')}`, () => {
    const doc = new Y.Doc({ gc: false })
    for (const name of names) Y.applyUpdate(doc, fixture(name))
    assert.deepEqual(doc.getArray('letters').toArray(), ['a', 'b', 'X', 'c'])
  })
}

for (const names of [
  ['map-base-v1', 'map-replace-v1'],
  ['map-replace-v1', 'map-base-v1'],
]) {
  test(`map fixtures converge in order ${names.join(', ')}`, () => {
    assert.deepEqual(apply(names, 'map').getMap('meta').toJSON(), { title: 'new' })
  })
}

for (const names of [
  ['map-first-key-v1', 'map-second-key-v1'],
  ['map-second-key-v1', 'map-first-key-v1'],
]) {
  test(`independent map-key fixtures converge in order ${names.join(', ')}`, () => {
    assert.deepEqual(apply(names, 'map').getMap('meta').toJSON(), { first: 1, second: 2 })
  })
}

test('full map fixture includes the replacement and delete set', () => {
  assert.deepEqual(apply(['map-full-v1'], 'map').getMap('meta').toJSON(), { title: 'new' })
})

test('garbage-collected full map fixture retains the replacement', () => {
  assert.deepEqual(apply(['map-full-gc-v1'], 'map').getMap('meta').toJSON(), { title: 'new' })
})

test('full map fixture applies replacements and explicit deletes', () => {
  assert.deepEqual(apply(['map-delete-full-v1'], 'map').getMap('meta').toJSON(), { title: 'new' })
})

for (const names of [
  ['array-front-base-v1', 'array-front-insert-v1'],
  ['array-front-insert-v1', 'array-front-base-v1'],
]) {
  test(`right-origin fixtures converge in order ${names.join(', ')}`, () => {
    const doc = new Y.Doc({ gc: false })
    for (const name of names) Y.applyUpdate(doc, fixture(name))
    assert.deepEqual(doc.getArray('letters').toArray(), ['x', 'a', 'b'])
  })
}

for (const names of [
  ['nested-owner-v1', 'nested-child-v1'],
  ['nested-child-v1', 'nested-owner-v1'],
]) {
  test(`cross-client nested fixtures converge in order ${names.join(', ')}`, () => {
    const doc = new Y.Doc({ gc: false })
    for (const name of names) Y.applyUpdate(doc, fixture(name))
    const profile = doc.getMap('root').get('profile')
    assert.ok(profile instanceof Y.Map)
    assert.deepEqual(profile.toJSON(), { name: 'Ada' })
  })
}

for (const names of [
  ['nested-map-base-v1', 'nested-map-city-v1'],
  ['nested-map-city-v1', 'nested-map-base-v1'],
]) {
  test(`nested fixtures converge in order ${names.join(', ')}`, () => {
    const profile = apply(names, 'nested-map-update').getMap('root').get('profile')
    assert.ok(profile instanceof Y.Map)
    assert.deepEqual(profile.toJSON(), { name: 'Ada', city: 'Seoul' })
  })
}
