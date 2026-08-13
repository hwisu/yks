import assert from 'node:assert/strict'
import fs from 'node:fs'

import * as Y from 'yjs'
import {
  Awareness,
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
} from 'y-protocols/awareness.js'

import './assert-version.mjs'

const mode = process.argv[2]

if (mode === 'emit') {
  const doc = new Y.Doc()
  doc.clientID = 7
  const awareness = new Awareness(doc)
  awareness.setLocalState({
    name: 'Ada 😀',
    cursor: [3, 9],
    profile: { active: true },
  })
  process.stdout.write(Buffer.from(encodeAwarenessUpdate(awareness, [7])).toString('base64'))
  awareness.destroy()
} else if (mode === 'verify') {
  const input = process.argv[3]
  if (input == null) throw new Error('usage: oracle.mjs verify <update.bin>')
  const doc = new Y.Doc()
  doc.clientID = 88
  const awareness = new Awareness(doc)
  applyAwarenessUpdate(awareness, fs.readFileSync(input), 'kotlin')
  assert.deepEqual(awareness.getStates().get(9), {
    name: 'Kotlin 😀',
    cursor: [4, 12],
    profile: { active: false },
  })
  assert.equal(awareness.meta.get(9).clock, 1)
  awareness.destroy()
} else {
  throw new Error('usage: oracle.mjs <emit|verify> [update.bin]')
}
