package org.taktik.freehealth.utils

import be.cin.encrypted.EncryptedKnownContent
import be.cin.types.v1.Blob
import org.taktik.connector.business.mycarenetdomaincommons.mapper.DomainBlobMapper
import org.taktik.connector.technical.service.etee.Crypto
import org.taktik.connector.technical.utils.ConnectorIOUtils
import org.taktik.connector.technical.utils.MarshallerHelper

/**
 * Thrown when the payload of a generic async message cannot be decoded.
 * [stage] tells which decoding step failed and [payloadHead] holds the first bytes (hex) of the data given to that step.
 */
class AsyncPayloadDecodingException(val stage: String, val payloadHead: String?, cause: Throwable) :
    RuntimeException("Cannot decode async message payload at stage [$stage]: ${cause.message}", cause)

object AsyncPayloadDecoder {
    private const val HEAD_SIZE = 16


    /**
     * The business payload of a Detail, and the XAdES that sealed it.
     *
     * [xades] is `EncryptedKnownContent.xades`: the sender's seal over the business content, the one the
     * synchronous eAgreement path already returns. Null when the message is not `encryptedForKnownRecipient`.
     */
    class DecodedDetail(val payload: ByteArray, val xades: ByteArray?)

    /**
     * Turns the Detail blob of a generic async message into the business payload bytes:
     * decompresses it, unseals it when it is encrypted for a known recipient and unwraps the EncryptedKnownContent.
     */
    fun decodeDetail(detail: Blob, crypto: Crypto): ByteArray = decodeDetailWithSeal(detail, crypto).payload

    /**
     * [decodeDetail], keeping the XAdES of the EncryptedKnownContent instead of dropping it. Before this the seal of
     * every async message was lost on the way: the decision of an eAgreement medical adviser reached the caller
     * without the proof that came with it.
     */
    fun decodeDetailWithSeal(detail: Blob, crypto: Crypto): DecodedDetail {
        val content = stage("read detail", null) { DomainBlobMapper.mapToBlob(detail).content }
        val data = if (detail.contentEncoding == "deflate") stage("decompress detail", content) { ConnectorIOUtils.decompress(content) } else content
        if (detail.contentEncryption != "encryptedForKnownRecipient") return DecodedDetail(data, null)

        val unsealedData = stage("unseal detail", data) { crypto.unseal(Crypto.SigningPolicySelector.WITHOUT_NON_REPUDIATION, data).contentAsByte }
        val knownContent = stage("unmarshal EncryptedKnownContent", unsealedData) {
            MarshallerHelper(EncryptedKnownContent::class.java, EncryptedKnownContent::class.java).toObject(unsealedData)
        }
        val businessContent = knownContent.businessContent
        val payload = if (businessContent.contentEncoding == "deflate") stage("decompress business content", businessContent.value) { ConnectorIOUtils.decompress(businessContent.value) } else businessContent.value
        return DecodedDetail(payload, knownContent.xades)
    }

    /** Unmarshals the decoded payload, reporting a failure as an [AsyncPayloadDecodingException] carrying the payload head. */
    fun <T> unmarshal(payload: ByteArray, clazz: Class<T>): T =
        stage("unmarshal ${clazz.simpleName}", payload) { MarshallerHelper(clazz, clazz).toObject(payload) }

    fun hexHead(bytes: ByteArray?) = bytes?.take(HEAD_SIZE)?.joinToString(" ") { "%02x".format(it) }

    private fun <T> stage(name: String, input: ByteArray?, block: () -> T): T = try {
        block()
    } catch (e: AsyncPayloadDecodingException) {
        throw e
    } catch (e: Exception) {
        throw AsyncPayloadDecodingException(name, input?.let { "${hexHead(it)} (${it.size} bytes)" }, e)
    }
}
