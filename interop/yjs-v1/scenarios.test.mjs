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

    assert.deepEqual(target.toJSON(), source.toJSON())
    if (name === 'formatted-text' || name === 'partial-formatted-text') {
      assert.deepEqual(target.getText('body').toDelta(), source.getText('body').toDelta())
    }
  })
}
