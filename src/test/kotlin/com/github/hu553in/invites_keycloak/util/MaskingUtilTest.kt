package com.github.hu553in.invites_keycloak.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaskingUtilTest {

    @Test
    fun `masks regular value`() {
        assertThat(maskSensitive("alice@example.com")).isEqualTo("a***m")
    }

    @Test
    fun `masks short value`() {
        assertThat(maskSensitive("ab")).isEqualTo("***")
    }

    @Test
    fun `returns placeholder for blank`() {
        assertThat(maskSensitive("   ")).isEqualTo("***")
        assertThat(maskSensitive(null)).isEqualTo("***")
    }
}
