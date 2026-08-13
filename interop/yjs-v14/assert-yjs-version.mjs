import fs from 'node:fs'

const projectPackage = JSON.parse(fs.readFileSync(new URL('../../package.json', import.meta.url), 'utf8'))
const installedPackage = JSON.parse(fs.readFileSync(new URL('../../node_modules/yjs14/package.json', import.meta.url), 'utf8'))
const alias = projectPackage.devDependencies?.yjs14
const expected = typeof alias === 'string' ? alias.match(/^npm:@y\/y@(.+)$/)?.[1] : undefined

if (expected == null || installedPackage.name !== '@y/y' || installedPackage.version !== expected) {
  throw new Error(
    `Yjs 14 oracle mismatch: expected npm:@y/y@${expected ?? '<missing exact alias>'}, ` +
      `installed ${installedPackage.name}@${installedPackage.version}`,
  )
}

export const installedYjs14Version = installedPackage.version
