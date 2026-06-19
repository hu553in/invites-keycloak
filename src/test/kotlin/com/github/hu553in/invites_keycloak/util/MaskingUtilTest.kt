package com.github.hu553in.invites_keycloak.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaskingUtilTest {

    @Test
    fun `masks regular value`() {
        // act & assert
        assertThat(maskSensitive("alice@example.com")).isEqualTo("a***m")
    }

    @Test
    fun `masks short value`() {
        // act & assert
        assertThat(maskSensitive("ab")).isEqualTo("***")
    }

    @Test
    fun `returns placeholder for blank`() {
        // act & assert
        assertThat(maskSensitive("   ")).isEqualTo("***")
        assertThat(maskSensitive(null)).isEqualTo("***")
    }
}
