import fs from 'node:fs'

const rootPackage = JSON.parse(
  fs.readFileSync(new URL('../../package.json', import.meta.url), 'utf8'),
)
const installedPackage = JSON.parse(
  fs.readFileSync(new URL('../../node_modules/yjs/package.json', import.meta.url), 'utf8'),
)

export const expectedYjsVersion = rootPackage.devDependencies.yjs
export const installedYjsVersion = installedPackage.version

if (installedYjsVersion !== expectedYjsVersion) {
  throw new Error(
    `Yjs oracle version mismatch: expected ${expectedYjsVersion}, installed ${installedYjsVersion}`,
  )
}
