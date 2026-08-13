#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const args = process.argv.slice(2)
const currentPath = args.pop()
const baselineRefs = args
if (baselineRefs.length === 0 || !currentPath) {
  throw new Error('usage: check-release-abi.mjs <baseline-git-ref>... <current-api-dump>')
}

const current = readFileSync(currentPath, 'utf8')

function parseApi(text) {
  const classes = new Map()
  let active = null
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd()
    if (line.startsWith('public ') && line.endsWith(' {')) {
      const match = line.match(/\bclass ([^ :{]+)/)
      if (match) {
        active = { header: line, members: [] }
        classes.set(match[1], active)
      }
      continue
    }
    if (active && line === '}') {
      active = null
      continue
    }
    if (active && line.startsWith('\tpublic ')) active.members.push(line.slice(1))
  }
  return classes
}

function declarationKind(header) {
  if (header.includes(' annotation class ')) return 'annotation'
  if (header.includes(' interface class ')) return 'interface'
  if (header.includes(' enum class ')) return 'enum'
  return 'class'
}

function supertypes(header) {
  const separator = header.indexOf(' : ')
  if (separator < 0) return []
  return header.slice(separator + 3, -2).split(', ')
}

function canonicalMember(line) {
  const callable = line.indexOf('fun ')
  if (callable >= 0) {
    return `${line.includes(' static ') ? 'static ' : ''}${line.slice(callable)}`
  }
  const field = line.indexOf('field ')
  if (field >= 0) {
    return `${line.includes(' static ') ? 'static ' : ''}${line.slice(field)}`
  }
  return line.replace(/\b(final|abstract|synthetic)\s+/g, '')
}

function isAbstract(line) {
  return /\babstract\b/.test(line)
}

function isFinal(line) {
  return /\bfinal\b/.test(line)
}

const newClasses = parseApi(current)
let failed = false

for (const baselineRef of baselineRefs) {
  const baseline = execFileSync('git', ['show', `${baselineRef}:api/yks.api`], {
    encoding: 'utf8',
  })
  const oldClasses = parseApi(baseline)
  const failures = []

  for (const [name, oldClass] of oldClasses) {
    const newClass = newClasses.get(name)
    if (!newClass) {
      failures.push(`removed class: ${name}`)
      continue
    }
    if (declarationKind(oldClass.header) !== declarationKind(newClass.header)) {
      failures.push(`changed class kind: ${name}`)
    }
    if (!isFinal(oldClass.header) && isFinal(newClass.header)) {
      failures.push(`finalized class: ${name}`)
    }
    if (!isAbstract(oldClass.header) && isAbstract(newClass.header)) {
      failures.push(`made class abstract: ${name}`)
    }
    for (const supertype of supertypes(oldClass.header)) {
      if (!supertypes(newClass.header).includes(supertype)) {
        failures.push(`removed supertype: ${name} : ${supertype}`)
      }
    }

    const newMembers = new Map(newClass.members.map((member) => [canonicalMember(member), member]))
    for (const oldMember of oldClass.members) {
      const key = canonicalMember(oldMember)
      const newMember = newMembers.get(key)
      if (!newMember) {
        failures.push(`removed member: ${name} :: ${key}`)
        continue
      }
      if (!isAbstract(oldMember) && isAbstract(newMember)) {
        failures.push(`made member abstract: ${name} :: ${key}`)
      }
      if (!oldMember.includes(' static ') && !isFinal(oldMember) && isFinal(newMember)) {
        failures.push(`finalized member: ${name} :: ${key}`)
      }
    }
  }

  if (failures.length > 0) {
    failed = true
    console.error(`Release ABI check against ${baselineRef} failed:`)
    for (const failure of failures) console.error(`- ${failure}`)
  } else {
    console.log(`Release ABI check against ${baselineRef} passed (${oldClasses.size} classes).`)
  }
}

if (failed) process.exit(1)
