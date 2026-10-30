package ai.yaps.support

import java.io.File

/** Reads `protocol/fixtures/` (wired in via the `yaps.fixturesDir` system property in build.gradle.kts). */
object Fixtures {
    val dir: File by lazy {
        val path = System.getProperty("yaps.fixturesDir")
            ?: error("yaps.fixturesDir is not set; run the tests through Gradle")
        File(path).also { require(it.isDirectory) { "Fixtures directory not found: $it" } }
    }

    fun read(name: String): String = File(dir, name).readText()

    fun all(): List<File> = dir.listFiles().orEmpty().filter { it.isFile && (it.extension == "json" || it.extension == "jsonl") }.sortedBy { it.name }
}
