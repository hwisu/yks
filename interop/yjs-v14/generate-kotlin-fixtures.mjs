import fs from 'node:fs'

import * as Y from 'yjs14'

import './assert-yjs-version.mjs'

const output = process.argv[2]
if (output == null) throw new Error('usage: node generate-kotlin-fixtures.mjs <output.tsv>')

const doc = new Y.Doc({ gc: false })
doc.clientID = 14
doc.get('body').insert(0, 'A😀한', { bold: true })
doc.get('items').insert(0, [1, 'x', true])
doc.get('meta').setAttr('title', 'hello')
const paragraph = new Y.Type('p')
paragraph.setAttr('id', 'intro')
paragraph.insert(0, 'hello')
doc.get('xml').push([paragraph])

const rows = [
  ['v1', Y.encodeStateAsUpdate(doc)],
  ['v2', Y.encodeStateAsUpdateV2(doc)],
].map(([format, update]) => `${format}\t${Buffer.from(update).toString('base64')}`)

fs.writeFileSync(output, `${rows.join('\n')}\n`)
