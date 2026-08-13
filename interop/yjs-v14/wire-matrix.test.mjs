import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y13 from 'yjs'
import * as Y14 from 'yjs14'

import './assert-yjs-version.mjs'

const createV14Document = () => {
  const doc = new Y14.Doc({ gc: false })
  doc.clientID = 14
  doc.get('body').insert(0, 'A😀한', { bold: true })
  doc.get('items').insert(0, [1, 'x', true])
  doc.get('meta').setAttr('title', 'hello')
  const paragraph = new Y14.Type('p')
  paragraph.setAttr('id', 'intro')
  paragraph.insert(0, 'hello')
  doc.get('xml').push([paragraph])
  return doc
}

const createV13Document = () => {
  const doc = new Y13.Doc({ gc: false })
  doc.clientID = 13
  doc.getText('body').insert(0, 'B😀한', { italic: true })
  doc.getArray('items').insert(0, [2, 'y', false])
  doc.getMap('meta').set('title', 'world')
  const element = new Y13.XmlElement('q')
  element.setAttribute('id', 'outro')
  doc.getXmlFragment('xml').insert(0, [element])
  return doc
}

for (const format of ['v1', 'v2']) {
  test(`Yjs 14 ${format} updates apply in pinned Yjs 13`, () => {
    const source = createV14Document()
    const update = format === 'v1' ? Y14.encodeStateAsUpdate(source) : Y14.encodeStateAsUpdateV2(source)
    const target = new Y13.Doc({ gc: false })
    const body = target.getText('body')
    const items = target.getArray('items')
    const meta = target.getMap('meta')
    const xml = target.getXmlFragment('xml')
    if (format === 'v1') Y13.applyUpdate(target, update)
    else Y13.applyUpdateV2(target, update)

    assert.deepEqual(body.toDelta(), [{ insert: 'A😀한', attributes: { bold: true } }])
    assert.deepEqual(items.toArray(), [1, 'x', true])
    assert.deepEqual(meta.toJSON(), { title: 'hello' })
    assert.equal(xml.toString(), '<p id="intro">hello</p>')
  })

  test(`pinned Yjs 13 ${format} updates apply in Yjs 14`, () => {
    const source = createV13Document()
    const update = format === 'v1' ? Y13.encodeStateAsUpdate(source) : Y13.encodeStateAsUpdateV2(source)
    const target = new Y14.Doc({ gc: false })
    if (format === 'v1') Y14.applyUpdate(target, update)
    else Y14.applyUpdateV2(target, update)

    assert.equal(target.get('body').toString(), 'B😀한')
    assert.deepEqual(target.get('items').toArray(), [2, 'y', false])
    assert.deepEqual(target.get('meta').getAttrs(), { title: 'world' })
    assert.equal(target.get('xml').toString(), '<q id="outro" />')
  })
}
