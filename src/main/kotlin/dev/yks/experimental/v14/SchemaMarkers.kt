@file:OptIn(ExperimentalYjs14Api::class)

package dev.yks.experimental.v14

import dev.yks.AbstractRenderer
import dev.yks.YDoc
import dev.yks.YSchema
import dev.yks.YTypeSchema
import dev.yks.namedTypeSchema
import dev.yks.predicateSchema
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
    public val `$doc`: YTypeSchema<YDoc> = ydocSchema

    @JvmField
    public val `$nodeAny`: YTypeSchema<Node> = namedTypeSchema("y:node") { value -> value is Node }

    /** Check nominal node identity and its immutable name, as upstream rc.26 does. */
    public fun `$node`(name: String? = null): YSchema<Node> =
        predicateSchema("an experimental v14 Node named '$name'") { value ->
            value is Node && (name == null || value.name == name)
        }

    @JvmField
    public val `$ydoc`: YTypeSchema<YDoc> = ydocSchema

    @JvmField
    public val `$ytypeAny`: YSchema<Type> =
        predicateSchema("an experimental v14 Type instance") { value -> value is Type }

    public fun `$ytype`(): YSchema<Type> = `$ytypeAny`

    @JvmField
    public val `$renderer`: YTypeSchema<AbstractRenderer> =
        namedTypeSchema("y:renderer") { value -> value is AbstractRenderer }
}
