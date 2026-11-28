package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `AutoPlacementScene` overload that forwards to another must pass at least one argument
 * its own parameter list does not have. Forwarding only its own parameters, by name, resolves to
 * itself (Kotlin prefers the candidate that leaves the fewest parameters at their default) and
 * recurses until the stack overflows: the Wall demo crashed so on the Pixel 4a (#5262).
 */
class AutoPlacementSceneForwardingTest {

    private val source = File("src/main/java/io/github/sceneview/ar/AutoPlacementScene.kt").readText()

    @Test
    fun `no overload forwards to itself`() {
        // Each group stops at the first line that closes a list: the main overload, whose body is a
        // block (`) {`), never matches.
        val list = """((?:(?!\n\)).)*)"""
        val overloads = Regex("""\nfun AutoPlacementScene\($list\n\): Unit = AutoPlacementScene\($list\n\)""",
            RegexOption.DOT_MATCHES_ALL).findAll(source).toList()
        assertTrue("expected the forwarding overloads, found ${overloads.size}", overloads.size >= 4)
        for (overload in overloads) {
            val own = Regex("""^\s{4}(\w+):""", RegexOption.MULTILINE)
                .findAll(overload.groupValues[1]).map { it.groupValues[1] }.toSet()
            val forwarded = Regex("""^\s{4}(\w+) =""", RegexOption.MULTILINE)
                .findAll(overload.groupValues[2]).map { it.groupValues[1] }.toSet()
            assertTrue("parameters not read from $overload", own.isNotEmpty() && forwarded.isNotEmpty())
            assertTrue(
                "overload with ${own.sorted()} forwards only its own parameters: it calls itself",
                (forwarded - own).isNotEmpty(),
            )
        }
    }
}
