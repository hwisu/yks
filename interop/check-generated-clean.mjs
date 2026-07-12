import { spawnSync } from 'node:child_process'

const generatedPaths = [
  'interop/yjs-v1/fixtures',
  'interop/yrs-oracle/fixtures',
]

const result = spawnSync(
  'git',
  ['status', '--porcelain=v1', '--untracked-files=all', '--', ...generatedPaths],
  { cwd: new URL('..', import.meta.url), encoding: 'utf8' },
)

if (result.error) {
  throw result.error
}
if (result.status !== 0) {
  process.stderr.write(result.stderr)
  process.exit(result.status ?? 1)
}

if (result.stdout.length > 0) {
  process.stderr.write('Generated interoperability fixtures are not clean:\n')
  process.stderr.write(result.stdout)
  process.exit(1)
}

console.log('Generated interoperability fixtures are clean')
