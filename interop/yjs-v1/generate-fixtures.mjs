import fs from 'node:fs'

import * as Y from 'yjs'

import {
  createHelloDocument,
  describeDocument,
  fixtureDirectory,
  helloExpectedPath,
  helloFixturePath,
} from './fixture-helpers.mjs'
import { createScenarioDocument } from './scenarios.mjs'

const doc = createHelloDocument()
const update = Y.encodeStateAsUpdate(doc)
const expected = {
  fixture: 'hello-text-v1',
  yjsVersion: '13.6.31',
  clientId: 1,
  updateBase64: Buffer.from(update).toString('base64'),
  ...describeDocument(doc),
}

fs.mkdirSync(fixtureDirectory, { recursive: true })
fs.writeFileSync(helloFixturePath, update)
fs.writeFileSync(helloExpectedPath, `${JSON.stringify(expected, null, 2)}\n`)

const writeFixture = (name, value) => {
  fs.writeFileSync(`${fixtureDirectory}/${name}.bin`, value)
}

const writeHexFixture = (name, value) => {
  writeFixture(name, Buffer.from(value, 'hex'))
}

// Canonical upstream encodings for a two-clock hole followed by a root-text
// item at clock 2. Skip only describes omitted update data, while GC owns the
// skipped clocks in the document store.
writeHexFixture('skip-then-text-v1', '010201000a02040104626f6479017800')
writeHexFixture('gc-then-text-v1', '010201000002040104626f6479017800')

const replacedText = new Y.Doc()
replacedText.clientID = 1
const replacedBody = replacedText.getText('body')
replacedBody.insert(0, 'old')
replacedBody.delete(0, 3)
replacedBody.insert(0, 'new')
writeFixture('text-replace-full-v1', Y.encodeStateAsUpdate(replacedText))

const formattedInsert = new Y.Doc({ gc: false })
formattedInsert.clientID = 1
formattedInsert.getText('body').insert(0, 'ab', { bold: true })
writeFixture('text-format-insert-v1', Y.encodeStateAsUpdate(formattedInsert))

const formattedRemoval = new Y.Doc({ gc: false })
formattedRemoval.clientID = 1
const removalUpdates = []
formattedRemoval.getText('body').insert(0, 'ab', { bold: true })
formattedRemoval.on('update', value => removalUpdates.push(value))
formattedRemoval.getText('body').format(0, 2, { bold: null })
writeFixture('text-format-remove-v1', removalUpdates[0])

const partialFormat = new Y.Doc({ gc: false })
partialFormat.clientID = 1
const partialFormatUpdates = []
partialFormat.on('update', value => partialFormatUpdates.push(value))
partialFormat.getText('body').insert(0, 'abcd')
partialFormat.getText('body').format(1, 2, { bold: true })
writeFixture('text-format-base-v1', partialFormatUpdates[0])
writeFixture('text-format-partial-v1', partialFormatUpdates[1])
writeFixture('text-format-partial-full-v1', Y.encodeStateAsUpdate(partialFormat))

const formattedEmbed = new Y.Doc({ gc: false })
formattedEmbed.clientID = 1
formattedEmbed.getText('body').insertEmbed(0, { image: 'x' }, { bold: true })
writeFixture('text-format-embed-v1', Y.encodeStateAsUpdate(formattedEmbed))

const restoredFormat = new Y.Doc({ gc: false })
restoredFormat.clientID = 1
const restoredBody = restoredFormat.getText('body')
restoredBody.insert(0, 'abc')
restoredBody.format(0, 3, { url: 'outer' })
restoredBody.format(1, 1, { url: 'inner' })
writeFixture('text-format-restoration-v1', Y.encodeStateAsUpdate(restoredFormat))

writeFixture(
  'array-v1',
  Y.encodeStateAsUpdate(createScenarioDocument('array')),
)

