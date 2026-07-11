import fs from 'node:fs'

import * as Y from 'yjs'

import {
  createHelloDocument,
  describeDocument,
  fixtureDirectory,
  helloExpectedPath,
  helloFixturePath,
} from './fixture-helpers.mjs'

const doc = createHelloDocument()
const update = Y.encodeStateAsUpdate(doc)
const expected = {
  fixture: 'hello-text-v1',
  yjsVersion: '13.6.31',
  clientId: 1,
  updateBase64: Buffer.from(update).toString('base64'),
  ...describeDocument(doc),
}

fs.mkdirSync(fixtureDirectory, { recursive: true })
fs.writeFileSync(helloFixturePath, update)
fs.writeFileSync(helloExpectedPath, `${JSON.stringify(expected, null, 2)}\n`)
