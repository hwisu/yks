import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import * as Y from 'yjs'

export const fixtureDirectory = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'fixtures',
)

export const helloFixturePath = path.join(fixtureDirectory, 'hello-text-v1.bin')
export const helloExpectedPath = path.join(fixtureDirectory, 'hello-text-v1.json')

export const createHelloDocument = () => {
  const doc = new Y.Doc()
  doc.clientID = 1
  doc.getText('body').insert(0, 'hello')
  return doc
}

export const describeDocument = doc => ({
  text: doc.getText('body').toString(),
  json: doc.toJSON(),
  stateVectorBase64: Buffer.from(Y.encodeStateVector(doc)).toString('base64'),
})

export const readHelloExpected = () =>
  JSON.parse(fs.readFileSync(helloExpectedPath, 'utf8'))

export const applyAndDescribe = update => {
  const doc = new Y.Doc()
  Y.applyUpdate(doc, update)
  return describeDocument(doc)
}
