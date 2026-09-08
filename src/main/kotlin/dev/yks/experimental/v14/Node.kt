@file:OptIn(ExperimentalYjs14Api::class)

package dev.yks.experimental.v14

import dev.yks.AbstractYType
import dev.yks.RootKind
import dev.yks.YDoc

/** The rc.25+ Node spelling; the existing Type JVM class and ABI remain available. */
@ExperimentalYjs14Api
public typealias Node = Type

/** Open a node using an explicit legacy root projection. */
@ExperimentalYjs14Api
public fun YDoc.getNode(name: String, kind: RootKind): Node = getType(name, kind)

/** Expose a concrete shared type using the rc.25+ Node spelling. */
@ExperimentalYjs14Api
public fun AbstractYType.asV14Node(): Node = asV14Type()
