package eu.monniot.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ticket #20: [app/src/main/res/xml/data_extraction_rules.xml] and its API<31 sibling
 * [app/src/main/res/xml/backup_rules.xml] must carry explicit backup rules (no leftover
 * wizard-scaffold TODO) that exclude the session-auth DataStore file
 * (`datastore/ktor_session_cookies.preferences_pb`) from both cloud backup and device
 * transfer, so a restored/transferred backup can't carry a live login cookie.
 *
 * These are plain JVM file-content assertions (no Android runtime needed) — the JUnit
 * `Test` task's working directory is the `app/` module root, so the resources are read
 * directly off disk rather than via Robolectric's resource loader.
 */
class BackupRulesTest {

    private val dataExtractionRules =
        File("src/main/res/xml/data_extraction_rules.xml").readText()
    private val backupRules =
        File("src/main/res/xml/backup_rules.xml").readText()

    @Test
    fun `data extraction rules file has no leftover scaffold TODO`() {
        assertFalse(dataExtractionRules.contains("TODO"))
    }

    @Test
    fun `backup rules file has no leftover scaffold TODO`() {
        assertFalse(backupRules.contains("TODO"))
    }

    @Test
    fun `data extraction rules exclude the session cookie datastore from cloud backup and device transfer`() {
        val excludePath = "datastore/ktor_session_cookies.preferences_pb"

        val cloudBackupBlock = dataExtractionRules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val deviceTransferBlock = dataExtractionRules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")

        assertTrue(cloudBackupBlock.contains(excludePath))
        assertTrue(deviceTransferBlock.contains(excludePath))
    }

    @Test
    fun `full backup content excludes the session cookie datastore`() {
        val excludePath = "datastore/ktor_session_cookies.preferences_pb"

        assertTrue(backupRules.contains(excludePath))
    }
}
