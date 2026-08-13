package dev.yks

import java.util.Collections

/** Application metadata for root details that are absent from the Yjs update wire format. */
public sealed interface YRootSchema {
    public val kind: RootKind

    public data object Array : YRootSchema {
        override val kind: RootKind = RootKind.Array
    }

    public data object Map : YRootSchema {
        override val kind: RootKind = RootKind.Map
    }

    public data object Text : YRootSchema {
        override val kind: RootKind = RootKind.Text
    }

    public data object XmlFragment : YRootSchema {
        override val kind: RootKind = RootKind.XmlFragment
    }

    public data class XmlElement(public val nodeName: String) : YRootSchema {
        init {
            require(nodeName.isNotBlank()) { "XML element root nodeName must not be blank" }
        }

        override val kind: RootKind = RootKind.XmlElement
    }

    public data class XmlHook(public val hookName: String) : YRootSchema {
        init {
            require(hookName.isNotBlank()) { "XML hook root hookName must not be blank" }
        }

        override val kind: RootKind = RootKind.XmlHook
    }

    public data object XmlText : YRootSchema {
        override val kind: RootKind = RootKind.XmlText
    }
}

/** Typed fallback for applications whose root layout is resolved dynamically. */
public fun interface YRootSchemaResolver {
    public fun resolve(rootName: String): YRootSchema?
}

/** Immutable named schemas plus an optional resolver. Resolutions are cached by each [YDoc]. */
public class YRootSchemaRegistry(
    schemas: Map<String, YRootSchema> = emptyMap(),
    public val resolver: YRootSchemaResolver? = null,
) {
    public val schemas: Map<String, YRootSchema> = Collections.unmodifiableMap(LinkedHashMap(schemas))

    public fun resolve(rootName: String): YRootSchema? = schemas[rootName] ?: resolver?.resolve(rootName)

    public operator fun get(rootName: String): YRootSchema? = resolve(rootName)

    public fun withSchema(rootName: String, schema: YRootSchema): YRootSchemaRegistry =
        YRootSchemaRegistry(LinkedHashMap(schemas).also { entries -> entries[rootName] = schema }, resolver)

    public companion object {
        @JvmField
        public val EMPTY: YRootSchemaRegistry = YRootSchemaRegistry()
    }
}

/** A configured schema disagrees with concrete root metadata already known to the document. */
public class YRootSchemaConflictException(
    public val rootName: String,
    detail: String,
) : YksException("root schema conflict for '$rootName': $detail")
