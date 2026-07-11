import * as Y from 'yjs'

export const scenarioNames = [
  'hello',
  'array',
  'map',
  'nested-map',
  'nested-map-update',
  'nested-text',
  'formatted-text',
  'formatted-embed',
  'partial-formatted-text',
  'xml',
  'xml-formatted',
  'subdoc-map',
  'subdoc-array',
  'text-delete',
  'xml-delete',
  'subdoc-delete',
  'concurrent-array',
  'concurrent-format',
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
    case 'formatted-embed':
      doc.getText('body').insertEmbed(0, { image: 'x' }, { bold: true })
      break
    case 'partial-formatted-text':
      doc.getText('body').insert(0, 'abcd')
      doc.getText('body').format(1, 2, { bold: true })
      break
    case 'xml': {
      const paragraph = new Y.XmlElement('p')
      doc.getXmlFragment('xml').insert(0, [paragraph])
      paragraph.setAttribute('class', 'intro')
      const text = new Y.XmlText()
      paragraph.insert(0, [text])
      text.insert(0, 'hi')
      break
    }
    case 'xml-formatted': {
      const paragraph = new Y.XmlElement('p')
      doc.getXmlFragment('xml').insert(0, [paragraph])
      paragraph.setAttribute('class', 'intro')
      const text = new Y.XmlText()
      paragraph.insert(0, [text])
      text.insert(0, 'hi', { strong: { level: '1' } })
      break
    }
    case 'subdoc-map':
      doc.getMap('subs').set('child', new Y.Doc({ guid: 'child', shouldLoad: false }))
      break
    case 'subdoc-array':
      doc.getArray('subs').insert(0, [
        new Y.Doc({
          guid: 'child-guid',
          gc: false,
          autoLoad: true,
          meta: { role: 'child' },
        }),
      ])
      break
    case 'text-delete': {
      const text = doc.getText('body')
      text.insert(0, 'hello')
      text.delete(1, 3)
      break
    }
    case 'xml-delete': {
      const fragment = doc.getXmlFragment('xml')
      fragment.insert(0, [new Y.XmlElement('p')])
      fragment.delete(0, 1)
      break
    }
    case 'subdoc-delete': {
      const subs = doc.getMap('subs')
      subs.set('child', new Y.Doc({ guid: 'child', shouldLoad: false }))
      subs.delete('child')
      break
    }
    case 'concurrent-array': {
      const base = new Y.Doc({ gc: false })
      base.clientID = 1
      base.getArray('letters').insert(0, ['a', 'b'])
      const baseUpdate = Y.encodeStateAsUpdate(base)
      const x = new Y.Doc({ gc: false })
      x.clientID = 2
      Y.applyUpdate(x, baseUpdate)
      x.getArray('letters').insert(1, ['X'])
      const y = new Y.Doc({ gc: false })
      y.clientID = 3
      Y.applyUpdate(y, baseUpdate)
      y.getArray('letters').insert(1, ['Y'])
      Y.applyUpdate(doc, Y.encodeStateAsUpdate(base))
      Y.applyUpdate(doc, Y.encodeStateAsUpdate(x, Y.encodeStateVector(base)))
      Y.applyUpdate(doc, Y.encodeStateAsUpdate(y, Y.encodeStateVector(base)))
      doc.getArray('letters')
      break
    }
    case 'concurrent-format': {
      const base = new Y.Doc({ gc: false })
      base.clientID = 1
      base.getText('body').insert(0, 'abcd')
      const baseUpdate = Y.encodeStateAsUpdate(base)
      const bold = new Y.Doc({ gc: false })
      bold.clientID = 2
      Y.applyUpdate(bold, baseUpdate)
      bold.getText('body').format(0, 2, { bold: true })
      const italic = new Y.Doc({ gc: false })
      italic.clientID = 3
      Y.applyUpdate(italic, baseUpdate)
      italic.getText('body').format(1, 2, { italic: true })
      Y.applyUpdate(doc, Y.encodeStateAsUpdate(base))
      Y.applyUpdate(doc, Y.encodeStateAsUpdate(bold, Y.encodeStateVector(base)))
      Y.applyUpdate(doc, Y.encodeStateAsUpdate(italic, Y.encodeStateVector(base)))
      doc.getText('body')
      break
    }
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
    case 'formatted-embed':
    case 'partial-formatted-text':
      return doc.getText('body')
    case 'xml':
    case 'xml-formatted':
      return doc.getXmlFragment('xml')
    case 'subdoc-map':
      return doc.getMap('subs')
    case 'subdoc-array':
      return doc.getArray('subs')
    case 'text-delete':
      return doc.getText('body')
    case 'xml-delete':
      return doc.getXmlFragment('xml')
    case 'subdoc-delete':
      return doc.getMap('subs')
    case 'concurrent-array':
      return doc.getArray('letters')
    case 'concurrent-format':
      return doc.getText('body')
    default:
      throw new Error(`unknown interoperability scenario: ${name}`)
  }
}
