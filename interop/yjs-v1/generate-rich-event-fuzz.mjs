import fs from 'node:fs'

import * as Y from 'yjs'

import './assert-yjs-version.mjs'

const output = process.argv[2]
const seedCount = Number.parseInt(process.argv[3] ?? '200', 10)
if (output == null || !Number.isSafeInteger(seedCount) || seedCount < 1) {
  throw new Error('usage: node generate-rich-event-fuzz.mjs <output.tsv> [seed-count]')
}

const b64 = value => Buffer.from(value).toString('base64')
const text = value => b64(Buffer.from(value, 'utf8'))
const canonical = value => {
  if (value === undefined) return 'null'
  if (value instanceof Uint8Array) return canonical([...value])
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value != null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}
const deltaSignature = ops => ops.map(op => {
  const attrs = canonical(op.attributes ?? {})
  if ('insert' in op) return `I:${canonical(op.insert)}:${attrs}`
  if ('retain' in op) return `R:${op.retain}:${attrs}`
  return `D:${op.delete}`
}).join('|')

const rows = []
for (let seed = 1; seed <= seedCount; seed++) {
  const source = new Y.Doc({ gc: false })
  source.clientID = 500000 + seed
  const body = source.getText('body')
  const root = source.getMap('root')
  const section = new Y.Map()
  const list = new Y.Array()
  const note = new Y.Text()
  root.set('section', section)
  section.set('list', list)
  section.set('note', note)
  section.set('flag', seed % 2 === 0)
  list.push([seed, `base-${seed}`])
  note.insert(0, `note-${seed}`, { underline: seed % 3 === 0 })
  body.insert(0, `base-${seed}`, { bold: seed % 2 === 0 })
  body.insertEmbed(body.length, { image: `base-${seed}` }, { kind: 'image' })

  const base = Y.encodeStateAsUpdate(source)
  const before = Y.encodeStateVector(source)
  const baseSnapshot = Y.snapshot(source)

  const insertAt = seed % (body.length + 1)
  body.insert(insertAt, `x${seed % 10}`, { italic: seed % 2 !== 0 })
  body.format(0, Math.min(3, body.length), { color: `c${seed % 5}` })
  if (seed % 2 === 0 && body.length > 2) body.delete(1, 1)
  body.insertEmbed(body.length, { mention: seed }, { kind: 'mention' })
  section.set('flag', `round-${seed % 7}`)
  section.set('extra', seed * 2)
  list.insert(seed % (list.length + 1), [{ seed }, `tail-${seed % 11}`])
  if (seed % 4 === 0) list.delete(0, 1)
  note.insert(seed % (note.length + 1), `+${seed % 10}`, { italic: true })
  note.format(0, Math.min(2, note.length), { highlight: seed % 3 })
  const increment = Y.encodeStateAsUpdate(source, before)

  const target = new Y.Doc({ gc: false })
  const targetBody = target.getText('body')
  const targetRoot = target.getMap('root')
  Y.applyUpdate(target, base)
  const targetSection = targetRoot.get('section')
  const targetList = targetSection.get('list')
  const targetNote = targetSection.get('note')
  let bodyEventDelta = ''
  let bodyAdded = -1
  let bodyDeleted = -1
  let sectionChanges = ''
  const deepPaths = []
  targetBody.observe(event => {
    bodyEventDelta = deltaSignature(event.delta)
    bodyAdded = event.changes.added.size
    bodyDeleted = event.changes.deleted.size
  })
  targetSection.observe(event => {
    sectionChanges = [...event.changes.keys]
      .map(([key, change]) => `${key}:${change.action}:${canonical(change.oldValue)}`)
      .sort()
      .join('|')
  })
  targetRoot.observeDeep(events => {
    for (const event of events) deepPaths.push(canonical(event.path))
  })
  Y.applyUpdate(target, increment)

  const snapshotDoc = Y.createDocFromSnapshot(target, baseSnapshot)

  const gcDoc = new Y.Doc()
  gcDoc.clientID = 700000 + seed
  const gcMap = gcDoc.getMap('gc')
  gcMap.set('value', `old-${seed}`)
  gcMap.set('value', `new-${seed}`)
  gcMap.set('gone', seed)
  gcMap.delete('gone')

  rows.push([
    seed,
    b64(base),
    b64(increment),
    text(targetBody.toString()),
    text(deltaSignature(targetBody.toDelta())),
    text(bodyEventDelta),
    bodyAdded,
    bodyDeleted,
    text(sectionChanges),
    text(deepPaths.sort().join('|')),
    text(targetNote.toString()),
    text(deltaSignature(targetNote.toDelta())),
    text(canonical(targetList.toArray())),
    b64(Y.encodeSnapshot(baseSnapshot)),
    text(snapshotDoc.getText('body').toString()),
    b64(Y.encodeStateAsUpdate(gcDoc)),
    text(canonical(gcMap.toJSON())),
  ].join('\t'))
}

fs.writeFileSync(output, `${rows.join('\n')}\n`)
