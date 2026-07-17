package dev.yks

/** Base type for failures reported by the YKS public boundary. */
public open class YksException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Malformed or unsupported bytes were rejected while decoding an external payload. */
public class YksDecodingException(
    public val format: String,
    cause: Throwable,
) : YksException(
    message = "failed to decode $format: ${cause.message ?: cause::class.simpleName}",
    cause = cause,
)

/** A configured update resource limit was exceeded before document mutation began. */
public class YksUpdateLimitException(
    public val limit: String,
    public val maximum: Long,
    public val actual: Long,
) : YksException("update $limit exceeds limit $maximum: $actual")

/** A mutable document was accessed from a thread other than its owning thread. */
public class YksThreadConfinementException(
    public val ownerThreadName: String,
    public val currentThreadName: String,
) : YksException(
    "YDoc is confined to thread '$ownerThreadName' and cannot be accessed from '$currentThreadName'",
)

/** Concurrent access was rejected for a document whose caller promised external serialization. */
public class YksConcurrentAccessException(
    public val activeThreadName: String,
    public val currentThreadName: String,
) : YksException(
    "YDoc is already in use by '$activeThreadName' and cannot be accessed concurrently from '$currentThreadName'",
)

/** Immutable, per-document limits applied before integrating an update. */
public data class YUpdateLimits(
    val maxEncodedBytes: Int = 16 * 1024 * 1024,
    val maxStructs: Int = 50_000,
    val maxDeleteRanges: Int = 50_000,
) {
    init {
        require(maxEncodedBytes > 0) { "maxEncodedBytes must be positive" }
        require(maxStructs > 0) { "maxStructs must be positive" }
        require(maxStructs <= MAX_DECODED_COLLECTION_SIZE) {
            "maxStructs cannot exceed decoder maximum $MAX_DECODED_COLLECTION_SIZE"
        }
        require(maxDeleteRanges > 0) { "maxDeleteRanges must be positive" }
        require(maxDeleteRanges <= MAX_DECODED_COLLECTION_SIZE) {
            "maxDeleteRanges cannot exceed decoder maximum $MAX_DECODED_COLLECTION_SIZE"
        }
    }

    internal fun requireEncodedSize(size: Int) {
        if (size > maxEncodedBytes) {
            throw YksUpdateLimitException("encoded byte size", maxEncodedBytes.toLong(), size.toLong())
        }
    }

    internal fun requireStructCount(count: Int) {
        if (count > maxStructs) {
            throw YksUpdateLimitException("struct count", maxStructs.toLong(), count.toLong())
        }
    }

    internal fun requireDeleteRangeCount(count: Int) {
        if (count > maxDeleteRanges) {
            throw YksUpdateLimitException("delete range count", maxDeleteRanges.toLong(), count.toLong())
        }
    }

    public companion object {
        @JvmField
        public val DEFAULT: YUpdateLimits = YUpdateLimits()
    }
}

/** Immutable JVM runtime policies kept separate from Yjs-compatible document options. */
public data class YDocRuntimeOptions(
    val updateLimits: YUpdateLimits = YUpdateLimits.DEFAULT,
    val threadAccessPolicy: YThreadAccessPolicy = YThreadAccessPolicy.ENFORCED,
    val standardUpdatePolicy: YStandardUpdatePolicy = YStandardUpdatePolicy.ALLOW_LOSSLESS_EXTENSIONS,
) {
    public companion object {
        @JvmField
        public val DEFAULT: YDocRuntimeOptions = YDocRuntimeOptions()
    }
}

/** Controls whether CRDT operations enforce YDoc's single-thread ownership at runtime. */
public enum class YThreadAccessPolicy {
    /** Bind lazily on first CRDT access and reject access from every other thread. */
    ENFORCED,

    /**
     * Allow serialized calls to resume on different threads while rejecting overlapping access.
     *
     * This is the coroutine/server integration mode. The caller still owns serialization; YKS
     * detects violations instead of silently racing mutable CRDT state.
     */
    EXTERNALLY_SERIALIZED,

    /** Skip runtime checks; the caller remains responsible for serializing every access. */
    UNCHECKED,
}

/** Controls whether local transactions may contain Kotlin-only lossless extensions. */
public enum class YStandardUpdatePolicy {
    /** Allow extensions unless a standard update listener makes a standard wire update mandatory. */
    ALLOW_LOSSLESS_EXTENSIONS,

    /** Atomically reject every local transaction that cannot be represented as a standard Yjs update. */
    REQUIRE_STANDARD,
}

internal class DecodedCountBudget(
    private val limit: String,
    private val maximum: Int,
) {
    private var count: Int = 0

    fun consume(amount: Int) {
        check(amount >= 0) { "decoded count must be non-negative" }
        if (amount > maximum - count) {
            throw YksUpdateLimitException(limit, maximum.toLong(), count.toLong() + amount)
        }
        count += amount
    }
}

internal class DecodedUpdateBudget(maxStructs: Int, maxDeleteRanges: Int) {
    val structs: DecodedCountBudget = DecodedCountBudget("struct count", maxStructs)
    val deleteRanges: DecodedCountBudget = DecodedCountBudget("delete range count", maxDeleteRanges)
}

internal inline fun <T> decodeBoundary(format: String, block: () -> T): T = try {
    block()
} catch (error: YksException) {
    throw error
} catch (error: RuntimeException) {
    throw YksDecodingException(format, error)
}
