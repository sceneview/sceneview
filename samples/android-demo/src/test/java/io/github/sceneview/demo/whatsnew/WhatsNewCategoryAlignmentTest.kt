package io.github.sceneview.demo.whatsnew

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo's `BundleChangelogAsset` task (`samples/android-demo/build.gradle`) synthesises the
 * bundled `## Unreleased` section from the `changelog.d/` fragments with its own copy of the
 * release collator's category rules (`.claude/scripts/collate-changelog.sh`). The copy has to
 * match the collator, or an installed build files a fragment under another heading than the
 * release notes will: a `<!-- category: Security -->` fragment used to read as `Changed` in the
 * app until the release, then as `Security` once collated.
 *
 * Both files are read as text: the Gradle task class is not on the test classpath and the
 * collator is a shell script.
 */
class WhatsNewCategoryAlignmentTest {

    private val repoRoot = listOf(File("../.."), File("."))
        .first { File(it, GRADLE_SCRIPT).isFile }

    private val gradle = File(repoRoot, GRADLE_SCRIPT).readText()
    private val collator = File(repoRoot, COLLATOR).readText()

    @Test
    fun `the bundle task renders the collator's categories in the collator's order`() {
        val collatorOrder = Regex("""(?m)^CATEGORIES=\(([^)]*)\)""").find(collator)
            ?.groupValues?.get(1)?.trim()?.split(Regex("""\s+""")).orEmpty()
        val gradleOrder = Regex("""List<String>\s+CATEGORIES\s*=\s*\[([^\]]*)]""").find(gradle)
            ?.groupValues?.get(1)
            ?.let { list -> QUOTED.findAll(list).map { it.groupValues[1] }.toList() }
            .orEmpty()

        assertTrue("CATEGORIES=(...) not found in $COLLATOR", collatorOrder.isNotEmpty())
        assertEquals("BundleChangelogAsset.CATEGORIES in $GRADLE_SCRIPT", collatorOrder, gradleOrder)
    }

    @Test
    fun `the bundle task files every category tag where the collator does`() {
        val collatorRules = collatorRules()
        val gradleRules = gradleRules()

        assertTrue("canonical_category arms not found in $COLLATOR", collatorRules.aliases.isNotEmpty())
        assertTrue("canonicalCategory cases not found in $GRADLE_SCRIPT", gradleRules.aliases.isNotEmpty())
        assertNotNull("fallback arm not found in $COLLATOR", collatorRules.fallback)
        assertEquals("fallback bucket", collatorRules.fallback, gradleRules.fallback)

        // Compared on the union of explicit tags, so an alias only one side spells out is still
        // checked against the other side's fallback.
        val disagreements = (collatorRules.aliases.keys + gradleRules.aliases.keys).sorted()
            .associateWith { tag -> collatorRules.resolve(tag) to gradleRules.resolve(tag) }
            .filterValues { (fromCollator, fromGradle) -> fromCollator != fromGradle }
        assertEquals(
            "tag -> (collator bucket, BundleChangelogAsset bucket)",
            emptyMap<String, Pair<String?, String?>>(),
            disagreements,
        )
    }

    private class CategoryRules(val aliases: Map<String, String>, val fallback: String?) {
        fun resolve(tag: String): String? = aliases[tag] ?: fallback
    }

    /** `canonical_category()`'s `case` arms, e.g. `fixed|fix|bugfix) echo "Fixed" ;;`. */
    private fun collatorRules(): CategoryRules {
        val body = collator.substringAfter("canonical_category() {", "").substringBefore("\n}")
        val aliases = mutableMapOf<String, String>()
        var fallback: String? = null
        for (arm in Regex("(?m)^\\s*([a-z|*]+)\\)\\s*echo\\s+\"(\\w+)\"").findAll(body)) {
            val (alternatives, bucket) = arm.destructured
            for (tag in alternatives.split('|')) {
                if (tag == "*") fallback = bucket else aliases[tag] = bucket
            }
        }
        return CategoryRules(aliases, fallback)
    }

    /** `canonicalCategory()`'s `switch`, e.g. `case ['fixed', 'fix', 'bugfix']: return 'Fixed'`. */
    private fun gradleRules(): CategoryRules {
        val body = gradle.substringAfter("String canonicalCategory(", "")
        val aliases = mutableMapOf<String, String>()
        val cases = Regex("""case\s*\[([^\]]*)]\s*:\s*return\s*'(\w+)'""")
        for (arm in cases.findAll(body.substringBefore("default"))) {
            val (alternatives, bucket) = arm.destructured
            for (tag in QUOTED.findAll(alternatives)) aliases[tag.groupValues[1]] = bucket
        }
        val fallback = Regex("""default\s*:\s*return\s*'(\w+)'""").find(body)?.groupValues?.get(1)
        return CategoryRules(aliases, fallback)
    }

    private companion object {
        const val GRADLE_SCRIPT = "samples/android-demo/build.gradle"
        const val COLLATOR = ".claude/scripts/collate-changelog.sh"
        val QUOTED = Regex("""'(\w+)'""")
    }
}
