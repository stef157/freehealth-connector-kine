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

import org.taktik.freehealth.middleware.dto.mycarenet.MycarenetError

/**
 * The location an eHealth acknowledgement error points at, made resolvable.
 *
 * Every MyCareNet domain carries its own copy of `extractError`, and each one compiles the `url` an error
 * sends as an XPath against the request that provoked it, then compares the path it rebuilds to a catalogue
 * under `be/errors/`. The two steps that make the compilation succeed are the same for all of them and hold
 * no domain knowledge, so they live here once rather than ten times. What legitimately differs between the
 * domains — which `cd` builds a predicate, which fallbacks apply, which catalogue is read — stays in each
 * service's own `nodeDescr` and `extractError`.
 *
 * Both functions are pure. Measured by `ErrorLocationPathTest`.
 */
object ErrorLocationPath {

    /**
     * A catalogue entry rendered for one node of one request.
     *
     * `eAttestErrors` is read once into this singleton `@Service`, so its `MycarenetError` entries are shared by
     * every caller. Writing `value` on the entry itself published the offending node of one caller's request —
     * a NIHII, an SSIN, a date — into the catalogue every other caller reads from, and the returned set kept the
     * reference, so a concurrent call could rewrite it between the rendering and the serialisation. The entry is
     * a template; each error gets its own copy.
     */
    fun renderedFor(entry: MycarenetError, textContent: String?) = MycarenetError(
        uid = entry.uid,
        path = entry.path,
        regex = entry.regex,
        locFr = entry.locFr,
        locNl = entry.locNl,
        msgFr = entry.msgFr,
        msgNl = entry.msgNl,
        msgEn = entry.msgEn,
        code = entry.code,
        subCode = entry.subCode,
        faultCode = entry.faultCode,
        faultSource = entry.faultSource,
        detailCode = entry.detailCode,
        detailSource = entry.detailSource,
        value = textContent
    )

    /**
     * A trailing `text()` step, written the way `nodeDescr` writes the node it resolves to.
     *
     * `text()` is legitimate in step position and the CIN uses it — § 3 of
     * `FR-EXEM-MEMD-ALL … exemples de réponses.pdf` sends `*:AttributeQuery / *:Subject / *:NameID / text()`
     * (separators spaced out: Kotlin nests block comments, and a bare slash-star would open one). It
     * resolves to a text node, and `nodeDescr` writes that `#text`: a text node has no `localName`, its
     * `nodeName` is `#text`, and the de-prefixing regex leaves it alone. That is why `GenInsErrors.json`
     * indexes its 21 text entries as `…/Inss/#text` rather than `…/Inss/text()`.
     *
     * **This is not XPath and must never reach `xpath.compile`.** It exists for the one caller that has no
     * document to resolve against — the async MemberData overload, which compares the location textually —
     * so that both channels arrive at the same string.
     */
    fun resolvedTextStep(locationPath: String) =
        if (locationPath.endsWith("/text()")) locationPath.removeSuffix("/text()") + "/#text" else locationPath

    /**
     * The part of a location path that can actually be resolved.
     *
     * The CIN error tables name a *missing* element with a `not(…)` **step** — `/AttributeQuery/not(*:Issuer)`,
     * verbatim in `MemberDataErrors.json`, entered from the CIN xpath column — and that is not valid XPath: a
     * function cannot follow `/`. The catalogue already expects the shape to be handled by resolving the
     * **parent** and matching the `not(…)` through the entry's own `regex`. Measured on all 27 `regex` entries
     * of `eAttestErrors.json`: the name the regex captures (`patientpaid`, `claim`, `'date'`,
     * `NIHDI-ID-DOC-MEDIA-TYPE`, …) is never the last step of the entry's `path`, which always stops on a
     * `transaction[…]` or an `item[…]`. So the step is dropped here, and the `regex` keeps seeing the original
     * url.
     *
     * `not(` is the only step dropped. A node type test — `text()`, `node()` — is legitimate in step position
     * and stays. If the OAs send the predicate form `…[not(…)]` instead, there is no `not(` at step position
     * and the path comes back unchanged: a safe superset under either hypothesis.
     */
    fun resolvableSteps(locationPath: String): String {
        var depth = 0
        var quote = ' '
        var i = 0
        while (i < locationPath.length) {
            val c = locationPath[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '\'' || c == '"' -> quote = c
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> depth--
                c == '/' && depth == 0 && locationPath.startsWith("/not(", i) -> return locationPath.substring(0, i)
            }
            i++
        }
        return locationPath
    }

