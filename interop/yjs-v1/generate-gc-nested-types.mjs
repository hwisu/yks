import fs from 'node:fs'

import * as Y from 'yjs'

import './assert-yjs-version.mjs'

// Each row gives the updates a peer receives and the state upstream Yjs then encodes with
// garbage collection on: name, updates in delivery order (comma separated), expected V1 state.
const output = process.argv[2]
if (!output) throw new Error('usage: node generate-gc-nested-types.mjs <output.tsv>')

const shapes = {
  'map-values': root => {
    const nested = new Y.Map()
    root.set('k', nested)
    nested.set('a', 1)
    nested.set('b', 2)
    nested.set('c', 3)
  },
  'map-overwrites': root => {
    const nested = new Y.Map()
    root.set('k', nested)
    nested.set('a', 1)
    nested.set('a', 2)
    nested.set('a', 3)
  },
  array: root => {
    const nested = new Y.Array()
    root.set('k', nested)
    nested.push([1, 2, 3])
    nested.push(['x'])
  },
  'mixed-siblings': root => {
    const nested = new Y.Array()
    root.set('k', nested)
    nested.push([1])
    nested.push([new Y.Map()])
    nested.push(['x', new Uint8Array([1, 2])])
  },
  text: root => {
    const nested = new Y.Text()
    root.set('k', nested)
    nested.insert(0, 'abc')
    nested.format(1, 1, { bold: true })
    nested.insert(3, 'def')
  },
  xml: root => {
    const nested = new Y.XmlElement('p')
    root.set('k', nested)
    nested.setAttribute('a', 'b')
    nested.insert(0, [new Y.XmlText('hi'), new Y.XmlElement('br')])
  },
  'three-levels': root => {
    const first = new Y.Map()
    root.set('k', first)
    const second = new Y.Map()
    first.set('k', second)
    const third = new Y.Map()
    second.set('k', third)
    third.set('leaf', 1)
  },
  'sibling-kept': root => {
    const nested = new Y.Map()
    root.set('k', nested)
    nested.set('a', 1)
    root.set('keep', 'v')
  },
}

const base64 = bytes => Buffer.from(bytes).toString('base64')
const expected = updates => {
  const doc = new Y.Doc()
  for (const update of updates) Y.applyUpdate(doc, update)
  return Y.encodeStateAsUpdate(doc)
}
const rows = []
const add = (name, updates) => rows.push([name, updates.map(base64).join(','), base64(expected(updates))].join('\t'))

for (const [name, build] of Object.entries(shapes)) {
  const source = new Y.Doc({ gc: false })
  source.clientID = 1
  const root = source.getMap('m')
  source.transact(() => build(root))
  const create = Y.encodeStateAsUpdate(source)
  const created = Y.encodeStateVector(source)
  root.delete('k')
  add(name, [create, Y.encodeStateAsUpdate(source, created)])
}

// A second client writes into the nested type before it is deleted, and once more without
// having seen the deletion; that last update reaches the peer after collection.
{
  const first = new Y.Doc({ gc: false })
  first.clientID = 1
  const second = new Y.Doc({ gc: false })
  second.clientID = 2
  const nested = new Y.Map()
  first.getMap('m').set('k', nested)
  nested.set('a', 1)
  Y.applyUpdate(second, Y.encodeStateAsUpdate(first))
  second.getMap('m').get('k').set('b', 2)
  Y.applyUpdate(first, Y.encodeStateAsUpdate(second))
  const create = Y.encodeStateAsUpdate(first)
  const created = Y.encodeStateVector(first)
  first.getMap('m').delete('k')
  const remove = Y.encodeStateAsUpdate(first, created)
  add('two-clients', [create, remove])
  const seen = Y.encodeStateVector(second)
  second.getMap('m').get('k').set('late', 3)
  add('late-child', [create, remove, Y.encodeStateAsUpdate(second, seen)])
}

fs.writeFileSync(output, `${rows.join('\n')}\n`)
