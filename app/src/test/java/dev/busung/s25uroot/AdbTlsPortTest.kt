package dev.busung.s25uroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbTlsPortTest {
    @Test
    fun acceptsValidTlsPortProperty() {
        assertEquals(37099, parseAdbTlsPort("37099\n"))
        assertEquals(5555, parseAdbTlsPort("  5555  \n"))
    }

    @Test
    fun rejectsInvalidTlsPortProperty() {
        assertNull(parseAdbTlsPort(""))
        assertNull(parseAdbTlsPort("0"))
        assertNull(parseAdbTlsPort("-1"))
        assertNull(parseAdbTlsPort("65536"))
        assertNull(parseAdbTlsPort("not-a-port"))
    }
}
