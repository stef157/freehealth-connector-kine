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

package org.taktik.freehealth.middleware.web.controllers

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.taktik.freehealth.middleware.dto.HealthcareParty
import org.taktik.freehealth.middleware.service.AddressbookService
import java.util.*

/**
 * REST controller for querying the Belgian eHealth Addressbook.
 *
 * Provides endpoints to search for and retrieve details of healthcare professionals (HCPs)
 * and organizations registered in the Belgian eHealth platform addressbook. Lookups can be
 * performed by name (phonetic search), NIHII number, SSIN, CBE number, or EHP identifier.
 *
 * All endpoints require a valid keystore and SAML token, supplied via HTTP headers.
 */
@RestController
@RequestMapping("/ab")
@Tag(name = "Addressbook", description = "Search for healthcare professionals and organizations in the Belgian eHealth addressbook.")
class AddressbookController(val addressbookService: AddressbookService) {
    /**
     * Searches the Belgian eHealth addressbook for healthcare professionals matching a last name.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param lastName last name to search for in the addressbook
     * @param firstName optional first name to narrow the search results
     * @param type optional HCP type filter (e.g. "PHYSICIAN", "DENTIST"); defaults to "PHYSICIAN" if not specified
     * @return a list of [HealthcareParty] entries matching the search criteria
     */
    @Operation(
        summary = "Search healthcare professionals by last name (legacy)",
        description = "Searches the eHealth address book by family name only. The name accepts a '*' wildcard (Steeman*). " +
            "The profession defaults to PHYSICIAN when 'type' is omitted, so this endpoint cannot search across " +
            "every profession — use GET /ab/search/hcp for that, and for any criterion other than a name."
    )
    @GetMapping("/search/hcp/{lastName}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun searchHcp(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "Family name of the professional, '*' wildcard allowed", required = true)
        @PathVariable lastName: String,
        @Parameter(description = "First name of the professional, '*' wildcard allowed")
        @RequestParam(required = false) firstName: String?,
        @Parameter(description = "Profession code, e.g. PHYSICIAN, PHYSIOTHERAPIST, NURSE. Defaults to PHYSICIAN")
        @RequestParam(required = false) type: String?
    ): List<HealthcareParty> = addressbookService.searchHcp(
        keystoreId, tokenId, passPhrase, lastName, firstName, type ?: "PHYSICIAN"
    )

    @Operation(
        summary = "Search healthcare professionals on any combination of criteria",
        description = "Broad search in the eHealth address book: every criterion is optional, only the empty query is " +
            "rejected. NIHII/SSIN and city/zipCode are xs:choice in the schema, so each pair is mutually exclusive " +
            "(400). Beyond that the service decides, and it is stricter than its own schema: it wants a profession " +
            "unless you search by nihii or ssin, and it refuses a name and a location together, answering " +
            "'This combination of search criteria is not supported.' Names accept a '*' wildcard (Steeman*). Results " +
            "carry identity, nihii, professionCodes and speciality only — no address and no eHealthBox: resolve " +
            "those with GET /ab/hcp/nihii/{nihii} once a result has been picked."
    )
    @GetMapping("/search/hcp", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun searchHcpByCriteria(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "Family name of the professional, '*' wildcard allowed")
        @RequestParam(required = false) lastName: String?,
        @Parameter(description = "First name of the professional, '*' wildcard allowed")
        @RequestParam(required = false) firstName: String?,
        @Parameter(description = "Profession code, e.g. PHYSICIAN, PHYSIOTHERAPIST. Omit (or pass ALL) for every profession")
        @RequestParam(required = false) profession: String?,
        @Parameter(description = "INAMI/RIZIV number of the professional. Mutually exclusive with ssin")
        @RequestParam(required = false) nihii: String?,
        @Parameter(description = "NISS/INSZ of the professional. Mutually exclusive with nihii")
        @RequestParam(required = false) ssin: String?,
        @Parameter(description = "Zip code of the contact address. Mutually exclusive with city")
        @RequestParam(required = false) zipCode: String?,
        @Parameter(description = "City or village of the contact address. Mutually exclusive with zipCode")
        @RequestParam(required = false) city: String?,
        @Parameter(description = "E-mail address of the professional")
        @RequestParam(required = false) email: String?,
        @Parameter(description = "Index of the first result, defaults to 0. No total is returned: page until a page holds < limit")
        @RequestParam(required = false) offset: Int?,
        @Parameter(description = "Maximum number of results per page, defaults to and capped at 100 by the eHealth service")
        @RequestParam(required = false) limit: Int?
    ): List<HealthcareParty> = addressbookService.searchHcp(
        keystoreId = keystoreId,
        tokenId = tokenId,
        passPhrase = passPhrase,
        lastName = lastName,
        firstName = firstName,
        profession = profession,
        nihii = nihii,
        ssin = ssin,
        zipCode = zipCode,
        city = city,
        email = email,
        offset = offset ?: 0,
        limit = limit ?: 100
    )

