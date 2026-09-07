/*
 *
 * Copyright (C) 2018 iCure SA
 *
 * This file is part of FreeHealthConnector.
 *
 * FreeHealthConnector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation.
 *
 * FreeHealthConnector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with FreeHealthConnector.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.taktik.freehealth.middleware.service.impl

import be.fgov.ehealth.genericinsurability.core.v1.*
import be.fgov.ehealth.genericinsurability.core.v1.InsurabilityContactTypeType.AMBULATORY_CARE
import be.fgov.ehealth.genericinsurability.core.v1.InsurabilityContactTypeType.HOSPITALIZED_ELSEWHERE
import be.fgov.ehealth.genericinsurability.core.v1.InsurabilityRequestTypeType.INFORMATION
import be.fgov.ehealth.genericinsurability.protocol.v1.GetInsurabilityAsXmlOrFlatRequestType
import be.fgov.ehealth.genericinsurability.protocol.v1.GetInsurabilityResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.xml.messaging.saaj.soap.impl.ElementImpl
import com.sun.xml.messaging.saaj.soap.ver1_1.DetailEntry1_1Impl
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.taktik.connector.business.mycarenetdomaincommons.util.McnConfigUtil
import org.taktik.connector.business.mycarenetdomaincommons.util.PropertyUtil
import org.taktik.connector.technical.config.ConfigFactory
import org.taktik.connector.technical.idgenerator.IdGeneratorFactory
import org.taktik.connector.technical.utils.MarshallerHelper
import org.taktik.freehealth.middleware.dao.User
import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetError
import org.taktik.freehealth.middleware.dto.genins.InsurabilityInfoDto
import org.taktik.freehealth.middleware.dto.mycarenet.CommonOutput
import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetConversation
import org.taktik.freehealth.middleware.exception.MissingTokenException
import org.taktik.freehealth.middleware.mapper.toInsurabilityInfoDto
import org.taktik.freehealth.middleware.service.GenInsService
import org.taktik.freehealth.middleware.service.STSService
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@Service
class GenInsServiceImpl(val stsService: STSService) : GenInsService {
    @Value("\${mycarenet.timezone}")
    internal val mcnTimezone: String = "Europe/Brussels"

    private val log = LoggerFactory.getLogger(this.javaClass)
    private val freehealthGenInsService: org.taktik.connector.business.genins.service.GenInsService =
        org.taktik.connector.business.genins.service.impl.GenInsServiceImpl()
    private val GenInsErrors =
        ObjectMapper().readValue<Array<MycarenetError>>(
            this.javaClass.getResourceAsStream("/be/errors/GenInsErrors.json")!!
        ).associateBy({ it.uid }, { it })
    private val xPathfactory = XPathFactory.newInstance()
    private val config = ConfigFactory.getConfigValidator(listOf())

    override fun getGeneralInsurabity(
        keystoreId: UUID,
        tokenId: UUID,
        hcpQuality: String,
        hcpNihii: String,
        hcpSsin: String,
        hcpName: String,
        passPhrase: String,
        patientSsin: String?,
        io: String?,
        ioMembership: String?,
        startDate: Instant?,
        endDate: Instant?,
        hospitalized: Boolean
    ): InsurabilityInfoDto {
        require(
            hcpQuality.equals("doctor") ||
                hcpQuality.equals("nurse") ||
                hcpQuality.equals("physiotherapist") ||
                hcpQuality.equals("dentist") ||
                hcpQuality.equals("logopedist") ||
                hcpQuality.equals("trussmaker") ||
                hcpQuality.equals("orthopedist") ||
                hcpQuality.equals("midwife") ||
                hcpQuality.equals("optician") ||
                hcpQuality.equals("podologist") ||
                hcpQuality.equals("dietician") ||
                hcpQuality.equals("hospital") ||
                hcpQuality.equals("groupofnurses") ||
                hcpQuality.equals("labo") ||
                hcpQuality.equals("retirement") ||
                hcpQuality.equals("otdpharmacy") ||
                hcpQuality.equals("medicalhouse") ||
                hcpQuality.equals("groupofdoctors") ||
                hcpQuality.equals("psychiatrichouse") ||
                hcpQuality.equals("guardpost") ||
                hcpQuality.equals("ambulanceservice")
        ) { "hcpQuality is invalid" }

        val samlToken =
            stsService.getSAMLToken(tokenId, keystoreId, passPhrase)
                ?: throw MissingTokenException("Cannot obtain token for Genins operations")
        assert(patientSsin != null || io != null && ioMembership != null)

        val principal = SecurityContextHolder.getContext().authentication?.principal as? User
        val packageInfo = McnConfigUtil.retrievePackageInfo("genins", principal?.mcnLicense, principal?.mcnPassword, principal?.mcnPackageName)

        log.debug("getGeneralInsurability called with principal "+(principal?:"<ANONYMOUS>")+" and license " + (principal?.mcnLicense ?: "<DEFAULT>"))

        val request = GetInsurabilityAsXmlOrFlatRequestType().apply {
            recordCommonInput =
                RecordCommonInputType().apply {
                    inputReference =
                        BigDecimal(IdGeneratorFactory.getIdGenerator().generateId())
                }
            commonInput = CommonInputType().apply {
                request =
                    RequestType().apply { isIsTest = false /*config.getProperty("endpoint.genins")?.contains("-acpt") ?: false*/ }
                inputReference = "" + IdGeneratorFactory.getIdGenerator().generateId()
                origin = OriginType().apply {
                    `package` = PackageType().apply {
                        license = LicenseType().apply {
                            username = packageInfo.userName
                            password = packageInfo.password
                        }
                        name = ValueRefString().apply { value = packageInfo.packageName }
                    }
                    config.getProperty("mycarenet.${PropertyUtil.retrieveProjectNameToUse("genins","mycarenet.")}.site.id")?.let{
                        if (it.isNotBlank()) {
                            siteID = ValueRefString().apply { value = it }
                        }
                    }
                    careProvider = CareProviderType().apply {
                        if(hcpQuality == "guardpost") {
                            // nihii11 is required with guardpost
                            nihii =
                                NihiiType().apply {
                                    quality = hcpQuality; value =
                                    ValueRefString().apply { value = hcpNihii.padEnd(11, '0') }
                                }
                            organization = IdType().apply {
                                nihii =
                                    NihiiType().apply {
                                        quality = hcpQuality; value =
                                        ValueRefString().apply { value = hcpNihii.padEnd(11, '0') }
                                    }
                            }
                        }else{
                            nihii =
                                NihiiType().apply {
                                    quality = hcpQuality; value =
                                    ValueRefString().apply { value = hcpNihii }
                                }
                            physicalPerson = IdType().apply {
                                name = ValueRefString().apply { value = hcpName }
                                ssin = ValueRefString().apply { value = hcpSsin }
                                nihii =
                                    NihiiType().apply {
                                        quality = hcpQuality; value =
                                        ValueRefString().apply { value = hcpNihii }
                                    }
                            }
                        }
                    }
                }
            }
            request = SingleInsurabilityRequestType().apply {
                insurabilityRequestDetail = InsurabilityRequestDetailType().apply {
                    insurabilityRequestType = INFORMATION
                    careReceiverId = CareReceiverIdType().apply {
                        inss = patientSsin
                        mutuality = io
                        regNrWithMut = ioMembership
                    }
                    insurabilityContactType = if (hospitalized) HOSPITALIZED_ELSEWHERE else AMBULATORY_CARE
                    insurabilityReference = "" + System.currentTimeMillis()
                    period = PeriodType().apply {
                        periodStart = startDate?.let { DateTime(it.toEpochMilli(), DateTimeZone.forID(mcnTimezone)) } ?: DateTime()
                        periodEnd = endDate?.let { DateTime(it.toEpochMilli(), DateTimeZone.forID(mcnTimezone)) } ?: periodStart
                    }
                }
            }
        }

        return try {
            val kmehrRequestMarshaller =
                MarshallerHelper(
                    GetInsurabilityAsXmlOrFlatRequestType::class.java,
                    GetInsurabilityAsXmlOrFlatRequestType::class.java
                                )
            val xmlData = kmehrRequestMarshaller.toXMLByteArray(request)

            if (log.isDebugEnabled) {
                log.debug("Genins request: {}", xmlData.toString(Charsets.UTF_8))
            }

            val genInsResponse = freehealthGenInsService.getInsurability(samlToken, request)
            val genInsResponseDTO = genInsResponse.toInsurabilityInfoDto()

            genInsResponseDTO.errors = genInsResponse.response.messageFault?.details?.details?.flatMap { extractError(xmlData, it.detailCode, it.location).toList() } ?: listOf()

            val commonOutput = CommonOutput(
                genInsResponse?.commonOutput?.inputReference?.toString(),
                genInsResponse?.commonOutput?.nipReference?.toString(),
                genInsResponse?.commonOutput?.outputReference?.toString()
            )

            genInsResponseDTO?.apply {
                this.commonOutput = commonOutput
                this.mycarenetConversation = MycarenetConversation().apply {
                    genInsResponse.soapRequest?.writeTo(this.soapRequestOutputStream())
                    genInsResponse.soapResponse?.writeTo(this.soapResponseOutputStream())
                    this.transactionRequest = xmlData.toString(Charsets.UTF_8)
                    this.transactionResponse = MarshallerHelper(GetInsurabilityResponse::class.java, GetInsurabilityResponse::class.java).toXMLByteArray(genInsResponse).toString(Charsets.UTF_8)
                }
            }

            return genInsResponseDTO

        } catch (e: jakarta.xml.ws.soap.SOAPFaultException) {
            InsurabilityInfoDto(
                faultMessage = e.fault.faultString,
                faultSource = e.message,
                faultCode = e.fault?.faultCode,
                transfers = listOf(),
                errors = extractError(e).toList()
            )
        }
    }

    private fun extractError(sendTransactionRequest: ByteArray, ec: String, errorUrl: String?): Set<MycarenetError> {
        //For some reason... The path starts with ../../../../ which corrsponds to the request
        return errorUrl?.let { url ->
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()

            // The location eHealth sends here is relative: `../../../../CareReceiverId/Inss/text()`, four
            // steps up being the request itself. That substitution is a fact of this domain and has to stay —
            // a `*[local-name()=…]` on a relative path evaluated from the Document has no parent to climb. The
            // prefixes it used to inject, and the NamespaceContext that bound them, are gone: the rewrite
            // discards prefixes and matches on the local name whatever the namespace.
            val curratedUrl = url.replace(
                Regex("^\\.\\./\\.\\./\\.\\./\\.\\./"),
                "/GetInsurabilityAsXmlOrFlatRequestType/Request/"
            )
            val resolvableUrl = ErrorLocationPath.resolvableSteps(curratedUrl)
            val namesAMissingElement = resolvableUrl != curratedUrl
            val result = mutableSetOf<MycarenetError>()

            val nodes = runCatching {
                val xpath = xPathfactory.newXPath()
                val expr = xpath.compile(ErrorLocationPath.namespaceAgnostic(resolvableUrl))
                expr.evaluate(
                    builder.parse(ByteArrayInputStream(sendTransactionRequest)),
                    XPathConstants.NODESET
                              ) as NodeList
            }.getOrElse { e ->
                // The CIN writes some locations in a notation that is not XPath at all — a bare URN inside a
                // predicate, for one. Six of the ten copies of this function already caught that; here the
                // exception left `extractError` and took the whole response down with it.
                log.warn("genins: error $ec, uncompilable location `$url\u00b4", e)
                result.add(
                    MycarenetError(
                        code = ec,
                        path = curratedUrl,
                        msgFr = "Erreur générique, xpath invalide : " + e.message,
                        msgNl = "Onbekend foutmelding, xpath ongeldig : " + e.message
                    )
                )
                null
            }

            nodes?.let { it ->
                if (it.length > 0) {
                    var node = it.item(0)
                    val textContent = if (namesAMissingElement) null else node.textContent
                    var base = "/" + nodeDescr(node)
                    while (node.parentNode != null && node.parentNode is Element) {
                        base = "/${nodeDescr(node.parentNode)}$base"
                        node = node.parentNode
                    }
                    val elements =
                        GenInsErrors.values.filter {
                            (it.path == null || it.path == base) && it.code == ec &&
                                (it.regex == null || curratedUrl.matches(Regex(".*" + it.regex + ".*")))
                        }
                    result.addAll(elements.map { ErrorLocationPath.renderedFor(it, textContent) })
                } else {
                    log.warn("genins: error $ec, unresolved location `$url\u00b4")
                    result.add(
                        MycarenetError(
                            code = ec,
                            path = url,
                            msgFr = "Erreur générique, xpath invalide",
                            msgNl = "Onbekend foutmelding, xpath ongeldig"
                                                                                     )
                              )
                }
            }
            result
        } ?: setOf()
    }

    private fun extractError(e: jakarta.xml.ws.soap.SOAPFaultException): Set<MycarenetError> {
        val result = mutableSetOf<MycarenetError>()

        e.fault.detail.detailEntries.forEach { it ->
            if(it != null) {
                val detailEntry = it as DetailEntry1_1Impl
                val codeElements = detailEntry.getElementsByTagName("Code")
                for (i in 0..(codeElements.length - 1)){
                    val codeElement = codeElements?.item(i) as ElementImpl
                    result.addAll(GenInsErrors.values.filter { it.code == codeElement.value })
                }
            }
        }
        return result
    }

    private fun nodeDescr(node: Node): String {
        val localName = node.localName ?: node.nodeName?.replace(Regex(".+?:(.+)"), "$1") ?: "unknown"

        return localName
    }

}
