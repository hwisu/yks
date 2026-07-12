package dev.yks

import kotlin.test.Test
import kotlin.test.assertEquals

class YMapOrderTest {
    @Test
    fun iterationPreservesFirstKeyInsertionAcrossUpdatesDeletesAndReinsertion() {
        val map = YDoc(clientId = 1, gc = false).getMap("ordered")
        map.set("z", 1)
        map.set("a", 2)
        map.set("m", 3)

        assertEquals(listOf("z", "a", "m"), map.keys().toList())
        assertEquals(listOf("z", "a", "m"), map.toMap().keys.toList())
        assertEquals(listOf("z", "a", "m"), map.entries().map { entry -> entry.key })
        assertEquals(listOf("z", "a", "m"), map.map { entry -> entry.key })

        val visited = mutableListOf<String>()
        map.forEach { _, key -> visited.add(key) }
        assertEquals(listOf("z", "a", "m"), visited)

        map.set("z", 10)
        map.delete("a")
        assertEquals(listOf("z", "m"), map.keys().toList())

        map.set("a", 20)
        assertEquals(listOf("z", "a", "m"), map.keys().toList())
        assertEquals(listOf(10L, 20L, 3L), map.values().toList())
    }

    @Test
    fun toJSONUsesJavaScriptObjectPropertyOrderWithoutChangingMapIterationOrder() {
        val map = YDoc(clientId = 1).getMap("ordered")
        listOf("10", "2", "01", "1", "4294967295", "4294967294", "b", "0", "00")
            .forEach { key -> map.set(key, key) }

        assertEquals(
            listOf("10", "2", "01", "1", "4294967295", "4294967294", "b", "0", "00"),
            map.keys().toList(),
        )
        val objectOrder = listOf("0", "1", "2", "10", "4294967294", "01", "4294967295", "b", "00")
        assertEquals(objectOrder, map.toJSON().keys.toList())
        assertEquals(objectOrder, map.toJson().keys.toList())
    }

    @Test
    fun nestedMapOrderSurvivesAStandardFullStateRelay() {
        val source = YDoc(clientId = 1, gc = false)
        val root = source.getMap("root")
        val nested = source.createMap()
        root.set("child", nested)
        nested.set("z", 1)
        nested.set("a", 2)
        nested.set("m", 3)

        val target = YDoc(clientId = 2, gc = false)
        target.applyUpdate(source.encodeStateAsUpdate())
        val remoteNested = target.getMap("root").get("child") as YMap

        assertEquals(listOf("z", "a", "m"), remoteNested.keys().toList())
        assertEquals(listOf("z", "a", "m"), remoteNested.toMap().keys.toList())
        assertEquals(listOf("z", "a", "m"), remoteNested.toJSON().keys.toList())
    }
}