const array = new Y.Doc({ gc: false })
array.clientID = 1
const arrayUpdates = []
array.on('update', value => arrayUpdates.push(value))
array.getArray('numbers').insert(0, [1, 2, 3])
array.getArray('numbers').insert(3, [4])
writeFixture('array-base-v1', arrayUpdates[0])
writeFixture('array-append-v1', arrayUpdates[1])

const front = new Y.Doc({ gc: false })
front.clientID = 1
const frontUpdates = []
front.on('update', value => frontUpdates.push(value))
front.getArray('letters').insert(0, ['a', 'b'])
front.getArray('letters').insert(0, ['x'])
writeFixture('array-front-base-v1', frontUpdates[0])
writeFixture('array-front-insert-v1', frontUpdates[1])

const interior = new Y.Doc({ gc: false })
interior.clientID = 1
const interiorUpdates = []
interior.on('update', value => interiorUpdates.push(value))
interior.getArray('letters').insert(0, ['a', 'b', 'c'])
interior.getArray('letters').insert(2, ['X'])
writeFixture('array-interior-base-v1', interiorUpdates[0])
writeFixture('array-interior-insert-v1', interiorUpdates[1])

const map = new Y.Doc({ gc: false })
map.clientID = 1
const mapUpdates = []
map.on('update', value => mapUpdates.push(value))
map.getMap('meta').set('title', 'old')
map.getMap('meta').set('title', 'new')
writeFixture('map-base-v1', mapUpdates[0])
writeFixture('map-replace-v1', mapUpdates[1])
writeFixture('map-full-v1', Y.encodeStateAsUpdate(map))

const mapKeys = new Y.Doc({ gc: false })
mapKeys.clientID = 1
const mapKeyUpdates = []
mapKeys.on('update', value => mapKeyUpdates.push(value))
mapKeys.getMap('meta').set('first', 1)
mapKeys.getMap('meta').set('second', 2)
writeFixture('map-first-key-v1', mapKeyUpdates[0])
writeFixture('map-second-key-v1', mapKeyUpdates[1])

const mapGc = new Y.Doc()
mapGc.clientID = 1
mapGc.getMap('meta').set('title', 'old')
mapGc.getMap('meta').set('title', 'new')
writeFixture('map-full-gc-v1', Y.encodeStateAsUpdate(mapGc))

const mapDelete = new Y.Doc()
mapDelete.clientID = 1
mapDelete.getMap('meta').set('title', 'old')
mapDelete.getMap('meta').set('title', 'new')
mapDelete.getMap('meta').set('temporary', true)
mapDelete.getMap('meta').delete('temporary')
writeFixture('map-delete-full-v1', Y.encodeStateAsUpdate(mapDelete))

const nested = new Y.Doc({ gc: false })
nested.clientID = 1
const profile = new Y.Map()
nested.getMap('root').set('profile', profile)
profile.set('name', 'Ada')
const nestedState = Y.encodeStateVector(nested)
const nestedBase = Y.encodeStateAsUpdate(nested)
profile.set('city', 'Seoul')
writeFixture('nested-map-base-v1', nestedBase)
writeFixture(
  'nested-map-city-v1',
  Y.encodeStateAsUpdate(nested, nestedState),
)

const owner = new Y.Doc({ gc: false })
owner.clientID = 1
owner.getMap('root').set('profile', new Y.Map())
const ownerUpdate = Y.encodeStateAsUpdate(owner)
const child = new Y.Doc({ gc: false })
child.clientID = 2
Y.applyUpdate(child, ownerUpdate)
const childUpdates = []
child.on('update', value => childUpdates.push(value))
child.getMap('root').get('profile').set('name', 'Ada')
writeFixture('nested-owner-v1', ownerUpdate)
writeFixture('nested-child-v1', childUpdates[0])
const ownerDeleteUpdates = []
owner.on('update', value => ownerDeleteUpdates.push(value))
owner.getMap('root').delete('profile')
writeFixture('nested-owner-delete-v1', ownerDeleteUpdates[0])

