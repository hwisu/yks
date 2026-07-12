import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'

test('XML selectors and insertAfter use case-insensitive tag names and live identity', () => {
  const doc = new Y.Doc()
  const xml = doc.getXmlFragment('xml')
  const section = new Y.XmlElement('section')
  const paragraph = new Y.XmlElement('p')
  const emphasis = new Y.XmlElement('em')
  paragraph.push([emphasis])
  xml.push([section, paragraph])

  const aside = new Y.XmlElement('aside')
  xml.insertAfter(section, [aside])

  assert.deepEqual(xml.toArray(), [section, aside, paragraph])
  assert.strictEqual(xml.querySelector('EM'), emphasis)
  assert.deepEqual(xml.querySelectorAll('p'), [paragraph])
})

test('YText delta sanitizing and snapshot comparisons expose upstream shapes', () => {
  const doc = new Y.Doc({ gc: false })
  const text = doc.getText('body')
  text.applyDelta([{ insert: 'line\n' }], { sanitize: false })
  assert.equal(text.toString(), 'line')

  const before = Y.snapshot(doc)
  text.insert(text.length, '!')
  const after = Y.snapshot(doc)
  assert.deepEqual(text.toDelta(after, before), [
    { insert: 'line' },
    { insert: '!', attributes: { ychange: { type: 'added' } } },
  ])
})

test('computeYChange runs once for a packed ContentString and uses its first id', () => {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = 7
  const text = doc.getText('body')
  const before = Y.snapshot(doc)
  text.insert(0, 'abc')
  const after = Y.snapshot(doc)
  const calls = []

  const delta = text.toDelta(after, before, (change, id) => {
    calls.push([change, id.client, id.clock])
    return { type: change, clock: id.clock }
  })

  assert.deepEqual(calls, [['added', 7, 0]])
  assert.deepEqual(delta, [
    { insert: 'abc', attributes: { ychange: { type: 'added', clock: 0 } } },
  ])
})

test('YText format retains upstream lifecycle and overflow newline behavior', () => {
  const doc = new Y.Doc()
  const text = doc.getText('body')
  text.insert(0, 'abc')
  let beforeTransactions = 0
  let updates = 0
  doc.on('beforeTransaction', () => beforeTransactions++)
  doc.on('update', () => updates++)

  text.format(1, 4, { bold: true })
  assert.equal(text.toString(), 'abc\n\n')
  assert.deepEqual(text.toDelta(), [
    { insert: 'a' },
    { insert: 'bc\n\n', attributes: { bold: true } },
  ])
  text.format(99, 1, { code: true })
  text.format(0, 0, { code: true })
  assert.equal(beforeTransactions, 2)
  assert.equal(updates, 1)

  const negativeDoc = new Y.Doc()
  const negativeText = negativeDoc.getText('body')
  negativeText.insert(0, 'abc')
  let negativeUpdates = 0
  negativeDoc.on('update', () => negativeUpdates++)
  negativeText.format(0, -1, { italic: true })
  assert.deepEqual(negativeText.toDelta(), [{ insert: 'abc' }])
  assert.equal(negativeUpdates, 1)

  const emptyAttrsDoc = new Y.Doc()
  const emptyAttrsText = emptyAttrsDoc.getText('body')
  emptyAttrsText.insert(0, 'abc')
  emptyAttrsText.format(1, 4, {})
  assert.equal(emptyAttrsText.toString(), 'abc\n\n')
})

test('beforeObserverCalls mutations are included in the first event and deduplicated next', () => {
  const doc = new Y.Doc()
  const array = doc.getArray('items')
  const deltas = []
  const updates = []
  let mutateOnce = true
  doc.on('beforeObserverCalls', () => {
    if (mutateOnce) {
      mutateOnce = false
      array.push(['y'])
    }
  })
  array.observe(event => deltas.push(event.changes.delta))
  doc.on('update', update => updates.push(update))

  array.push(['x'])

  assert.deepEqual(deltas, [[{ insert: ['x', 'y'] }], []])
  assert.equal(updates.length, 2)
  const replay = new Y.Doc()
  Y.applyUpdate(replay, updates[0])
  assert.deepEqual(replay.getArray('items').toArray(), ['x', 'y'])
  Y.applyUpdate(replay, updates[1])
  assert.deepEqual(replay.getArray('items').toArray(), ['x', 'y'])
})

