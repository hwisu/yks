@file:OptIn(ExperimentalYjs14Api::class)

package dev.yks.experimental.v14

import dev.yks.AbstractRenderer
import dev.yks.YDoc
import dev.yks.YSchema
import dev.yks.YTypeSchema
import dev.yks.namedTypeSchema
import dev.yks.predicateSchema
import dev.yks.rendererSchema
import dev.yks.ydocSchema

/**
 * Typed schema-marker spellings for the pinned @y/y 14 compatibility surface.
 *
 * They live behind the v14 opt-in so the stable JVM ABI can retain its historical function and
 * string aliases until a major release.
 */
@ExperimentalYjs14Api
public object Yjs14SchemaMarkers {
    @JvmField
    public val `$ydoc`: YTypeSchema<YDoc> = ydocSchema

    @JvmField
    public val `$ytypeAny`: YSchema<Type> =
        predicateSchema("an experimental v14 Type instance") { value -> value is Type }

    public fun `$ytype`(): YSchema<Type> = `$ytypeAny`

    @JvmField
    public val `$renderer`: YTypeSchema<AbstractRenderer> = rendererSchema
}