    /**
     * Searches the Belgian eHealth addressbook for organizations matching a name.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param name organization name to search for in the addressbook
     * @param type optional organization type filter (e.g. "HOSPITAL", "PHARMACY"); defaults to "HOSPITAL" if not specified
     * @return a list of [HealthcareParty] entries representing matching organizations
     */
    @Operation(
        summary = "Search healthcare organizations by name",
        description = "Searches the eHealth address book for institutions whose name matches. The name accepts a '*' " +
            "wildcard (*clinique*). The institution type defaults to HOSPITAL when 'type' is omitted."
    )
    @GetMapping("/search/org/{name}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun searchOrg(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "Name of the institution, '*' wildcard allowed", required = true)
        @PathVariable name: String,
        @Parameter(description = "Type / quality of the institution, e.g. HOSPITAL. Defaults to HOSPITAL")
        @RequestParam(required = false) type: String?
    ): List<HealthcareParty> = addressbookService.searchOrg(
        keystoreId, tokenId, passPhrase, name, type ?: "HOSPITAL"
    )

    /**
     * Retrieves a healthcare professional from the addressbook by their NIHII number.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param nihii NIHII number (unique Belgian healthcare provider identification number)
     * @param language optional language code for the response (e.g. "fr", "nl", "de"); defaults to "fr"
     * @return the matching [HealthcareParty], or null if no match is found
     */
    @Operation(
        summary = "Get the full contact information of a professional by INAMI/RIZIV number",
        description = "Returns the professional with their addresses, telecoms, professionCodes and eHealthBox entries. " +
            "This is the call that completes a search result, which carries none of those."
    )
    @GetMapping("/hcp/nihii/{nihii}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun getHcpByNihii(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "INAMI/RIZIV number of the professional", required = true)
        @PathVariable nihii: String,
        @Parameter(description = "Language of the address descriptions (fr, nl, de). Defaults to fr")
        @RequestParam(required = false) language: String?
    ): HealthcareParty? = addressbookService.getHcp(
        keystoreId, tokenId, passPhrase, nihii, null, null, language ?: "fr"
    )

    /**
     * Retrieves a healthcare professional from the addressbook by their SSIN.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param ssin social security identification number (NISS) of the healthcare professional
     * @param quality optional healthcare provider quality for authorization purposes
     * @param language optional language code for the response (e.g. "fr", "nl", "de"); defaults to "fr"
     * @return the matching [HealthcareParty], or null if no match is found
     */
    @Operation(
        summary = "Get the full contact information of a professional by NISS/INSZ",
        description = "Returns the professional with their addresses, telecoms, professionCodes and eHealthBox entries. " +
            "A person can hold several professions: 'quality' selects which one the nihii and addresses are taken " +
            "from, and defaults to PHYSICIAN."
    )
    @GetMapping("/hcp/ssin/{ssin}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun getHcpBySsin(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "NISS/INSZ of the professional", required = true)
        @PathVariable ssin: String,
        @Parameter(description = "Profession code selecting among the person's professions. Defaults to PHYSICIAN")
        @RequestParam(required = false) quality: String?,
        @Parameter(description = "Language of the address descriptions (fr, nl, de). Defaults to fr")
        @RequestParam(required = false) language: String?
    ): HealthcareParty? = addressbookService.getHcp(
        keystoreId, tokenId, passPhrase, null, ssin, quality, language ?: "fr"
    )

