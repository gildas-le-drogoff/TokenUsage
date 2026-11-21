package fr.gildasledrogoff.tokenusage.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManufacturerAdviceTest {

    @Test
    fun `known vendors map to their group regardless of case and blanks`() {
        assertEquals(ManufacturerGroup.XIAOMI, ManufacturerAdvice.forManufacturer("Xiaomi"))
        assertEquals(ManufacturerGroup.XIAOMI, ManufacturerAdvice.forManufacturer("  POCO "))
        assertEquals(ManufacturerGroup.HUAWEI, ManufacturerAdvice.forManufacturer("HONOR"))
        assertEquals(ManufacturerGroup.OPPO, ManufacturerAdvice.forManufacturer("realme"))
        assertEquals(ManufacturerGroup.VIVO, ManufacturerAdvice.forManufacturer("vivo"))
        assertEquals(ManufacturerGroup.SAMSUNG, ManufacturerAdvice.forManufacturer("samsung"))
        assertEquals(ManufacturerGroup.ONEPLUS, ManufacturerAdvice.forManufacturer("OnePlus"))
    }

    @Test
    fun `stock android vendors have no advice`() {
        listOf("Google", "motorola", "Sony", "Nothing", null, "", "   ").forEach {
            assertNull(it, ManufacturerAdvice.forManufacturer(it))
        }
    }

    @Test
    fun `vendor name must match a whole word`() {
        assertNull(ManufacturerAdvice.forManufacturer("vivobook"))
        assertEquals(ManufacturerGroup.XIAOMI, ManufacturerAdvice.forManufacturer("Xiaomi Communications"))
    }

    @Test
    fun `instruction links are plain https text pointing to dontkillmyapp`() {
        ManufacturerGroup.entries.forEach {
            assertTrue(it.instructionsUrl, it.instructionsUrl.startsWith("https://dontkillmyapp.com"))
        }
    }

    @Test
    fun `standby bucket names and restrictiveness`() {
        assertEquals("ACTIVE", BackgroundSnapshot.standbyBucketName(10))
        assertEquals("RESTRICTED", BackgroundSnapshot.standbyBucketName(45))
        assertEquals("UNKNOWN(33)", BackgroundSnapshot.standbyBucketName(33))
        assertNull(BackgroundSnapshot.standbyBucketName(null))
        assertTrue(BackgroundSnapshot.isStandbyBucketRestrictive(40))
        assertTrue(BackgroundSnapshot.isStandbyBucketRestrictive(45))
        assertTrue(!BackgroundSnapshot.isStandbyBucketRestrictive(30))
        assertTrue(!BackgroundSnapshot.isStandbyBucketRestrictive(null))
    }
}
