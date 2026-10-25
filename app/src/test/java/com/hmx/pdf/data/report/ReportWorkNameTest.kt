package com.hmx.pdf.data.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportWorkNameTest {

    @Test
    fun uniqueNameUsesTheNormalizedClientId() {
        assertEquals(
            "report-$TEST_CLIENT_ID",
            ReportWorkName.uniqueName(TEST_CLIENT_ID.uppercase()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidClientIdIsRejected() {
        ReportWorkName.uniqueName("not-a-uuid")
    }
}
