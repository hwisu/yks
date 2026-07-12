import fs from 'node:fs'

import * as Y from 'yjs'

const output = process.argv[2]
const seedCount = Number(process.argv[3] ?? 500)
if (output == null || !Number.isSafeInteger(seedCount) || seedCount <= 0) {
  throw new Error('usage: node generate-differential-fuzz.mjs <output.tsv> [positive-seed-count]')
}

const shuffled = (values, random) => {
  const result = [...values]
  for (let index = result.length - 1; index > 0; index--) {
    const other = Math.floor(random() * (index + 1))
    ;[result[index], result[other]] = [result[other], result[index]]
  }
  return result
}

const lines = []
for (let seed = 1; seed <= seedCount; seed++) {
  let state = seed >>> 0
  const random = () => ((state = (Math.imul(state, 1664525) + 1013904223) >>> 0) / 2 ** 32)
  const docs = [10, 20, 30].map(clientID => {
    const doc = new Y.Doc()
    doc.clientID = clientID
    doc.getArray('a')
    doc.getText('t')
    doc.getMap('m')
    return doc
  })
  const updates = []

  for (let round = 0; round < 8; round++) {
    const roundUpdates = []
    for (let client = 0; client < docs.length; client++) {
      const doc = docs[client]
      const before = Y.encodeStateVector(doc)
      const operation = Math.floor(random() * 7)
      const token = String.fromCharCode(65 + client) + round
      const array = doc.getArray('a')
      const text = doc.getText('t')
      const map = doc.getMap('m')

      if (operation === 0 || operation === 1) {
        array.insert(Math.floor(random() * (array.length + 1)), [token])
      } else if (operation === 2) {
        if (array.length > 0) array.delete(Math.floor(random() * array.length), 1)
        else array.push([token])
      } else if (operation === 3) {
        text.insert(Math.floor(random() * (text.length + 1)), token)
      } else if (operation === 4) {
        if (text.length > 0) text.delete(Math.floor(random() * text.length), 1)
        else text.insert(0, token)
      } else if (operation === 5) {
        map.set(`k${Math.floor(random() * 3)}`, token)
      } else {
        map.delete(`k${Math.floor(random() * 3)}`)
      }
      roundUpdates.push(Y.encodeStateAsUpdate(doc, before))
    }

    for (const doc of docs) {
      for (const update of shuffled(roundUpdates, random)) Y.applyUpdate(doc, update)
    }
    updates.push(...roundUpdates)
  }

  const expected = docs[0]
  const array = `[${expected.getArray('a').toArray().join(', ')}]`
  const map = `{${Object.entries(expected.getMap('m').toJSON())
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join(', ')}}`
  lines.push([
    seed,
    shuffled(updates, random).map(update => Buffer.from(update).toString('base64')).join(','),
    array,
    expected.getText('t').toString(),
    map,
  ].join('\t'))
}

fs.writeFileSync(output, lines.join('\n'))
