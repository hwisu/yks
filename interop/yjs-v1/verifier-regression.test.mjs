import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

import * as Y from 'yjs'

const projectDirectory = path.resolve(import.meta.dirname, '../..')
const verifyUpdate = path.join(import.meta.dirname, 'verify-update.mjs')
const verifySequence = path.join(import.meta.dirname, 'verify-update-sequence.mjs')

const runVerifier = args => spawnSync(process.execPath, args, {
  cwd: projectDirectory,
  encoding: 'utf8',
})

const withTempUpdate = (update, callback) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yks-verifier-'))
  const updatePath = path.join(directory, 'update.bin')
  try {
    fs.writeFileSync(updatePath, update)
    callback(updatePath)
  } finally {
    fs.rmSync(directory, { recursive: true, force: true })
  }
}

test('subdocument delete verifiers reject sequences that never inserted a subdocument', () => {
  withTempUpdate(Y.encodeStateAsUpdate(new Y.Doc()), updatePath => {
    for (const scenario of ['subdoc-text-delete', 'subdoc-xml-text-delete']) {
      const result = runVerifier([verifySequence, scenario, updatePath, updatePath])
      assert.notEqual(result.status, 0, `${scenario} unexpectedly accepted two empty updates`)
    }
  })
})

test('embedded subdocument verifiers reject a matching guid stored at the wrong location', () => {
  for (const [scenario, guid] of [
    ['subdoc-text', 'text-child'],
    ['subdoc-xml-text', 'xml-child'],
  ]) {
    const doc = new Y.Doc()
    doc.getMap('wrong').set('child', new Y.Doc({ guid }))
    withTempUpdate(Y.encodeStateAsUpdate(doc), updatePath => {
      const result = runVerifier([verifyUpdate, updatePath, scenario])
      assert.notEqual(result.status, 0, `${scenario} unexpectedly accepted a map-stored subdocument`)
    })
  }
})
