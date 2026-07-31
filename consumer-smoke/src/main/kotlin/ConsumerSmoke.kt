import dev.yks.YDoc
import dev.yks.YMap
import dev.yks.applyUpdate
import dev.yks.encodeStateAsUpdate
import java.io.File
import java.util.zip.ZipFile

fun main() {
    val source = YDoc(clientId = 1, gc = false)
    source.getText("body").insert(0, "hello from a standalone consumer")
    val question = source.createMap()
    source.getMap("questions").set("42", question)
    question.set("id", 42)
    question.set("assignUser", listOf("user-1", "사용자-😀"))
    val paragraph = source.createXmlElement("paragraph")
    source.getXmlFragment("42").push(paragraph)
    paragraph.setAttr("node_ids", listOf("n4"))
    val answer = source.createXmlText()
    paragraph.push(answer)
    answer.insert(0, "저장된 답변 😀")

    val target = YDoc(clientId = 2, gc = false)
    applyUpdate(target, encodeStateAsUpdate(source))

    check(target.getText("body").toString() == "hello from a standalone consumer") {
        "Published YKS artifact failed a cross-document update roundtrip."
    }
    check((target.getMap("questions").get("42") as YMap).get("assignUser") == listOf("user-1", "사용자-😀")) {
        "Published YKS artifact failed a nested application-map roundtrip."
    }
    check("저장된 답변 😀" in target.getXmlFragment("42").toString()) {
        "Published YKS artifact failed a Tiptap-shaped XML roundtrip."
    }

    val artifact = File(YDoc::class.java.protectionDomain.codeSource.location.toURI())
    check(artifact.isFile && artifact.extension == "jar") {
        "YKS must be consumed from a published JAR, got: $artifact"
    }
    ZipFile(artifact).use { archive ->
        val license = checkNotNull(archive.getEntry("META-INF/LICENSE")) {
            "Published YKS JAR is missing META-INF/LICENSE."
        }
        val licenseText = archive.getInputStream(license).bufferedReader().use { it.readText() }
        check("MIT License" in licenseText && "Copyright (c) 2026 hwisu" in licenseText) {
            "Published YKS JAR contains an unexpected license."
        }

        val notices = checkNotNull(archive.getEntry("META-INF/THIRD_PARTY_NOTICES")) {
            "Published YKS JAR is missing META-INF/THIRD_PARTY_NOTICES."
        }
        val noticesText = archive.getInputStream(notices).bufferedReader().use { it.readText() }
        check(
            "Yjs" in noticesText &&
                "Copyright (c) 2023" in noticesText &&
                "lib0" in noticesText &&
                "Copyright (c) 2019 Kevin Jahns" in noticesText &&
                "MIT" in noticesText,
        ) {
            "Published YKS JAR is missing the upstream Yjs/lib0 attribution."
        }
    }
}
