import fs from 'node:fs'

import * as Y from 'yjs'

const output = process.argv[2]
const seedCount = Number(process.argv[3] ?? 100)
if (output == null || !Number.isSafeInteger(seedCount) || seedCount <= 0) {
  throw new Error('usage: node generate-advanced-differential-fuzz.mjs <output.tsv> [positive-seed-count]')
}

const bytes = value => Buffer.from(value).toString('base64')
const text = value => Buffer.from(value, 'utf8').toString('base64')

const shuffled = (values, random) => {
  const result = [...values]
  for (let index = result.length - 1; index > 0; index--) {
    const other = Math.floor(random() * (index + 1))
    ;[result[index], result[other]] = [result[other], result[index]]
  }
  return result
}

const createXmlCase = (seed, random) => {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = 1000 + seed
  const fragment = doc.getXmlFragment('xml')
  const paragraph = new Y.XmlElement('p')
  paragraph.setAttribute('seed', `${seed}`)
  const content = new Y.XmlText()
  content.insert(0, `s${seed}`)
  paragraph.insert(0, [content])
  fragment.insert(0, [paragraph])
  const base = Y.encodeStateAsUpdate(doc)
  const before = Y.encodeStateVector(doc)

  content.insert(Math.floor(random() * (content.length + 1)), `x${seed % 10}`)
  content.format(0, Math.min(2, content.length), { bold: seed % 2 === 0 })
  paragraph.setAttribute('round', `${seed % 7}`)
  if (seed % 3 === 0) {
    const sibling = new Y.XmlElement('span')
    const siblingText = new Y.XmlText()
    siblingText.insert(0, `n${seed}`)
    sibling.insert(0, [siblingText])
    fragment.insert(fragment.length, [sibling])
  }

  return {
    updates: shuffled([base, Y.encodeStateAsUpdate(doc, before)], random).map(bytes).join(','),
    expected: text(fragment.toString()),
  }
}

const createSubdocCase = (seed, random) => {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = 100000 + seed
  const map = doc.getMap('subdocs')
  const array = doc.getArray('subdocArray')
  map.set('primary', new Y.Doc({
    guid: `primary-${seed}`,
    gc: false,
    shouldLoad: seed % 2 === 0,
    autoLoad: seed % 3 === 0,
    meta: { seed },
  }))
  const base = Y.encodeStateAsUpdate(doc)
  const before = Y.encodeStateVector(doc)

  array.push([new Y.Doc({
    guid: `array-${seed}`,
    gc: seed % 2 !== 0,
    shouldLoad: true,
    meta: { role: 'array' },
  })])
  if (seed % 4 === 0) map.delete('primary')
  if (seed % 5 === 0) {
    map.set('primary', new Y.Doc({ guid: `replacement-${seed}`, gc: false }))
  }

  const primary = map.get('primary')
  const arrayGuids = array.toArray().map(child => child.guid)
  const allGuids = [...doc.getSubdocs()].map(child => child.guid).sort()
  return {
    updates: shuffled([base, Y.encodeStateAsUpdate(doc, before)], random).map(bytes).join(','),
    expected: `${primary?.guid ?? '-'}|${arrayGuids.join(',')}|${allGuids.join(',')}`,
  }
}

const createRelativePositionCase = (seed, random) => {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = 200000 + seed
  const sharedText = doc.getText('relative')
  sharedText.insert(0, `anchor-${seed}`)
  const index = Math.floor(random() * (sharedText.length + 1))
  const assoc = random() < 0.5 ? -1 : 0
  const position = Y.createRelativePositionFromTypeIndex(sharedText, index, assoc)

  sharedText.insert(Math.floor(random() * (sharedText.length + 1)), `i${seed % 10}`)
  if (sharedText.length > 2 && seed % 2 === 0) {
    sharedText.delete(Math.floor(random() * (sharedText.length - 1)), 1)
  }
  const absolute = Y.createAbsolutePositionFromRelativePosition(position, doc)
  if (absolute == null) throw new Error(`relative position did not resolve for seed ${seed}`)

  return {
    update: bytes(Y.encodeStateAsUpdate(doc)),
    position: bytes(Y.encodeRelativePosition(position)),
    index: absolute.index,
    assoc: absolute.assoc,
  }
}

const createV2Case = (seed, random) => {
  const left = new Y.Doc({ gc: false })
  left.clientID = 300000 + seed * 2
  const leftText = left.getText('body')
  leftText.insert(0, `v${seed}`)
  const base = Y.encodeStateAsUpdateV2(left)
  const baseline = Y.encodeStateVector(left)

  const right = new Y.Doc({ gc: false })
  right.clientID = left.clientID + 1
  Y.applyUpdateV2(right, base)
  const rightText = right.getText('body')

  leftText.insert(Math.floor(random() * (leftText.length + 1)), `L${seed % 10}`)
  rightText.insert(Math.floor(random() * (rightText.length + 1)), `R${seed % 10}`)
  const increments = [
    Y.encodeStateAsUpdateV2(left, baseline),
    Y.encodeStateAsUpdateV2(right, baseline),
  ]
  const expected = new Y.Doc({ gc: false })
  for (const update of shuffled([base, ...increments], random)) Y.applyUpdateV2(expected, update)

  return {
    base: bytes(base),
    increments: shuffled(increments, random).map(bytes).join(','),
    expected: text(expected.getText('body').toString()),
  }
}

const createUndoCase = (seed, random) => {
  const doc = new Y.Doc({ gc: false })
  doc.clientID = 400000 + seed
  const sharedText = doc.getText('undo')
  const undoManager = new Y.UndoManager(sharedText, { captureTimeout: 0 })
  const operations = []

  for (let round = 0; round < 12; round++) {
    const insert = sharedText.length === 0 || random() < 0.65
    if (insert) {
      const index = Math.floor(random() * (sharedText.length + 1))
      const value = `${String.fromCharCode(65 + (seed + round) % 26)}${round}`
      sharedText.insert(index, value)
      operations.push(`I,${index},${value}`)
    } else {
      const index = Math.floor(random() * sharedText.length)
      const length = Math.min(1 + Math.floor(random() * 2), sharedText.length - index)
      sharedText.delete(index, length)
      operations.push(`D,${index},${length}`)
    }
  }

  const beforeUndo = sharedText.toString()
  while (undoManager.canUndo()) undoManager.undo()
  const afterUndo = sharedText.toString()
  while (undoManager.canRedo()) undoManager.redo()
  const afterRedo = sharedText.toString()
  undoManager.destroy()

  return {
    operations: operations.join(';'),
    beforeUndo: text(beforeUndo),
    afterUndo: text(afterUndo),
    afterRedo: text(afterRedo),
  }
}

const lines = []
for (let seed = 1; seed <= seedCount; seed++) {
  let state = seed >>> 0
  const random = () => ((state = (Math.imul(state, 1664525) + 1013904223) >>> 0) / 2 ** 32)
  const xml = createXmlCase(seed, random)
  const subdoc = createSubdocCase(seed, random)
  const relative = createRelativePositionCase(seed, random)
  const v2 = createV2Case(seed, random)
  const undo = createUndoCase(seed, random)
  lines.push([
    seed,
    xml.updates,
    xml.expected,
    subdoc.updates,
    subdoc.expected,
    relative.update,
    relative.position,
    relative.index,
    relative.assoc,
    v2.base,
    v2.increments,
    v2.expected,
    undo.operations,
    undo.beforeUndo,
    undo.afterUndo,
    undo.afterRedo,
  ].join('\t'))
}

fs.writeFileSync(output, lines.join('\n'))
