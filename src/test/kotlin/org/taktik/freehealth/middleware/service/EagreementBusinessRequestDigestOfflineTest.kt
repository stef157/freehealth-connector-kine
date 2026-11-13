package org.taktik.freehealth.middleware.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.taktik.freehealth.middleware.service.impl.EagreementServiceImpl

/**
 * The INFO log names an eAgreement request by its size and a SHA-256 prefix instead of printing the Bundle (SSIN,
 * prescription PDF). The prefix must be recomputable from `mycarenetConversation.businessRequest`, so it is pinned
 * against a value any `sha256sum` gives.
 */
class EagreementBusinessRequestDigestOfflineTest {
    @Test
    fun theDigestIsTheFirstEightHexDigitsOfTheSha256() {
        // printf '%s' '<Bundle/>' | shasum -a 256
        assertThat(EagreementServiceImpl.businessRequestDigest("<Bundle/>".toByteArray(Charsets.UTF_8)))
            .isEqualTo("df305f80")
        // printf '' | shasum -a 256 → e3b0c442…
        assertThat(EagreementServiceImpl.businessRequestDigest(ByteArray(0))).isEqualTo("e3b0c442")
    }
}
