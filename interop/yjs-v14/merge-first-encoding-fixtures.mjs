import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import * as Y from 'yjs14'
import './assert-yjs-version.mjs'

const directory = process.argv[2]
const verify = process.argv[3] === 'verify'
const rows = []
const describe = structs => structs.filter(s => !(s instanceof Y.Skip)).flatMap(s =>
  Array.from({ length: s.length }, (_, i) => [
    s.id.client, s.id.clock + i,
    s instanceof Y.Item ? s.content.constructor.name : 'GC',
    s instanceof Y.Item ? s.content.getContent()[i] ?? null : null,
  ]),
)

for (const [format, encode, apply, merge, intersect, decode] of [
  ['v1', Y.encodeStateAsUpdate, Y.applyUpdate, Y.mergeUpdates, Y.intersectUpdateWithContentIds, Y.decodeUpdate],
  ['v2', Y.encodeStateAsUpdateV2, Y.applyUpdateV2, Y.mergeUpdatesV2, Y.intersectUpdateWithContentIdsV2, Y.decodeUpdateV2],
]) {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = 41
  doc.get('body').insert(0, 'abcde')
  doc.get('body').delete(0, 5)
  const content = encode(doc)
  const gc = new Y.Doc()
  apply(gc, content)
  const stub = encode(gc)
  const part = (update, clock, len) => {
    const inserts = Y.createIdSet()
    inserts.add(41, clock, len)
    return intersect(update, { inserts, deletes: Y.createIdSet() })
  }
  const cases = [
    [content, stub], [stub, content],
    [part(content, 1, 1), stub], [stub, part(content, 1, 1)],
    [part(stub, 1, 1), part(stub, 3, 1), content],
    [part(stub, 3, 1), part(stub, 1, 1), content],
    [part(content, 1, 3), stub], [part(stub, 1, 3), content],
    [part(content, 0, 1), part(content, 4, 1), stub],
    [part(stub, 0, 1), part(stub, 4, 1), content],
  ]
  // Fixed-seed subsets cover sparse gaps, repeated merges, and both input orders.
  let seed = 20261023
  const random = () => (seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0)
  for (let i = 0; i < 30; i++) {
    const updates = Array.from({ length: 6 }, () => {
      const clock = random() % 5
      return part(random() % 2 ? content : stub, clock, 1 + random() % (5 - clock))
    })
    cases.push([...updates, content, stub])
    cases.push([merge(updates.slice(0, 3)), merge(updates.slice(3)), content, stub])
  }
  for (const [index, updates] of cases.entries()) {
    if (verify) {
      const actual = decode(fs.readFileSync(path.join(directory, `${format}-${index}.bin`)))
      const expected = decode(merge(updates))
      assert.deepEqual(describe(actual.structs), describe(expected.structs), `${format} case ${index}`)
      assert.deepEqual(actual.ds, expected.ds)
    } else {
      rows.push([format, index, ...updates.map(u => Buffer.from(u).toString('base64'))].join('\t'))
    }
  }
  doc.destroy()
  gc.destroy()
}
if (!verify) fs.writeFileSync(path.join(directory, 'inputs.tsv'), rows.join('\n'))
