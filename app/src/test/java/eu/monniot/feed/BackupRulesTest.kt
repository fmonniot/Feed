package eu.monniot.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Ticket #20: [app/src/main/res/xml/data_extraction_rules.xml] and its API<31 sibling
 * [app/src/main/res/xml/backup_rules.xml] must carry explicit backup rules (no leftover
 * wizard-scaffold TODO) that exclude the session-auth DataStore
 * (`datastore/ktor_session_cookies.preferences_pb`, plus its atomic-write scratch sibling)
 * from both cloud backup and device transfer, so a restored/transferred backup can't carry
 * a live login cookie.
 *
 * The exclude assertions parse the XML with [DocumentBuilderFactory] rather than matching
 * raw substrings: this ignores comments for free (a commented-out `<exclude>` no longer
 * passes) and forces the parent element to actually exist (a missing/renamed `<cloud-backup>`
 * tag fails instead of silently passing off a sibling block). The files are read directly off
 * disk — the JUnit `Test` task's working directory is the `app/` module root — so no Android
 * runtime is needed.
 */
class BackupRulesTest {

    private val dataExtractionRulesFile = File("src/main/res/xml/data_extraction_rules.xml")
    private val backupRulesFile = File("src/main/res/xml/backup_rules.xml")

    /** The DataStore file that must never leave the device via backup. */
    private val cookieFile = "datastore/ktor_session_cookies.preferences_pb"

    /** `domain`/`path` pairs of every `<exclude>` nested under [parentTag] in [xml]. */
    private fun excludesUnder(xml: File, parentTag: String): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val parents = doc.getElementsByTagName(parentTag)
        val result = mutableListOf<Pair<String, String>>()
        for (i in 0 until parents.length) {
            val excludes = (parents.item(i) as Element).getElementsByTagName("exclude")
            for (j in 0 until excludes.length) {
                val e = excludes.item(j) as Element
                result += e.getAttribute("domain") to e.getAttribute("path")
            }
        }
        return result
    }

    /**
     * True when some file-domain exclude covers [cookieFile] — either the exact file or a
     * directory prefix (e.g. `datastore/`, which also covers the `.preferences_pb.tmp`
     * atomic-write scratch file).
     */
    private fun List<Pair<String, String>>.excludesCookie(): Boolean = any { (domain, path) ->
        domain == "file" &&
            (path == cookieFile || cookieFile.startsWith(path.removeSuffix("/") + "/"))
    }

    @Test
    fun `data extraction rules file has no leftover scaffold TODO`() {
        assertFalse(dataExtractionRulesFile.readText().contains("TODO"))
    }

    @Test
    fun `backup rules file has no leftover scaffold TODO`() {
        assertFalse(backupRulesFile.readText().contains("TODO"))
    }

    @Test
    fun `data extraction rules exclude the session cookie datastore from cloud backup and device transfer`() {
        assertTrue(
            "cloud-backup must exclude $cookieFile",
            excludesUnder(dataExtractionRulesFile, "cloud-backup").excludesCookie(),
        )
        assertTrue(
            "device-transfer must exclude $cookieFile",
            excludesUnder(dataExtractionRulesFile, "device-transfer").excludesCookie(),
        )
    }

    @Test
    fun `full backup content excludes the session cookie datastore`() {
        assertTrue(
            "full-backup-content must exclude $cookieFile",
            excludesUnder(backupRulesFile, "full-backup-content").excludesCookie(),
        )
    }
}