test('cleanup only deduplicates queued inserts that Item.mergeWith can actually merge', () => {
  const prependDoc = new Y.Doc({ gc: false })
  const prepend = prependDoc.getArray('items')
  const prependDeltas = []
  let prependOnce = true
  prependDoc.on('beforeObserverCalls', () => {
    if (prependOnce) {
      prependOnce = false
      prepend.insert(0, ['y'])
    }
  })
  prepend.observe(event => prependDeltas.push(event.changes.delta))
  prepend.push(['x'])
  assert.deepEqual(prependDeltas, [
    [{ insert: ['y', 'x'] }],
    [{ insert: ['y'] }],
  ])

  const middleDoc = new Y.Doc({ gc: false })
  const middle = middleDoc.getArray('items')
  middle.push(['a', 'b'])
  const middleDeltas = []
  let middleOnce = true
  middleDoc.on('beforeObserverCalls', () => {
    if (middleOnce) {
      middleOnce = false
      middle.insert(1, ['y'])
    }
  })
  middle.observe(event => middleDeltas.push(event.changes.delta))
  middle.push(['x'])
  assert.deepEqual(middleDeltas, [
    [{ retain: 1 }, { insert: ['y'] }, { retain: 1 }, { insert: ['x'] }],
    [{ retain: 1 }, { insert: ['y'] }],
  ])

  const mixedDoc = new Y.Doc({ gc: false })
  const mixed = mixedDoc.getArray('items')
  const mixedDeltas = []
  let mixedOnce = true
  mixedDoc.on('beforeObserverCalls', () => {
    if (mixedOnce) {
      mixedOnce = false
      mixed.push(['y', new Uint8Array([1]), 'z'])
    }
  })
  mixed.observe(event => mixedDeltas.push(event.changes.delta.map(op => (
    op.insert
      ? { ...op, insert: op.insert.map(value => value instanceof Uint8Array ? 'BINARY' : value) }
      : op
  ))))
  mixed.push(['x'])
  assert.deepEqual(mixedDeltas, [
    [{ insert: ['x', 'y', 'BINARY', 'z'] }],
    [{ retain: 2 }, { insert: ['BINARY', 'z'] }],
  ])

  const typeDoc = new Y.Doc({ gc: false })
  const withType = typeDoc.getArray('items')
  const typeDeltas = []
  let typeOnce = true
  typeDoc.on('beforeObserverCalls', () => {
    if (typeOnce) {
      typeOnce = false
      withType.push([new Y.Array()])
    }
  })
  withType.observe(event => typeDeltas.push(event.changes.delta.map(op => (
    op.insert ? { ...op, insert: op.insert.map(value => value instanceof Y.Array ? 'TYPE' : value) } : op
  ))))
  withType.push(['x'])
  assert.deepEqual(typeDeltas, [
    [{ insert: ['x', 'TYPE'] }],
    [{ retain: 1 }, { insert: ['TYPE'] }],
  ])
})

test('cleanup merge representatives apply to queued deletes too', () => {
  const capture = values => {
    const doc = new Y.Doc({ gc: false })
    const array = doc.getArray('items')
    array.push(values)
    const deltas = []
    let deleteOnce = true
    doc.on('beforeObserverCalls', () => {
      if (deleteOnce) {
        deleteOnce = false
        array.delete(0, 1)
      }
    })
    array.observe(event => deltas.push(event.changes.delta))
    array.delete(0, 1)
    return deltas
  }

  assert.deepEqual(capture(['a', 'b']), [[{ delete: 1 }], []])
  assert.deepEqual(
    capture([new Uint8Array([1]), new Uint8Array([2])]),
    [[{ delete: 1 }], [{ delete: 1 }]],
  )
})

test('delete split candidates only merge their reachable sequence and honor the new-struct scan bound', () => {
  const sameDoc = new Y.Doc({ gc: false })
  const same = sameDoc.getArray('same')
  same.push(['a', 'b'])
  const sameDeltas = []
  let appendOnce = true
  sameDoc.on('beforeObserverCalls', () => {
    if (appendOnce) {
      appendOnce = false
      same.push(['y'])
    }
  })
  same.observe(event => sameDeltas.push(event.changes.delta))
  same.delete(0, 1)
  assert.deepEqual(sameDeltas, [
    [{ delete: 1 }, { retain: 1 }, { insert: ['y'] }],
    [],
  ])

  const crossDoc = new Y.Doc({ gc: false })
  const deletedFrom = crossDoc.getArray('deletedFrom')
  const appendedTo = crossDoc.getArray('appendedTo')
  deletedFrom.push(['a', 'b'])
  appendedTo.push(['x'])
  const crossDeltas = []
  let crossOnce = true
  crossDoc.on('beforeObserverCalls', () => {
    if (crossOnce) {
      crossOnce = false
      appendedTo.push(['y'])
    }
  })
  appendedTo.observe(event => crossDeltas.push(event.changes.delta))
  deletedFrom.delete(0, 1)
  assert.deepEqual(crossDeltas, [[{ retain: 1 }, { insert: ['y'] }]])

  const boundedDoc = new Y.Doc({ gc: false })
  const old = boundedDoc.getArray('old')
  const trigger = boundedDoc.getArray('trigger')
  old.push(['a', 'b'])
  old.delete(0, 1)
  const oldDeltas = []
  let deleteOnce = true
  boundedDoc.on('beforeObserverCalls', () => {
    if (deleteOnce) {
      deleteOnce = false
      old.delete(0, 1)
    }
  })
  old.observe(event => oldDeltas.push(event.changes.delta))
  trigger.push(['x'])
  assert.deepEqual(oldDeltas, [[{ delete: 1 }]])
})

