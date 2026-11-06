package com.alwayside.android

import org.junit.Assert.*
import org.junit.Test

class SettingsRecoveryTest {
    @Test fun resetDropsBrokenServicesAndUnknownSecretsButKeepsReadablePromptSettings() {
        val recovered = SettingsRecovery.reset(mapOf("schemaVersion" to 999, "services" to "broken",
            "credential" to "must not survive", "instructions" to "custom instruction",
            "textQuestion" to "custom question", "audioOutput" to "translate", "autoUpdateImage" to true))
        assertEquals(4, recovered["schemaVersion"])
        assertFalse(recovered.containsKey("credential"))
        val catalog = ServiceCatalogCodec.decode(recovered["services"].wireObject()) { fail("no secrets should load"); "" to false }
        assertEquals(ServiceCatalog(), catalog)
        assertEquals("custom instruction", recovered["instructions"])
        assertEquals("custom question", recovered["textQuestion"])
        assertEquals("translate", recovered["audioOutput"])
        assertEquals(true, recovered["autoUpdateImage"])
    }

    @Test fun unreadableOrInvalidPublicFieldsRecoverToDefaults() {
        val fallback = SettingsRecovery.reset(null)
        assertEquals(fallback, SettingsRecovery.reset(mapOf("instructions" to 5, "audioOutput" to "invalid")))
    }
}