    /**
     * The same location path, made independent of namespaces.
     *
     * MyCareNet names the offending node with an unprefixed path — or with the wildcard prefix `*:name`, the
     * notation the CIN error tables use — while the request we resolve it against is marshalled qualified,
     * over three namespaces: `messageservices/protocol/v1` for the root, `messageservices/core/v1` for
     * `request` and `kmehrmessage`, `standards/kmehr/schema/v1` for everything below. A `NamespaceContext`
     * cannot bridge that — in XPath 1.0 an unprefixed name test always means "no namespace", and there is no
     * single namespace to bind anyway. So every element name test, bare or prefixed, becomes
     * `*[local-name()='name']`, which matches the element whichever namespace it sits in. Predicates chain
     * onto it unchanged, so the expression stays equivalent:
     * `item[not(cd[@S='CD-ITEM'])]` → `*[local-name()='item'][not(*[local-name()='cd'][@S='CD-ITEM'])]`.
     *
     * Left alone: quoted literals (both quote styles arrive from the OAs), attribute tests together with
     * their prefix (`@S`, `@xsi:type` — attributes in these schemas are unqualified), function and node-type
     * calls (an NCName followed by `(`: `not`, `text`, `count`), axes (`child::`), `.`, `..`, a bare `*`,
     * numbers, and the four operator words. The rewrite is idempotent: an already agnostic path comes back
     * unchanged.
     */
    fun namespaceAgnostic(locationPath: String): String {
        val operators = setOf("and", "or", "div", "mod")
        fun isNameStart(c: Char) = c.isLetter() || c == '_'
        fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'
        fun nameEnd(from: Int): Int {
            var i = from
            while (i < locationPath.length && isNameChar(locationPath[i])) i++
            return i
        }

        val out = StringBuilder()
        var i = 0
        while (i < locationPath.length) {
            val c = locationPath[i]
            when {
                // A literal is copied verbatim: it may well hold a word that looks like an element name.
                c == '\'' || c == '"' -> {
                    val closing = locationPath.indexOf(c, i + 1)
                    val end = if (closing < 0) locationPath.length - 1 else closing
                    out.append(locationPath, i, end + 1)
                    i = end + 1
                }
                // An attribute test keeps its prefix; these schemas leave attributes unqualified.
                c == '@' -> {
                    out.append(c)
                    var j = i + 1
                    while (j < locationPath.length && (isNameChar(locationPath[j]) || locationPath[j] == ':')) j++
                    out.append(locationPath, i + 1, j)
                    i = j
                }
                // `*:name`, the CIN notation — a wildcard prefix on a real name test.
                c == '*' && i + 1 < locationPath.length && locationPath[i + 1] == ':' -> {
                    val end = nameEnd(i + 2)
                    if (end > i + 2) {
                        out.append("*[local-name()='").append(locationPath, i + 2, end).append("']")
                        i = end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                isNameStart(c) -> {
                    val end = nameEnd(i)
                    val name = locationPath.substring(i, end)
                    val next = locationPath.getOrNull(end)
                    val afterNext = locationPath.getOrNull(end + 1)
                    when {
                        // An axis: `child::`, `descendant::`. Copied, the name test after it is rewritten.
                        next == ':' && afterNext == ':' -> {
                            out.append(name).append("::")
                            i = end + 2
                        }
                        // A prefixed name test: the prefix goes, the local name stays.
                        next == ':' -> {
                            val localEnd = nameEnd(end + 1)
                            if (localEnd > end + 1) {
                                out.append("*[local-name()='").append(locationPath, end + 1, localEnd).append("']")
                                i = localEnd
                            } else {
                                out.append(name)
                                i = end
                            }
                        }
                        // A function or node-type call, and the operator words: not name tests.
                        next == '(' || name in operators -> {
                            out.append(name)
                            i = end
                        }
                        else -> {
                            out.append("*[local-name()='").append(name).append("']")
                            i = end
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

}