test('a beforeObserverCalls failure skips observers but still cleans up updates and subdocs', () => {
  const doc = new Y.Doc({ gc: false })
  const array = doc.getArray('items')
  const seen = []
  let update
  doc.on('beforeObserverCalls', () => {
    seen.push('before')
    throw new Error('before failure')
  })
  array.observe(() => seen.push('type'))
  doc.on('afterTransaction', () => seen.push('afterTransaction'))
  doc.on('afterTransactionCleanup', () => seen.push('cleanup'))
  doc.on('update', value => {
    seen.push('update')
    update = value
  })
  doc.on('subdocs', () => seen.push('subdocs'))

  assert.throws(() => array.push([new Y.Doc({ guid: 'nested' })]), /before failure/)

  assert.deepEqual(seen, ['before', 'cleanup', 'update', 'subdocs'])
  assert.deepEqual([...doc.getSubdocs()].map(subdoc => subdoc.guid), ['nested'])
  const replay = new Y.Doc()
  Y.applyUpdate(replay, update)
  assert.deepEqual([...replay.getSubdocs()].map(subdoc => subdoc.guid), ['nested'])
})

test('beforeObserverCalls keeps unrelated type inserts for their own queued event', () => {
  const doc = new Y.Doc()
  const first = doc.getArray('first')
  const second = doc.getArray('second')
  const firstDeltas = []
  const secondDeltas = []
  let mutateOnce = true
  doc.on('beforeObserverCalls', () => {
    if (mutateOnce) {
      mutateOnce = false
      second.push(['y'])
    }
  })
  first.observe(event => firstDeltas.push(event.changes.delta))
  second.observe(event => secondDeltas.push(event.changes.delta))

  first.push(['x'])

  assert.deepEqual(firstDeltas, [[{ insert: ['x'] }]])
  assert.deepEqual(secondDeltas, [[{ insert: ['y'] }]])
})

test('an empty outer transaction does not duplicate a nested observer update', () => {
  const doc = new Y.Doc()
  const array = doc.getArray('items')
  const updates = []
  let mutateOnce = true
  doc.on('beforeObserverCalls', () => {
    if (mutateOnce) {
      mutateOnce = false
      array.push(['nested'])
    }
  })
  doc.on('update', update => updates.push(update))

  doc.transact(() => {})

  assert.equal(updates.length, 1)
  const replay = new Y.Doc()
  Y.applyUpdate(replay, updates[0])
  assert.deepEqual(replay.getArray('items').toArray(), ['nested'])
})

test('adjacent same-format inserts merge into one observer insert op', () => {
  const doc = new Y.Doc()
  const text = doc.getText('body')
  const bold = { bold: true }
  text.insert(0, 'a', bold)
  let delta
  text.observe(event => { delta = event.delta })

  doc.transact(() => {
    text.insert(text.length, 'x', bold)
    text.insert(text.length, 'y', bold)
  })

  assert.deepEqual(delta, [
    { retain: 1 },
    { insert: 'xy', attributes: bold },
  ])
})

test('array and XML delete overflow commits available deletion before throwing', () => {
  for (const type of ['array', 'xml']) {
    const doc = new Y.Doc()
    const shared = type === 'array' ? doc.getArray('root') : doc.getXmlFragment('root')
    if (type === 'array') shared.push(['value'])
    else shared.push([new Y.XmlText('value')])

    assert.throws(() => shared.delete(0, 99), /Length exceeded/)
    assert.equal(shared.length, 0)
  }
})

test('replayed full delete sets report only the newly deleted text span', () => {
  const source = new Y.Doc({ gc: false })
  source.clientID = 1
  const sourceText = source.getText('body')
  sourceText.insert(0, 'abc')
  const target = new Y.Doc({ gc: false })
  Y.applyUpdate(target, Y.encodeStateAsUpdate(source))
  const deltas = []
  target.getText('body').observe(event => deltas.push(event.delta))

  sourceText.delete(1, 1)
  Y.applyUpdate(target, Y.encodeStateAsUpdate(source))
  sourceText.delete(1, 1)
  Y.applyUpdate(target, Y.encodeStateAsUpdate(source))

  assert.deepEqual(deltas, [
    [{ retain: 1 }, { delete: 1 }],
    [{ retain: 1 }, { delete: 1 }],
  ])
})
