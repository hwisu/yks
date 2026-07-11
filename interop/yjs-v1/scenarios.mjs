import * as Y from 'yjs'

export const scenarioNames = [
  'hello',
  'array',
  'map',
  'nested-map',
  'nested-map-update',
  'nested-text',
  'formatted-text',
  'partial-formatted-text',
]

export const createScenarioDocument = name => {
  const doc = new Y.Doc()
  doc.clientID = 1

  switch (name) {
    case 'hello':
      doc.getText('body').insert(0, 'hello')
      break
    case 'array':
      doc.getArray('items').insert(0, [
        'a',
        42,
        true,
        null,
        new Uint8Array([1, 2]),
      ])
      break
    case 'map': {
      const map = doc.getMap('meta')
      map.set('title', 'hello')
      map.set('count', 2)
      break
    }
    case 'nested-map': {
      const root = doc.getMap('root')
      const profile = new Y.Map()
      root.set('profile', profile)
      profile.set('name', 'Ada')
      break
    }
    case 'nested-map-update': {
      const root = doc.getMap('root')
      const profile = new Y.Map()
      root.set('profile', profile)
      profile.set('name', 'Ada')
      profile.set('city', 'Seoul')
      break
    }
    case 'nested-text': {
      const root = doc.getArray('nodes')
      const text = new Y.Text()
      root.insert(0, [text])
      text.insert(0, 'child')
      break
    }
    case 'formatted-text':
      doc.getText('body').insert(0, 'ab', { bold: true })
      break
    case 'partial-formatted-text':
      doc.getText('body').insert(0, 'abcd')
      doc.getText('body').format(1, 2, { bold: true })
      break
    default:
      throw new Error(`unknown interoperability scenario: ${name}`)
  }

  return doc
}

export const materializeScenario = (doc, name) => {
  switch (name) {
    case 'hello':
      return doc.getText('body')
    case 'array':
      return doc.getArray('items')
    case 'map':
      return doc.getMap('meta')
    case 'nested-map':
    case 'nested-map-update':
      return doc.getMap('root')
    case 'nested-text':
      return doc.getArray('nodes')
    case 'formatted-text':
    case 'partial-formatted-text':
      return doc.getText('body')
    default:
      throw new Error(`unknown interoperability scenario: ${name}`)
  }
}
