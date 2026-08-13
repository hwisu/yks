package dev.yks

/**
 * A typed runtime schema predicate.
 *
 * This mirrors the part of lib0's `Schema<T>` contract used by Yjs 14 markers while remaining a
 * Kotlin function for source and binary compatibility with the original YKS marker predicates.
 */
public interface YSchema<T> : (Any?) -> Boolean {
    /** Human-readable schema description used by validation failures. */
    public val description: String

    /** Returns true when [value] belongs to this schema. */
    public fun check(value: Any?): Boolean

    /** Typed validation for a value already believed to be [T]. */
    public fun validate(value: T): Boolean = check(value)

    /** Checks and narrows an untyped value to [T]. */
    public fun expect(value: Any?): T {
        require(check(value)) { "value does not match $description" }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Alias matching lib0 schema's checked cast operation. */
    public fun cast(value: Any?): T = expect(value)

    override fun invoke(value: Any?): Boolean = check(value)
}

/** A globally named `$type` schema such as Yjs 14's `y:doc` and `y:r` markers. */
public interface YTypeSchema<T> : YSchema<T> {
    public val name: String

    override val description: String get() = "schema '$name'"
}

private class PredicateSchema<T>(
    override val description: String,
    private val predicate: (Any?) -> Boolean,
) : YSchema<T> {
    override fun check(value: Any?): Boolean = predicate(value)

    override fun toString(): String = description
}

private class NamedTypeSchema<T>(
    override val name: String,
    private val predicate: (Any?) -> Boolean,
) : YTypeSchema<T> {
    override fun check(value: Any?): Boolean = predicate(value)

    override fun toString(): String = description
}

internal fun <T> predicateSchema(description: String, predicate: (Any?) -> Boolean): YSchema<T> =
    PredicateSchema(description, predicate)

internal fun <T> namedTypeSchema(name: String, predicate: (Any?) -> Boolean): YTypeSchema<T> =
    NamedTypeSchema(name, predicate)

/** Typed form of Yjs 14's globally named `$ydoc` marker. */
public val ydocSchema: YTypeSchema<YDoc> = namedTypeSchema("y:doc") { value -> value is YDoc }

/** Typed runtime schema for stable YKS shared types. */
public val ytypeAnySchema: YSchema<AbstractYType> =
    predicateSchema("an AbstractYType instance") { value -> value is AbstractYType }

/** Typed form of Yjs 14's globally named `$renderer` marker. */
public val rendererSchema: YTypeSchema<AbstractRenderer> =
    namedTypeSchema(rendererType) { value -> value is AbstractRenderer }
