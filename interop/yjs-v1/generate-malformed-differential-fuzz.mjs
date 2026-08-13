import fs from 'node:fs'

import * as Y from 'yjs'

import './assert-yjs-version.mjs'

const output = process.argv[2]
const seedCount = Number.parseInt(process.argv[3] ?? '1000', 10)
if (!output || !Number.isSafeInteger(seedCount) || seedCount < 1) {
  throw new Error('usage: node generate-malformed-differential-fuzz.mjs <output.tsv> [seed-count]')
}

const source = new Y.Doc({ gc: false })
source.clientID = 1
source.getText('body').insert(0, 'malformed-fuzz-baseline')
const validUpdates = [Y.encodeStateAsUpdate(source), Y.encodeStateAsUpdateV2(source)]
const rows = []

const randomFor = seed => {
  let state = (seed + 1) >>> 0
  return max => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    return state % max
  }
}

for (let seed = 0; seed < seedCount; seed++) {
  for (let format = 0; format < validUpdates.length; format++) {
    const random = randomFor(seed * 2 + format)
    const valid = validUpdates[format]
    let payload
    if (seed % 3 === 0) {
      payload = valid.slice(0, random(valid.length + 1))
    } else if (seed % 3 === 1) {
      payload = valid.slice()
      const mutationCount = 1 + random(3)
      for (let i = 0; i < mutationCount; i++) {
        const index = random(payload.length)
        payload[index] ^= 1 << random(8)
      }
    } else {
      payload = new Uint8Array(1 + random(128))
      for (let i = 0; i < payload.length; i++) payload[i] = random(256)
    }

    const target = new Y.Doc({ gc: false })
    const text = target.getText('body')
    let accepted = true
    try {
      if (format === 0) Y.applyUpdate(target, payload)
      else Y.applyUpdateV2(target, payload)
    } catch {
      accepted = false
    }
    rows.push([
      seed,
      format,
      Buffer.from(payload).toString('base64'),
      accepted ? '1' : '0',
      accepted ? Buffer.from(text.toString()).toString('base64') : '-',
      accepted ? Buffer.from(Y.encodeStateVector(target)).toString('base64') : '-',
    ].join('\t'))
  }
}

fs.writeFileSync(output, `${rows.join('\n')}\n`)