    /**
     * Retrieves an organization from the addressbook by its NIHII number.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param nihii NIHII number (unique Belgian healthcare provider identification number) of the organization
     * @param language optional language code for the response (e.g. "fr", "nl", "de"); defaults to "fr"
     * @return the matching [HealthcareParty], or null if no match is found
     */
    @Operation(
        summary = "Get the full contact information of an organization by INAMI/RIZIV number",
        description = "Returns the institution with its addresses, telecoms and eHealthBox entries."
    )
    @GetMapping("/org/nihii/{nihii}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun getOrgByNihii(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "INAMI/RIZIV number of the institution", required = true)
        @PathVariable nihii: String,
        @Parameter(description = "Language of the name and address descriptions (fr, nl, de). Defaults to fr")
        @RequestParam(required = false) language: String?
    ): HealthcareParty? = addressbookService.getOrg(
        keystoreId, tokenId, passPhrase, null, null, nihii, language ?: "fr"
    )

    /**
     * Retrieves an organization from the addressbook by its CBE number.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param cbe Belgian company registration number (Crossroads Bank for Enterprises / Banque-Carrefour des Entreprises)
     * @param language optional language code for the response (e.g. "fr", "nl", "de"); defaults to "fr"
     * @return the matching [HealthcareParty], or null if no match is found
     */
    @Operation(
        summary = "Get the full contact information of an organization by CBE/KBO number",
        description = "Returns the institution with its addresses, telecoms and eHealthBox entries."
    )
    @GetMapping("/org/cbe/{cbe}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun getOrgByCbe(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "CBE/KBO enterprise number of the institution", required = true)
        @PathVariable cbe: String?,
        @Parameter(description = "Language of the name and address descriptions (fr, nl, de). Defaults to fr")
        @RequestParam(required = false) language: String?
    ): HealthcareParty? = addressbookService.getOrg(
        keystoreId, tokenId, passPhrase, null, cbe, null, language ?: "fr"
    )

    /**
     * Retrieves an organization from the addressbook by its EHP identifier.
     *
     * @param keystoreId UUID of the uploaded PKCS12 keystore
     * @param tokenId UUID of the SAML authentication token
     * @param passPhrase passphrase to decrypt the keystore's private key
     * @param ehp eHealth Platform (EHP) identifier of the organization
     * @param language optional language code for the response (e.g. "fr", "nl", "de"); defaults to "fr"
     * @return the matching [HealthcareParty], or null if no match is found
     */
    @Operation(
        summary = "Get the full contact information of an organization by EHP identifier",
        description = "Returns the institution with its addresses, telecoms and eHealthBox entries."
    )
    @GetMapping("/org/ehp/{ehp}", produces = [MediaType.APPLICATION_JSON_UTF8_VALUE])
    fun getOrgByEhp(
        @Parameter(description = "The keystore ID for the certificate of the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-keystoreId") keystoreId: UUID,
        @Parameter(description = "The token ID for the healthcare provider", required = true)
        @RequestHeader(name = "X-FHC-tokenId") tokenId: UUID,
        @Parameter(description = "The passphrase for the keystore", required = true)
        @RequestHeader(name = "X-FHC-passPhrase") passPhrase: String,
        @Parameter(description = "EHP identifier of the institution", required = true)
        @PathVariable ehp: String?,
        @Parameter(description = "Language of the name and address descriptions (fr, nl, de). Defaults to fr")
        @RequestParam(required = false) language: String?
    ): HealthcareParty? = addressbookService.getOrg(
        keystoreId, tokenId, passPhrase, ehp, null, null, language ?: "fr"
    )
}
