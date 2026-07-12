import fs from 'node:fs'
import assert from 'node:assert/strict'

import * as Y from 'yjs'

const updatePath = process.argv[2]
if (!updatePath) throw new Error('usage: verify-preliminary-update.mjs <update>')

const doc = new Y.Doc({ gc: false })
Y.applyUpdate(doc, fs.readFileSync(updatePath))
const root = doc.getArray('root')
const outer = root.get(0)
assert.ok(outer instanceof Y.Array)
assert.equal(outer.get(0), 'before')

const inner = outer.get(1)
assert.ok(inner instanceof Y.Map)
assert.equal(inner.get('answer'), 42)

const element = outer.get(2)
assert.ok(element instanceof Y.XmlElement)
assert.equal(element.nodeName, 'P')
assert.equal(element.getAttribute('class'), 'intro')
const xmlText = element.get(0)
assert.ok(xmlText instanceof Y.XmlText)
assert.equal(xmlText.toString(), 'abc')

const hook = outer.get(3)
assert.ok(hook instanceof Y.XmlHook)
assert.equal(hook.get('enabled'), true)
assert.equal(outer.get(4), 'after')

const mapElement = doc.getMap('map').get('element')
assert.ok(mapElement instanceof Y.XmlElement)
assert.equal(mapElement.nodeName, 'ASIDE')
assert.equal(mapElement.toString(), '<aside>map-child</aside>')

const textDelta = doc.getText('body').toDelta()
assert.equal(textDelta.length, 1)
assert.ok(textDelta[0].insert instanceof Y.Array)
assert.deepEqual(textDelta[0].insert.toArray(), ['text-child'])
assert.deepEqual(textDelta[0].attributes, { bold: true })
