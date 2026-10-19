package com.calairtime

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun `platform name is reported`() {
        assertTrue(getPlatform().name.isNotBlank())
    }
}