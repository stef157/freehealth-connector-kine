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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The two steps that make an acknowledgement error's location resolvable, measured on their own — no Spring,
 * no certificate, no XML. Both are pure functions, so this is the whole surface.
 *
 * The notation they have to cope with is the one the CIN error tables use, which is transcribed verbatim into
 * `be/errors/MemberDataErrors.json`: unprefixed steps, the wildcard prefix `*:name`, `not(…)` in step
 * position, `text()`, attribute tests, and literals in either quote style.
 */
class ErrorLocationPathTest {

    /**
     * The rewrite that makes a location path independent of namespaces, measured on the notation the CIN
     * error tables actually use — unprefixed steps, the wildcard prefix `*:name`, `not(…)`, `text()`,
     * attribute tests, and literals in either quote style.
     */
    @Test
    fun namespaceAgnosticRewritesEveryElementNameTest() {
        val agnostic = { path: String -> ErrorLocationPath.namespaceAgnostic(path) }

        assertThat(agnostic("/SendTransactionRequest/request/id"))
            .isEqualTo("/*[local-name()='SendTransactionRequest']/*[local-name()='request']/*[local-name()='id']")

        // The CIN notation: a wildcard prefix is a name test too.
        assertThat(agnostic("/AttributeQuery/*:Issuer"))
            .isEqualTo("/*[local-name()='AttributeQuery']/*[local-name()='Issuer']")

        // A real prefix is today a compile error, for want of a NamespaceContext. The prefix goes.
        assertThat(agnostic("/ns1:cd[@S='CD-ITEM']"))
            .isEqualTo("/*[local-name()='cd'][@S='CD-ITEM']")

        // An attribute keeps its prefix — attributes in these schemas are unqualified.
        assertThat(agnostic("/AttributeQuery/Extensions/@xsi:type"))
            .isEqualTo("/*[local-name()='AttributeQuery']/*[local-name()='Extensions']/@xsi:type")

        // A function is not a name test, and neither is a node type test.
        assertThat(agnostic("/AttributeQuery/Issuer/text()"))
            .isEqualTo("/*[local-name()='AttributeQuery']/*[local-name()='Issuer']/text()")

        // A bare wildcard stays a wildcard.
        assertThat(agnostic("/SubjectConfirmation/*"))
            .isEqualTo("/*[local-name()='SubjectConfirmation']/*")
    }

    /** A word inside a literal only looks like an element name. It must survive untouched. */
    @Test
    fun namespaceAgnosticLeavesLiteralsAlone() {
        assertThat(ErrorLocationPath.namespaceAgnostic("""/transaction[not(cd[@S='CD-ITEM' and .='patientpaid'])]"""))
            .isEqualTo(
                "/*[local-name()='transaction'][not(*[local-name()='cd'][@S='CD-ITEM' and .='patientpaid'])]"
            )

        // Both quote styles arrive from the OAs — the ConsultTarif catalogue spells the class ['"] out.
        assertThat(ErrorLocationPath.namespaceAgnostic("""/item[cd[@SL="NIHDI-TREATED-LIMB"]]"""))
            .isEqualTo("""/*[local-name()='item'][*[local-name()='cd'][@SL="NIHDI-TREATED-LIMB"]]""")
    }

    /** Rewriting an already agnostic path changes nothing, so a double pass is harmless. */
    @Test
    fun namespaceAgnosticIsIdempotent() {
        val once = ErrorLocationPath.namespaceAgnostic("/SendTransactionRequest/kmehrmessage/folder/transaction[cga]")
        assertThat(ErrorLocationPath.namespaceAgnostic(once)).isEqualTo(once)

        // The interesting case: the rewrite emits quotes of its own, so a second pass sees literals that the
        // first one wrote. `local-name` is a function call and `'item'` a literal, both left alone.
        val withLiteral = ErrorLocationPath.namespaceAgnostic("""/item[not(cd[@S='CD-ITEM' and .='patientpaid'])]""")
        assertThat(ErrorLocationPath.namespaceAgnostic(withLiteral)).isEqualTo(withLiteral)
    }

    /**
     * A `not(…)` step names an element that is *absent*, so only its parent can be resolved. The CIN tables
     * write it that way — `/AttributeQuery/not(*:Issuer)` sits verbatim in `MemberDataErrors.json` — and it
     * is not valid XPath, a function cannot follow `/`.
     */
    @Test
    fun resolvableStepsDropsOnlyAMissingElementStep() {
        assertThat(ErrorLocationPath.resolvableSteps("/SendTransactionRequest/kmehrmessage/folder/transaction/not(*:patientpaid)"))
            .isEqualTo("/SendTransactionRequest/kmehrmessage/folder/transaction")

        // The predicate form is valid XPath and must survive untouched — the safe-superset property.
        assertThat(ErrorLocationPath.resolvableSteps("/folder/transaction[not(item)]"))
            .isEqualTo("/folder/transaction[not(item)]")

        // `not(` inside a predicate is not a step, whatever follows it.
        assertThat(ErrorLocationPath.resolvableSteps("/folder/transaction[not(item)]/author"))
            .isEqualTo("/folder/transaction[not(item)]/author")

        // A node type test is legitimate in step position.
        assertThat(ErrorLocationPath.resolvableSteps("/AttributeQuery/Issuer/text()"))
            .isEqualTo("/AttributeQuery/Issuer/text()")
    }
}
