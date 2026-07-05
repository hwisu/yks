package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YArrayDeltaTest {
    @Test
    fun sliceSupportsYjsStyleNegativeEndAndAliases() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.insert(0, listOf(1, 2, 3))

        assertEquals(listOf(1L, 2L, 3L), array.slice(0))
        assertEquals(listOf(2L, 3L), array.slice(1))
        assertEquals(listOf(1L, 2L), array.slice(0, -1))

        array.unshift(listOf(0))

        assertEquals(listOf(0L, 1L, 2L, 3L), array.toArray())
        assertEquals(listOf(0L, 1L), array.slice(0, 2))
    }

    @Test
    fun toDeltaRendersArrayContentAsSingleInsert() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push(listOf(1, true, null, "x"))

        assertEquals(
            listOf(YArrayDeltaOp(insert = listOf(1L, true, null, "x"))),
            array.toDelta(),
        )
    }

    @Test
    fun applyDeltaSupportsRetainDeleteAndInsert() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push(listOf("a", "b", "d"))

        array.applyDelta(
            listOf(
                YArrayDeltaOp(retain = 1),
                YArrayDeltaOp(delete = 1),
                YArrayDeltaOp(insert = listOf("B", "c")),
            ),
        )

        assertEquals(listOf("a", "B", "c", "d"), array.toArray())
    }

    @Test
    fun applyDeltaUsesActiveRendererForArrayIndexes() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        array.push(listOf("a", "b", "c"))
        array.useRenderer(renderer)

        array.applyDelta(listOf(
            YArrayDeltaOp(retain = 1),
            YArrayDeltaOp(insert = listOf("!")),
            YArrayDeltaOp(delete = 1),
        ))

        assertEquals(listOf("a", "b", "!"), array.toArray())
    }

    @Test
    fun applyDeltaRendererArgumentOverridesArrayActiveRenderer() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        val renderer = object : BaseRenderer() {
            override val attributed: IdSet = createIdSet().also { it.add(1, 0, 1) }

            override fun contentLength(item: ItemStruct): Long =
                if (item.id == Id(1, 0)) 0 else super.contentLength(item)
        }
        array.push(listOf("a", "b"))
        array.useRenderer(renderer)

        array.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(insert = listOf("!"))), renderer = baseRenderer)

        assertEquals(listOf("a", "!", "b"), array.toArray())
    }

    @Test
    fun arrayMapAndForEachUseVisibleContent() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")
        array.push(listOf(1, 2, 3))
        array.delete(1)

        assertEquals(listOf(2L, 6L), array.map { (it as Long) * 2 })

        val seen = mutableListOf<Any?>()
        array.forEach { seen.add(it) }
        assertEquals(listOf<Any?>(1L, 3L), seen)
    }

    @Test
    fun arrayLengthStaysConsistentAcrossDeleteInsertCyclesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")

        array.push(listOf(0, 1, 2, 3))
        array.delete(0)
        array.insert(0, listOf(0))
        assertEquals(array.toArray().size, array.length)

        doc.transact {
            array.delete(1)
            assertEquals(array.toArray().size, array.length)
            array.insert(1, listOf(1))
            assertEquals(array.toArray().size, array.length)
            array.delete(2)
            assertEquals(array.toArray().size, array.length)
            array.insert(2, listOf(2))
            assertEquals(array.toArray().size, array.length)
        }

        assertEquals(array.toArray().size, array.length)
        array.delete(1)
        assertEquals(array.toArray().size, array.length)
        array.insert(1, listOf(1))
        assertEquals(array.toArray().size, array.length)
        assertEquals(listOf(0L, 1L, 2L, 3L), array.toArray())
    }

    @Test
    fun arrayNestedTransactionsKeepLengthBoundsLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("")

        doc.transact {
            array.insert(0, listOf("group2"))
        }
        doc.transact {
            array.insert(1, listOf("rectangle3"))
        }
        doc.transact {
            array.delete(0)
            array.insert(0, listOf("rectangle3"))
        }
        array.delete(1)
        doc.transact {
            array.insert(1, listOf("ellipse4"))
        }
        doc.transact {
            array.insert(2, listOf("ellipse3"))
        }
        doc.transact {
            array.insert(3, listOf("ellipse2"))
        }
        doc.transact {
            doc.transact {
                assertFailsWith<IllegalArgumentException> {
                    array.insert(5, listOf("rectangle2"))
                }
                array.insert(4, listOf("rectangle2"))
            }
            doc.transact {
                array.delete(4)
            }
        }

        assertEquals(listOf("rectangle3", "ellipse4", "ellipse3", "ellipse2"), array.toArray())
        assertEquals(array.toArray().size, array.length)
    }

    @Test
    fun concurrentArrayInsertDeleteWithThreeConflictsConvergesLikeUpstream() {
        val users = (1L..3L).map { clientId -> YDoc(clientId = clientId) }
        val arrays = users.map { doc -> doc.getArray("array") }

        arrays[0].insert(0, listOf("x", "y", "z"))
        syncAll(users)

        arrays[0].insert(1, listOf(0))
        arrays[1].delete(0)
        arrays[1].delete(1)
        arrays[2].insert(1, listOf(2))
        syncAll(users)

        val expected = arrays.first().toArray()
        arrays.forEach { array ->
            assertEquals(expected, array.toArray())
        }
        assertEquals(listOf(0L, 2L, "y"), expected)
    }

    @Test
    fun arrayInsertionsInLateSyncConvergeLikeUpstream() {
        val users = (1L..3L).map { clientId -> YDoc(clientId = clientId) }
        val arrays = users.map { doc -> doc.getArray("array") }

        arrays[0].insert(0, listOf("x", "y"))
        syncAll(users)

        arrays[0].insert(1, listOf("user0"))
        arrays[1].insert(1, listOf("user1"))
        arrays[2].insert(1, listOf("user2"))
        syncAll(users)

        val expected = listOf("x", "user0", "user1", "user2", "y")
        arrays.forEach { array ->
            assertEquals(expected, array.toArray())
        }
    }

    @Test
    fun arrayDeletionsInLateSyncConvergeLikeUpstream() {
        val users = (1L..2L).map { clientId -> YDoc(clientId = clientId) }
        val arrays = users.map { doc -> doc.getArray("array") }

        arrays[0].insert(0, listOf("x", "y"))
        syncAll(users)

        arrays[1].delete(1)
        arrays[0].delete(0, 2)
        syncAll(users)

        arrays.forEach { array ->
            assertEquals(emptyList(), array.toArray())
        }
    }

    @Test
    fun arrayInsertThenMergeDeleteOnSyncConvergesLikeUpstream() {
        val users = (1L..2L).map { clientId -> YDoc(clientId = clientId) }
        val arrays = users.map { doc -> doc.getArray("array") }

        arrays[0].insert(0, listOf("x", "y", "z"))
        syncAll(users)

        arrays[1].delete(0, 3)
        syncAll(users)

        arrays.forEach { array ->
            assertEquals(emptyList(), array.toArray())
        }
    }

    @Test
    fun iteratingArrayContainingSharedTypesReturnsLiveNestedValuesLikeUpstream() {
        val doc = YDoc(clientId = 1)
        val array = doc.getArray("array")

        repeat(10) { index ->
            val map = doc.createMap()
            map.setAttr("value", index)
            array.push(map)
        }

        array.forEachIndexed { index, value ->
            val map = value as YMap
            assertEquals(index.toLong(), map.getAttr("value"))
        }
    }

    @Test
    fun arrayDeltaConvergesThroughUpdatesAndUndo() {
        val left = YDoc(clientId = 1)
        val right = YDoc(clientId = 2)
        val array = left.getArray("array")
        val undoManager = UndoManager(array, UndoManagerOptions(captureTimeoutMillis = 0))

        array.applyDelta(listOf(YArrayDeltaOp(insert = listOf("a", "b", "c"))))
        array.applyDelta(listOf(YArrayDeltaOp(retain = 1), YArrayDeltaOp(delete = 1), YArrayDeltaOp(insert = listOf("B"))))

        right.applyUpdate(left.encodeStateAsUpdate())

        assertEquals(listOf("a", "B", "c"), right.getArray("array").toArray())

        undoManager.undo()
        assertEquals(listOf("a", "b", "c"), array.toArray())

        undoManager.redo()
        assertEquals(listOf("a", "B", "c"), array.toArray())
    }

    private fun syncAll(users: List<YDoc>) {
        val updates = users.map { doc -> doc.encodeStateAsUpdate() }
        users.forEach { target ->
            updates.forEach { update -> target.applyUpdate(update) }
        }
    }
}