const gcNested = new Y.Doc()
gcNested.clientID = 1
const gcRoot = gcNested.getArray('gc-root')
const gcChild = new Y.Map()
gcRoot.insert(0, [gcChild])
gcChild.set('value', 1)
gcRoot.delete(0)
writeFixture('gc-nested-delete-v1', Y.encodeStateAsUpdate(gcNested))

const xml = new Y.Doc({ gc: false })
xml.clientID = 1
const xmlUpdates = []
xml.on('update', value => xmlUpdates.push(value))
const xmlRoot = xml.getXmlFragment('xml')
const paragraph = new Y.XmlElement('p')
xmlRoot.insert(0, [paragraph])
xml.transact(() => {
  paragraph.setAttribute('class', 'intro')
  const text = new Y.XmlText()
  paragraph.insert(0, [text])
  text.insert(0, 'hi')
})
writeFixture('xml-owner-v1', xmlUpdates[0])
writeFixture('xml-content-v1', xmlUpdates[1])
writeFixture('xml-basic-full-v1', Y.encodeStateAsUpdate(xml))

const formattedXml = new Y.Doc({ gc: false })
formattedXml.clientID = 1
const formattedXmlParagraph = new Y.XmlElement('p')
formattedXml.getXmlFragment('xml').insert(0, [formattedXmlParagraph])
formattedXmlParagraph.setAttribute('class', 'intro')
const formattedXmlText = new Y.XmlText()
formattedXmlParagraph.insert(0, [formattedXmlText])
formattedXmlText.insert(0, 'hi', { strong: { level: '1' } })
writeFixture('xml-formatted-full-v1', Y.encodeStateAsUpdate(formattedXml))

const crossXml = new Y.Doc({ gc: false })
Y.applyUpdate(crossXml, xmlUpdates[0])
crossXml.clientID = 2
const crossXmlUpdates = []
crossXml.on('update', value => crossXmlUpdates.push(value))
crossXml.transact(() => {
  const remoteParagraph = crossXml.getXmlFragment('xml').get(0)
  remoteParagraph.setAttribute('class', 'remote')
  const text = new Y.XmlText()
  remoteParagraph.insert(0, [text])
  text.insert(0, 'ok')
})
writeFixture('xml-cross-client-content-v1', crossXmlUpdates[0])

const rootXmlElement = new Y.Doc({ gc: false })
rootXmlElement.clientID = 1
const article = rootXmlElement.getXmlElement('article')
article.setAttribute('class', 'root')
const articleText = new Y.XmlText()
article.insert(0, [articleText])
articleText.insert(0, 'hi')
writeFixture('xml-root-element-v1', Y.encodeStateAsUpdate(rootXmlElement))

const subdocMap = new Y.Doc({ gc: false })
subdocMap.clientID = 1
subdocMap.getMap('subs').set('child', new Y.Doc({ guid: 'child' }))
writeFixture('subdoc-map-default-v1', Y.encodeStateAsUpdate(subdocMap))

const subdocArray = new Y.Doc({ gc: false })
subdocArray.clientID = 1
subdocArray.getArray('subs').insert(0, [
  new Y.Doc({
    guid: 'child-guid',
    gc: false,
    autoLoad: true,
    meta: { role: 'child' },
  }),
])
writeFixture('subdoc-array-options-v1', Y.encodeStateAsUpdate(subdocArray))

const duplicateSubdocs = new Y.Doc({ gc: false })
duplicateSubdocs.clientID = 1
duplicateSubdocs.getArray('subs').insert(0, [
  new Y.Doc({ guid: 'same-guid' }),
  new Y.Doc({ guid: 'same-guid' }),
])
writeFixture('subdoc-duplicate-guid-v1', Y.encodeStateAsUpdate(duplicateSubdocs))
