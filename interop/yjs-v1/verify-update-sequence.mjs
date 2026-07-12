import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

import { createScenarioDocument, materializeScenario } from './scenarios.mjs'

const scenario = process.argv[2]
const inputs = process.argv.slice(3)
if (scenario == null || inputs.length === 0) {
  throw new Error(
    'usage: node verify-update-sequence.mjs <scenario> <update.bin> [...]',
  )
}

const actual = new Y.Doc()
if (scenario === 'subdoc-text-delete' || scenario === 'subdoc-xml-text-delete') {
  assert.ok(inputs.length >= 2, 'subdocument delete verification requires an insert followed by a delete')
  const isText = scenario === 'subdoc-text-delete'
  const embeddedSubdoc = () => {
    const visibleContentDoc = type => {
      for (let item = type._start; item != null; item = item.right) {
        if (!item.deleted && item.content?.doc instanceof Y.Doc) return item.content.doc
      }
      return undefined
    }
    if (isText) return visibleContentDoc(actual.getText('body'))
    const paragraph = actual.getXmlFragment('xml').get(0)
    assert.ok(paragraph instanceof Y.XmlElement, 'subdocument baseline must contain the expected XML element')
    const text = paragraph.get(0)
    assert.ok(text instanceof Y.XmlText, 'subdocument baseline must contain the expected XML text')
    return visibleContentDoc(text)
  }

  if (isText) actual.getText('body')
  Y.applyUpdate(actual, fs.readFileSync(inputs[0]))
  const inserted = embeddedSubdoc()
  assert.ok(inserted instanceof Y.Doc, 'the first update must insert the subdocument at the expected position')
  assert.equal(inserted.guid, isText ? 'text-child' : 'xml-child')
  assert.deepEqual([...actual.subdocs], [inserted])

  for (const input of inputs.slice(1)) {
    Y.applyUpdate(actual, fs.readFileSync(input))
  }
  assert.equal(actual.subdocs.size, 0)
  assert.equal(embeddedSubdoc(), undefined, 'the final update must delete the embedded subdocument')
  process.exit(0)
}

for (const input of inputs) {
  Y.applyUpdate(actual, fs.readFileSync(input))
}

const expected = createScenarioDocument(scenario)
materializeScenario(actual, scenario)

if (scenario !== 'subdoc-map' && scenario !== 'subdoc-array') {
  assert.deepEqual(actual.toJSON(), expected.toJSON())
}
assert.deepEqual(Y.encodeStateVector(actual), Y.encodeStateVector(expected))
if (
  scenario === 'formatted-text' ||
  scenario === 'formatted-embed' ||
  scenario === 'concurrent-format' ||
  scenario === 'partial-formatted-text'
) {
  assert.deepEqual(actual.getText('body').toDelta(), expected.getText('body').toDelta())
}
if (scenario === 'subdoc-map') {
  assert.equal(actual.getMap('subs').get('child').guid, 'child')
}
if (scenario === 'subdoc-array') {
  const child = actual.getArray('subs').get(0)
  assert.equal(child.guid, 'child-guid')
  assert.equal(child.gc, false)
  assert.equal(child.autoLoad, true)
  assert.deepEqual(child.meta, { role: 'child' })
}
