import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

import { applyAndDescribe, readHelloExpected } from './fixture-helpers.mjs'
import { createScenarioDocument, materializeScenario } from './scenarios.mjs'

const input = process.argv[2]
const scenario = process.argv[3] ?? 'hello'
if (input == null) {
  throw new Error('usage: npm run interop:verify -- <update.bin> [scenario]')
}

const embeddedSubdoc = (doc, scenario) => {
  const visibleContentDoc = type => {
    for (let item = type._start; item != null; item = item.right) {
      if (!item.deleted && item.content?.doc instanceof Y.Doc) return item.content.doc
    }
    return undefined
  }
  if (scenario === 'subdoc-text') {
    return visibleContentDoc(doc.getText('body'))
  }
  const paragraph = doc.getXmlFragment('xml').get(0)
  assert.ok(paragraph instanceof Y.XmlElement, 'subdocument must be nested in the expected XML element')
  const text = paragraph.get(0)
  assert.ok(text instanceof Y.XmlText, 'subdocument must be nested in the expected XML text')
  return visibleContentDoc(text)
}

if (scenario === 'subdoc-text' || scenario === 'subdoc-xml-text') {
  const actual = new Y.Doc()
  if (scenario === 'subdoc-text') actual.getText('body')
  Y.applyUpdate(actual, fs.readFileSync(input))
  const child = [...actual.subdocs][0]
  assert.ok(child instanceof Y.Doc)
  assert.equal(child.guid, scenario === 'subdoc-text' ? 'text-child' : 'xml-child')
  assert.deepEqual([...actual.subdocs], [child])
  assert.equal(embeddedSubdoc(actual, scenario), child, 'subdocument must be stored at the expected text position')
} else if (scenario === 'hello') {
  const expected = readHelloExpected()
  const actual = applyAndDescribe(fs.readFileSync(input))

  assert.equal(actual.text, expected.text)
  assert.deepEqual(actual.json, expected.json)
  assert.equal(actual.stateVectorBase64, expected.stateVectorBase64)
} else if (scenario === 'xml-root-text') {
  const actual = new Y.Doc()
  const text = actual.get('root-xml-text', Y.XmlText)
  Y.applyUpdate(actual, fs.readFileSync(input))
  assert.equal(text.toString(), '<strong level="1">hello</strong>')
} else if (scenario === 'xml-root-hook') {
  const actual = new Y.Doc()
  const hook = actual.get('root-xml-hook', Y.XmlHook)
  Y.applyUpdate(actual, fs.readFileSync(input))
  assert.deepEqual(hook.toJSON(), { count: 1, nested: { ok: true } })
} else {
  const expected = createScenarioDocument(scenario)
  const actual = new Y.Doc()
  Y.applyUpdate(actual, fs.readFileSync(input))
  materializeScenario(actual, scenario)
  if (scenario !== 'subdoc-map' && scenario !== 'subdoc-array' && scenario !== 'subdoc-array-default') {
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
  if (scenario === 'nested-map') {
    assert.ok(actual.getMap('root').get('profile') instanceof Y.Map)
  }
  if (scenario === 'nested-text') {
    assert.ok(actual.getArray('nodes').get(0) instanceof Y.Text)
  }
  if (scenario === 'xml-hook') {
    const hook = actual.getXmlFragment('xml').get(0)
    assert.ok(hook instanceof Y.XmlHook)
    assert.equal(hook.hookName, 'Widget')
    assert.deepEqual(hook.toJSON(), { x: 1, nested: { ok: true } })
  }
  if (scenario === 'subdoc-map') {
    const child = actual.getMap('subs').get('child')
    assert.ok(child instanceof Y.Doc)
    assert.equal(child.guid, 'child')
  }
  if (scenario === 'subdoc-array') {
    const child = actual.getArray('subs').get(0)
    assert.ok(child instanceof Y.Doc)
    assert.equal(child.guid, 'child-guid')
    assert.equal(child.gc, false)
    assert.equal(child.shouldLoad, true)
    assert.equal(child.autoLoad, true)
    assert.deepEqual(child.meta, { role: 'child' })
  }
  if (scenario === 'subdoc-array-default') {
    const child = actual.getArray('subs').get(0)
    assert.ok(child instanceof Y.Doc)
    assert.equal(child.guid, 'child')
    assert.equal(child.shouldLoad, false)
    assert.equal(child.autoLoad, false)
  }
}
