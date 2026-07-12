package dev.yks

/**
 * Persistently associates Yjs client ids and deletions with application user descriptions.
 *
 * The data layout matches upstream Yjs 13.6.x: the store map contains one map per user, and
 * each user map contains an `ids` array plus a `ds` array of V1-encoded delete sets. Because
 * the attribution data is itself stored in shared types, another document can reconstruct the
 * mappings by applying a normal Yjs update and creating a [PermanentUserData] over that store.
 */
class PermanentUserData(
    val doc: YDoc,
    val yusers: YMap = doc.getMap("users"),
) : AutoCloseable {
    val clients: MutableMap<Long, String> = linkedMapOf()
    val dss: MutableMap<String, DeleteSet> = linkedMapOf()

    private val userBindings = linkedMapOf<String, UserBinding>()
    private val mappingSubscriptions = mutableListOf<Subscription>()
    private val usersSubscription: Subscription
    private var closed = false

    init {
        require(yusers.doc === doc) { "PermanentUserData store must belong to the supplied document" }
        usersSubscription = yusers.observe { event ->
            event.keysChanged.forEach { userDescription ->
                (yusers.get(userDescription) as? YMap)?.let { user -> initUser(user, userDescription) }
            }
        }
        yusers.forEach { value, userDescription ->
            (value as? YMap)?.let { user -> initUser(user, userDescription) }
        }
    }

    /**
     * Associates [clientId] with [userDescription] and records qualifying local deletions.
     */
    fun setUserMapping(
        doc: YDoc,
        clientId: Long,
        userDescription: String,
        filter: (YTransactionEvent, DeleteSet) -> Boolean = { _, _ -> true },
    ) {
        check(!closed) { "PermanentUserData is closed" }
        require(clientId >= 0) { "clientId must be non-negative" }
        val initialUser = getOrCreateUser(userDescription)
        userArray(initialUser, IDS_KEY).push(clientId)

        var currentUser = initialUser
        mappingSubscriptions += yusers.observe {
            val replacement = yusers.get(userDescription) as? YMap ?: return@observe
            if (replacement === currentUser) return@observe
            currentUser = replacement
            val ids = userArray(replacement, IDS_KEY)
            clients.forEach { (mappedClientId, mappedUserDescription) ->
                if (mappedUserDescription == userDescription) ids.push(mappedClientId)
            }
            dss[userDescription]
                ?.takeUnless { deleteSet -> deleteSet.isEmpty }
                ?.let(::encodePermanentDeleteSet)
                ?.let { encoded -> userArray(replacement, DELETE_SETS_KEY).push(encoded) }
        }

        mappingSubscriptions += doc.observeAfterTransactions { transaction ->
            val deleteSet = transaction.deleteSet
            if (!transaction.local || deleteSet.isEmpty || !filter(transaction, deleteSet)) return@observeAfterTransactions
            val user = yusers.get(userDescription) as? YMap ?: return@observeAfterTransactions
            userArray(user, DELETE_SETS_KEY).push(encodePermanentDeleteSet(deleteSet))
        }
    }

    fun getUserByClientId(clientId: Long): String? = clients[clientId]

    fun getUserByDeletedId(id: Id): String? =
        dss.entries.firstOrNull { (_, deleteSet) -> deleteSet.contains(id) }?.key

    override fun close() {
        if (closed) return
        closed = true
        usersSubscription.close()
        userBindings.values.forEach(UserBinding::close)
        userBindings.clear()
        mappingSubscriptions.forEach(Subscription::close)
        mappingSubscriptions.clear()
    }

    private fun getOrCreateUser(userDescription: String): YMap {
        (yusers.get(userDescription) as? YMap)?.let { return it }

        lateinit var user: YMap
        doc.transact {
            user = doc.createMap()
            yusers.set(userDescription, user)
            user.set(IDS_KEY, doc.createArray())
            user.set(DELETE_SETS_KEY, doc.createArray())
        }
        return yusers.get(userDescription) as? YMap
            ?: error("failed to create PermanentUserData entry for '$userDescription'")
    }

    private fun initUser(user: YMap, userDescription: String) {
        val existing = userBindings[userDescription]
        if (existing?.user === user) {
            readClientIds(user, userDescription)
            readDeleteSets(user, userDescription)
            return
        }
        existing?.close()

        readClientIds(user, userDescription)
        readDeleteSets(user, userDescription)
        val ids = userArray(user, IDS_KEY)
        val deleteSets = userArray(user, DELETE_SETS_KEY)
        userBindings[userDescription] = UserBinding(
            user = user,
            idsSubscription = ids.observe { readClientIds(user, userDescription) },
            deleteSetsSubscription = deleteSets.observe { readDeleteSets(user, userDescription) },
        )
    }

    private fun readClientIds(user: YMap, userDescription: String) {
        userArray(user, IDS_KEY).forEach { value ->
            val clientId = (value as? Number)?.toLong() ?: return@forEach
            clients[clientId] = userDescription
        }
    }

    private fun readDeleteSets(user: YMap, userDescription: String) {
        val merged = DeleteSet.empty()
        userArray(user, DELETE_SETS_KEY).forEach { value ->
            if (value is ByteArray) merged.addAll(decodePermanentDeleteSet(value))
        }
        dss[userDescription] = merged
    }

    private fun userArray(user: YMap, key: String): YArray =
        user.get(key) as? YArray ?: error("PermanentUserData user entry is missing '$key' array")

    private data class UserBinding(
        val user: YMap,
        val idsSubscription: Subscription,
        val deleteSetsSubscription: Subscription,
    ) {
        fun close() {
            idsSubscription.close()
            deleteSetsSubscription.close()
        }
    }

    private companion object {
        const val IDS_KEY = "ids"
        const val DELETE_SETS_KEY = "ds"
    }
}

private fun encodePermanentDeleteSet(deleteSet: DeleteSet): ByteArray {
    val encoder = IdSetEncoderV1()
    writeIdSet(encoder, deleteSet.toIdSet())
    return encoder.toUint8Array()
}

private fun decodePermanentDeleteSet(encoded: ByteArray): DeleteSet {
    val decoder = IdSetDecoderV1(encoded)
    val deleteSet = readIdSet(decoder).toDeleteSet()
    check(!decoder.hasRemaining()) { "PermanentUserData delete set has trailing bytes" }
    return deleteSet
}
