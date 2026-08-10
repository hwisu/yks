package dev.yks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XmlAnswerDocStandardInteropTest {
    @Test
    fun `preliminary XML AnswerDoc state is a genuine Yjs update`() {
        val document = YDoc(clientId = 1)
        val paragraph = paragraph("안녕하세요 😀 𠮷")
        document.getXmlFragment("42").pushTypes(listOf(paragraph))

        val update = document.encodeStateAsUpdate()
        val merged = mergeUpdates(listOf(update))
        val restored = createDocFromUpdate(merged)
        mergeUpdates(listOf(update, restored.encodeStateAsUpdate()))

        val client = createDocFromUpdate(update, YDoc(clientId = 2))
        val incremental = mutableListOf<ByteArray>()
        client.onUpdate { value, _, _, _ -> incremental.add(value) }
        client.getXmlFragment("42").also { fragment ->
            fragment.delete(0, fragment.length)
            fragment.pushTypes(listOf(paragraph("Ktor 동기화 𠮷")))
        }
        incremental.forEach(restored::applyUpdate)
        val inputs = listOf(update) + incremental + restored.encodeStateAsUpdate()
        val compacted = mergeUpdates(inputs)
        val compactedDocument = createDocFromUpdate(compacted)

        assertEquals(
            "<paragraph index=\"0\" node_ids=\"node-1\">Ktor 동기화 𠮷</paragraph>",
            compactedDocument.getXmlFragment("42").toString(),
        )
    }

    @Test
    fun `merges upstream Yjs incremental AnswerDoc updates with its full snapshot`() {
        val source = YDoc(clientId = 1)
        val question = YMap(
            linkedMapOf(
                "id" to 42,
                "status" to "IN_PROGRESS",
                "lastAppliedSourceId" to null,
                "assignUser" to listOf("user-1"),
                "answer" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "attrs" to mapOf("index" to 0, "node_ids" to listOf("node-seed")),
                            "content" to listOf(mapOf("type" to "text", "text" to "초기 답변 😀")),
                        ),
                    ),
                ),
                "lastMutationActorId" to null,
                "lastMutationActorName" to null,
                "lastMutationAt" to null,
                "lastMutationId" to null,
            ),
        )
        source.getMap("questions").set("42", question)
        source.getXmlFragment("42").pushTypes(listOf(paragraph("초기 답변 😀", "node-seed")))
        val seedFile = Files.createTempFile("yks-answerdoc-seed-", ".bin")
        try {
            Files.write(seedFile, source.encodeStateAsUpdate())
            val process = ProcessBuilder(
                "node",
                "--no-warnings",
                "interop/yjs-v1/generate-answerdoc-merge-updates.mjs",
                seedFile.toAbsolutePath().toString(),
            )
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val lines = process.inputStream.bufferedReader().readLines()
            assertTrue(process.waitFor() == 0, lines.joinToString("\n"))
            val inputs = lines.filter(String::isNotBlank).map(Base64.getDecoder()::decode)

            listOf(
                inputs,
                inputs.reversed(),
                listOf(inputs.last()) + inputs.dropLast(1),
            ).forEach { orderedInputs ->
                val merged = mergeUpdates(orderedInputs)
                val restored = createDocFromUpdate(merged)
                assertEquals(
                    "IN_REVIEW",
                    assertIs<YMap>(restored.getMap("questions").get("42")).get("status"),
                )
                assertEquals(
                    "<paragraph index=\"0\" node_ids=\"node-updated\">Ktor 동기화 𠮷</paragraph>",
                    restored.getXmlFragment("42").toString(),
                )
            }
        } finally {
            Files.deleteIfExists(seedFile)
        }
    }

    @Test
    fun `merges repeated upstream text edits with a large full snapshot`() {
        val source = YDoc(clientId = 1)
        source.getMap("questions").set(
            "42",
            YMap(linkedMapOf("id" to 42, "status" to "IN_PROGRESS")),
        )
        source.getXmlFragment("42").pushTypes(listOf(paragraph("초기 답변 😀", "node-seed")))
        repeat(120) { index ->
            val questionId = 1_000 + index
            source.getMap("questions").set(
                questionId.toString(),
                YMap(linkedMapOf("id" to questionId, "status" to "IN_PROGRESS")),
            )
            source.getXmlFragment(questionId.toString()).pushTypes(
                listOf(paragraph("답변 $questionId", "node-$questionId")),
            )
        }

        val seedFile = Files.createTempFile("yks-answerdoc-typing-seed-", ".bin")
        try {
            Files.write(seedFile, source.encodeStateAsUpdate())
            val process = ProcessBuilder(
                "node",
                "--no-warnings",
                "interop/yjs-v1/generate-answerdoc-merge-updates.mjs",
                seedFile.toAbsolutePath().toString(),
                "typing",
            )
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val lines = process.inputStream.bufferedReader().readLines()
            assertTrue(process.waitFor() == 0, lines.joinToString("\n"))
            val inputs = lines.filter(String::isNotBlank).map(Base64.getDecoder()::decode)

            val merged = mergeUpdates(inputs)
            val restored = createDocFromUpdate(merged)
            assertEquals(
                "<paragraph index=\"0\" node_ids=\"node-seed\">초기 답변 😀Ktor 수정</paragraph>",
                restored.getXmlFragment("42").toString(),
            )
        } finally {
            Files.deleteIfExists(seedFile)
        }
    }

    @Test
    fun `repeatedly compacts a standard snapshot with later and duplicate upstream updates`() {
        val source = YDoc(clientId = 1)
        source.getMap("questions").set(
            "42",
            YMap(linkedMapOf("id" to 42, "status" to "IN_PROGRESS")),
        )
        source.getXmlFragment("42").pushTypes(listOf(paragraph("initial", "node-seed")))
        var compacted = source.encodeStateAsUpdate()

        repeat(3) { round ->
            val seedFile = Files.createTempFile("yks-answerdoc-repeat-$round-", ".bin")
            try {
                Files.write(seedFile, compacted)
                val process = ProcessBuilder(
                    "node",
                    "--no-warnings",
                    "interop/yjs-v1/generate-answerdoc-merge-updates.mjs",
                    seedFile.toAbsolutePath().toString(),
                    "typing",
                )
                    .directory(Path.of(System.getProperty("user.dir")).toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                val lines = process.inputStream.bufferedReader().readLines()
                assertTrue(process.waitFor() == 0, lines.joinToString("\n"))
                val inputs = lines.filter(String::isNotBlank).map(Base64.getDecoder()::decode)
                val incremental = inputs.drop(1).dropLast(1)

                compacted = mergeUpdates(listOf(compacted) + incremental)
                compacted = mergeUpdates(listOf(compacted))
                compacted = mergeUpdates(listOf(compacted) + incremental + compacted)

                val restored = createDocFromUpdate(compacted)
                assertTrue(restored.getXmlFragment("42").toString().contains("Ktor 수정"))
            } finally {
                Files.deleteIfExists(seedFile)
            }
        }
    }

    private fun paragraph(value: String, nodeId: String = "node-1") = YXmlElementType("paragraph").also { element ->
        element.setAttribute("index", 0)
        element.setAttribute("node_ids", listOf(nodeId))
        element.pushTypes(
            listOf(YXmlTextType().also { text -> text.insert(0, value) }),
        )
    }
}
