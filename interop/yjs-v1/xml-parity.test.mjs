import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'

const renderXmlText = value => {
  const doc = new Y.Doc({ gc: false })
  const text = new Y.XmlText()
  doc.getXmlFragment('xml').push([text])
  text.insertEmbed(0, value, { mark: value })
  return { string: text.toString(), json: text.toJSON() }
}

test('Y.XmlText coerces embeds and enumerable formatting attributes like JavaScript', () => {
  const cases = [
    [{ a: 1, b: null }, '<mark a="1" b="null">[object Object]</mark>'],
    [['x', null, 3], '<mark 0="x" 1="null" 2="3">x,,3</mark>'],
    [new Uint8Array([1, 255]), '<mark 0="1" 1="255">1,255</mark>'],
    [7, '<mark>7</mark>'],
    [true, '<mark>true</mark>'],
  ]

  for (const [value, expected] of cases) {
    assert.deepEqual(renderXmlText(value), { string: expected, json: expected })
  }
})

test('Y.XmlHook is a map-backed type-ref-5 XML child', () => {
  const source = new Y.Doc({ gc: false })
  source.clientID = 1
  const hook = new Y.XmlHook('Widget')
  source.getXmlFragment('xml').push([hook])
  hook.set('x', 1)
  hook.set('nested', { ok: true })

  assert.equal(hook.hookName, 'Widget')
  assert.deepEqual(hook.toJSON(), { x: 1, nested: { ok: true } })
  assert.equal(hook.toString(), '[object Object]')
  assert.equal(source.getXmlFragment('xml').toString(), '[object Object]')

  const target = new Y.Doc({ gc: false })
  Y.applyUpdate(target, Y.encodeStateAsUpdate(source))
  const decoded = target.getXmlFragment('xml').get(0)

  assert.ok(decoded instanceof Y.XmlHook)
  assert.equal(decoded.hookName, 'Widget')
  assert.deepEqual(decoded.toJSON(), hook.toJSON())
  assert.equal(target.getXmlFragment('xml').toJSON(), '[object Object]')
})
