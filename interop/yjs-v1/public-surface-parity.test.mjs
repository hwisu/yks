import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'

test('Doc.toJSON skips remote roots until a concrete getter opens them', () => {
  const source = new Y.Doc()
  source.getArray('items').push(['remote'])
  source.getMap('meta').set('count', 1)

  const target = new Y.Doc()
  Y.applyUpdate(target, Y.encodeStateAsUpdate(source))

  assert.deepEqual(JSON.parse(JSON.stringify(target.toJSON())), {})
  assert.deepEqual([...target.share].map(([key, value]) => [key, value.constructor.name]), [
    ['items', 'AbstractType'],
    ['meta', 'AbstractType'],
  ])
  assert.deepEqual(JSON.parse(JSON.stringify(target.toJSON())), {})
  assert.deepEqual(target.getArray('items').toJSON(), ['remote'])
  assert.deepEqual(JSON.parse(JSON.stringify(target.toJSON())), { items: ['remote'] })
  assert.deepEqual(target.getMap('meta').toJSON(), { count: 1 })
  assert.deepEqual(target.toJSON(), { items: ['remote'], meta: { count: 1 } })
})

test('Doc.toJSON follows JavaScript numeric property ordering', () => {
  const doc = new Y.Doc()
  doc.getArray('10')
  doc.getArray('2')
  doc.getArray('01')
  doc.getArray('root')

  assert.deepEqual(Object.keys(doc.toJSON()), ['2', '10', '01', 'root'])
})

test('XmlText snapshot serialization retains formatting and embeds', () => {
  const doc = new Y.Doc({ gc: false })
  const fragment = doc.getXmlFragment('xml')
  const text = new Y.XmlText()
  text.insert(0, 'A', { em: {} })
  text.insertEmbed(1, { image: 'old' }, { strong: {} })
  text.insertEmbed(2, ['x', null, 2], { code: {} })
  fragment.push([text])
  const before = Y.snapshot(doc)

  text.delete(0, text.length)
  text.insert(0, 'new')

  const snapshotDoc = Y.createDocFromSnapshot(doc, before)
  const snapshotFragment = snapshotDoc.getXmlFragment('xml')
  const expected = '<em>A</em><strong>[object Object]</strong><code>x,,2</code>'
  assert.equal(snapshotFragment.toString(), expected)
  assert.equal(snapshotFragment.toArray().join(''), expected)

  const target = new Y.Doc()
  target.getXmlFragment('xml').push([snapshotFragment.toArray()[0].clone()])
  assert.equal(target.getXmlFragment('xml').toString(), expected)
})

test('Yjs direct and deep observers expose concrete event classes and event arrays', () => {
  const doc = new Y.Doc()
  const root = doc.getMap('root')
  const nested = new Y.Array()
  root.set('nested', nested)

  let directEvent
  let directChanges
  nested.observe(event => {
    directEvent = event
    directChanges = event.changes
  })
  let deepEvents
  let deepTransaction
  root.observeDeep((events, transaction) => {
    deepEvents = events
    deepTransaction = transaction
  })

  doc.transact(() => nested.push(['value']), 'oracle-origin')

  assert.ok(directEvent instanceof Y.YArrayEvent)
  assert.deepEqual(Object.keys(directChanges), ['added', 'deleted', 'delta', 'keys'])
  assert.ok(directChanges.added instanceof Set)
  assert.ok(directChanges.deleted instanceof Set)
  assert.ok(Array.isArray(directChanges.delta))
  assert.ok(directChanges.keys instanceof Map)
  assert.ok(Array.isArray(deepEvents))
  assert.equal(deepEvents.length, 1)
  assert.ok(deepEvents[0] instanceof Y.YArrayEvent)
  assert.deepEqual(deepEvents[0].path, ['nested'])
  assert.equal(deepTransaction.origin, 'oracle-origin')
})
