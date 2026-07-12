import dev.yks.YDoc
import dev.yks.applyUpdate
import dev.yks.encodeStateAsUpdate

fun main() {
    val source = YDoc(clientId = 1, gc = false)
    source.getText("body").insert(0, "hello from a standalone consumer")

    val target = YDoc(clientId = 2, gc = false)
    applyUpdate(target, encodeStateAsUpdate(source))

    check(target.getText("body").toString() == "hello from a standalone consumer") {
        "Published YKS artifact failed a cross-document update roundtrip."
    }
}
