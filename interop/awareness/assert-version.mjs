import fs from 'node:fs'

const rootPackage = JSON.parse(
  fs.readFileSync(new URL('../../package.json', import.meta.url), 'utf8'),
)
const installedPackage = JSON.parse(
  fs.readFileSync(new URL('../../node_modules/y-protocols/package.json', import.meta.url), 'utf8'),
)

export const expectedAwarenessVersion = rootPackage.devDependencies['y-protocols']
export const installedAwarenessVersion = installedPackage.version

if (installedAwarenessVersion !== expectedAwarenessVersion) {
  throw new Error(
    `y-protocols oracle version mismatch: expected ${expectedAwarenessVersion}, installed ${installedAwarenessVersion}`,
  )
}
