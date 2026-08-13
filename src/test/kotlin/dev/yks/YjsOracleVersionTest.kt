package dev.yks

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("yjs-interop")
class YjsOracleVersionTest {
    @Test
    fun installedOracleMatchesTheExactPackagePin() {
        val projectDirectory = Path.of(System.getProperty("yks.projectDirectory", System.getProperty("user.dir")))
        val process = ProcessBuilder("node", "interop/yjs-v1/assert-yjs-version.mjs")
            .directory(projectDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(process.waitFor() == 0, output)
        assertEquals("13.6.32", installedYjsVersion(projectDirectory))
    }

    private fun installedYjsVersion(projectDirectory: Path): String {
        val packageJson = projectDirectory.resolve("node_modules/yjs/package.json").toFile().readText()
        return checkNotNull(Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(packageJson))
            .groupValues[1]
    }
}
