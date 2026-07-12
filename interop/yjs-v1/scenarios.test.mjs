import assert from 'node:assert/strict'
import test from 'node:test'

import * as Y from 'yjs'

import {
  createScenarioDocument,
  materializeScenario,
  scenarioNames,
} from './scenarios.mjs'

for (const name of scenarioNames) {
  test(`upstream Yjs round-trips the ${name} V1 scenario`, () => {
    const source = createScenarioDocument(name)
    const target = new Y.Doc()
    Y.applyUpdate(target, Y.encodeStateAsUpdate(source))
    materializeScenario(target, name)

    if (name !== 'subdoc-map' && name !== 'subdoc-array' && name !== 'subdoc-array-default') {
      assert.deepEqual(target.toJSON(), source.toJSON())
    }
    if (
      name === 'formatted-text' ||
      name === 'formatted-embed' ||
      name === 'partial-formatted-text' ||
      name === 'concurrent-format'
    ) {
      assert.deepEqual(target.getText('body').toDelta(), source.getText('body').toDelta())
    }
    if (name === 'subdoc-map') {
      assert.equal(target.getMap('subs').get('child').guid, 'child')
    }
    if (name === 'subdoc-array') {
      const child = target.getArray('subs').get(0)
      assert.equal(child.guid, 'child-guid')
      assert.equal(child.gc, false)
      assert.equal(child.autoLoad, true)
      assert.deepEqual(child.meta, { role: 'child' })
    }
    if (name === 'subdoc-array-default') {
      const child = target.getArray('subs').get(0)
      assert.equal(child.guid, 'child')
      assert.equal(child.shouldLoad, false)
      assert.equal(child.autoLoad, false)
    }
    if (name === 'xml-hook') {
      const hook = target.getXmlFragment('xml').get(0)
      assert.ok(hook instanceof Y.XmlHook)
      assert.equal(hook.hookName, 'Widget')
      assert.deepEqual(hook.toJSON(), { x: 1, nested: { ok: true } })
    }
  })
}
