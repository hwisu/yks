import assert from 'node:assert/strict'
import readline from 'node:readline'

import * as Y from 'yjs'

const decode = (format, update) => {
  if (format === 'v1') {
    Y.decodeUpdate(update)
    return {
      meta: Y.parseUpdateMeta(update),
      stateVector: Y.encodeStateVectorFromUpdate(update),
    }
  }
  Y.decodeUpdateV2(update)
  return {
    meta: Y.parseUpdateMetaV2(update),
    stateVector: Y.encodeStateVectorFromUpdateV2(update),
  }
}

const normalizeMeta = meta => ({
  from: [...meta.from].sort(([left], [right]) => left - right),
  to: [...meta.to].sort(([left], [right]) => left - right),
})

const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
for await (const line of lines) {
  if (line.length === 0) continue
  const [fixture, operation, inputFormat, inputBase64, outputFormat, outputBase64] = line.split('\t')
  const input = Buffer.from(inputBase64, 'base64')
  const output = Buffer.from(outputBase64, 'base64')
  const inputDescription = decode(inputFormat, input)
  const outputDescription = decode(outputFormat, output)
  const label = `${fixture}:${operation}`
  const expected = operation === 'diff'
    ? decode(inputFormat, inputFormat === 'v1'
      ? Y.diffUpdate(input, Y.encodeStateVector(new Y.Doc()))
      : Y.diffUpdateV2(input, Y.encodeStateVector(new Y.Doc())))
    : inputDescription
  assert.deepEqual(normalizeMeta(outputDescription.meta), normalizeMeta(expected.meta), `${label}: metadata`)
  assert.deepEqual(outputDescription.stateVector, expected.stateVector, `${label}: state vector`)
}
