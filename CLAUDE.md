# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Freehealth Connector (FHC) is a Spring Boot middleware that exposes the Belgian **eHealth / MyCareNet** SOAP web
services as a REST API. It is a "massively multi-user" rewrite of the official eHealth connector: instead of running
one connector instance per healthcare professional with a locally installed keystore, it keeps many users' keystores
and SAML tokens in memory (Hazelcast) and serves them concurrently.

Everything downstream (SAML/STS authentication, end-to-end encryption, sealing, timestamping, KMEHR payloads) is
imposed by the eHealth platform specifications — the code mirrors those specs closely, which explains the volume of
generated JAXB classes and XSD/WSDL resources.

## Toolchain and build

Gradle 6.7 (wrapper) · Kotlin 1.3.50 · Spring Boot 2.0.9 · Jetty (Tomcat is excluded globally) · **Java 8 target**.

Build with a JDK 8 — Gradle 6.7 does not officially support recent JDKs, and Kotlin 1.3 is far older than the JDK 21
that is the machine default here:

```bash
export JAVA_HOME=/path/to/jdk8        # e.g. Temurin 8; Gradle 6.7 predates modern JDKs
./gradlew bootRun          # starts on port 8090 (the README's 8080 is stale — see application.properties)
./gradlew build            # compile + test
./gradlew test             # integration tests, see caveats below
./gradlew test --tests "org.taktik.freehealth.middleware.web.controllers.STSControllerTest"
./gradlew test --tests "*.STSControllerTest.requestToken"
./gradlew ktlint           # style check (not wired into `check`)
./gradlew ktlintFormat     # auto-fix
./gradlew dockerize -x test   # local image docker.taktik.be/icure/freehealth-connector:<version>
```

`dockerize` depends on `build`, so `-x test` is required unless you have the test setup below. It only builds a local
image — the push (and the `docker login docker.taktik.be` in `ci/cloudbuild.yaml`) happens **only** with
`-PdockerPush`, per `DockerJavaPlugin`. The base image `anapsix/alpine-java:8_jdk_unlimited` is amd64-only, so on
Apple Silicon Docker emulates it and warns `InvalidBaseImagePlatform`; the build still succeeds.

`./gradlew compileKotlin` under JDK 8 succeeds from a clean state in ~2.5 min (first run also downloads from
`maven.taktik.be` and `repo.ehealth.fgov.be`) and emits a long tail of harmless Kotlin warnings — unnecessary `!!`,
useless elvis operands, shadowed names. They are pre-existing; don't treat them as something your change introduced.

This working copy is a plain source download, **not a git checkout**. The `git-version` plugin therefore logs
`fatal: not a git repository` and `Could not get git version` and the project version falls back to `0.0` — the build
still succeeds, so ignore those lines.

API docs (Swagger 2 / springfox) are served at `/api/index.html` once running.

## Tests are live integration tests

Nearly every `*ControllerTest` boots the whole application (`@SpringBootTest(webEnvironment = RANDOM_PORT)`), uploads a
real PKCS#12 keystore and calls the **eHealth acceptance platform** over the network. They are not unit tests.

Prerequisites, none of which are in the repo (all gitignored):

- `src/test/resources/test.properties` — copy `test.template.properties` and fill in the SSIN / NIHII / password /
  name / CBE / IBAN of each test certificate. `EhealthTest` injects ~7 keystore slots (`keystore1..6`, `vaccinet`);
  a test referencing a slot you did not fill will fail on `@Value` resolution.
- Acceptance keystores named `<ssin>.acc-p12` (or `<nihii>.acc-p12` for medical houses / guard posts) in
  `src/test/resources/org/taktik/freehealth/middleware/`.
- MyCareNet license credentials (`mycarenet.license.username` / `.password`) in the technical properties file.

Consequence: **red tests do not imply a regression.** Compare against a baseline before concluding — that is what
`scripts/parse-test-results.sh` is for: run `./gradlew test --continue`, then the script parses
`build/test-results/test/*.xml` into a sorted `PASS/FAIL/ERROR/SKIP` list per branch for diffing.

The genuinely offline tests are the ones under `service/impl/` that build and validate payloads
(`CreatePrescriptionTest`, `InferPrescTypeTest`, `EagreementServiceUtilsTest`, `ValidatorTest`, …), plus
`AddressbookControllerOfflineTest`, which boots the app and hits `/ab/search/hcp` and `/v2/api-docs` — the criteria
are validated before any eHealth call, so fake auth headers are enough and no keystore or network is needed. That
one runs in ~30 s and is the quickest way to check the app still boots.

## Architecture

Three stacked layers, all under `src/main`:

**1. `org.taktik.freehealth.middleware` (Kotlin) — the REST middleware.**
One trio per eHealth domain: `web/controllers/XxxController.kt` → `service/XxxService.kt` (interface) →
`service/impl/XxxServiceImpl.kt`. Controllers are thin: they read the auth headers, delegate, and let
`web/ExceptionHandlers.kt` map exceptions to status codes (`TechnicalConnectorException` → its own category status,
missing keystore/token → 401, `SOAPFaultException` → 502). Route prefixes: `/sts`, `/recipe`, `/hub`, `/ehbox`,
`/ehboxV3`, `/efact`, `/eattest`, `/eattestv2`, `/eattestv3`, `/chap4`, `/mda`, `/mediprima`, `/mediprimaUma`,
`/eagreement`, `/genins`, `/tarif`, `/therlink`, `/gmd`, `/consultrn`, `/rnconsult`, `/consent`, `/vaccinnet`,
`/daas`, `/mhm`, `/ab`, `/apb`, `/crypto`, `/schematron`, `/rsw/fhir`, `/admin`.

**2. `org.taktik.connector` (mostly Java) — the connector itself, forked from the official eHealth connector.**
`technical/` = the plumbing every domain shares: `service/sts` (WS-Trust SAML), `service/etee` (end-to-end
encryption), `service/kgss`, `service/keydepot` (ETK), `service/seals`, `service/timestamp`, `service/sso`,
`ws/` (`GenericWsSender`), `config/` (`ConfigFactory`), `beid/`.
`business/` = one package per domain (`recipe`, `hubv3`, `chapterIV`, `mycarenet`, `genericasync`, `dmg`,
`consultrn`, `vaccinnet`, …), each exposing a `ServiceFactory` that builds the port for a given SOAP action.

**3. `be.*` (Java, ~8000 files) — generated JAXB bindings** for the eHealth/KMEHR/CIN XSDs, plus `be.apb.gfddpp`
(pharmacy/medication-scheme code). Treat these as generated artifacts: don't hand-edit or refactor them.

The typical call chain, e.g. sending an attestation:

```
EattestV3Controller → EattestV3ServiceImpl (Kotlin: builds the KMEHR/JAXB request, encrypts, signs)
  → org.taktik.connector.business.eattest.impl.EattestServiceImpl
    → business ServiceFactory.getAttestPort(samlToken, soapAction)
      → technical ws.ServiceFactory.getGenericWsSender().send(...)  → XmlResponse.asObject(...)
```

### Authentication and session state

There is no user database in the request path. Clients:

1. `POST /sts/keystore` with a PKCS#12 file → returns a `keystoreId` UUID. The keystore bytes live only in the
   Hazelcast `KEYSTORES` map (18 h TTL), never on disk.
2. `GET /sts/token/{quality}?ssin=…` with headers `X-FHC-keystoreId` + `X-FHC-passPhrase` → obtains a SAML token from
   the eHealth STS, cached in the `TOKENS` map (12 h TTL) → returns a `tokenId`.
3. Every business call carries `X-FHC-keystoreId`, `X-FHC-tokenId`, `X-FHC-passPhrase`.

`STSServiceImpl` also keeps a local Guava cache of decrypted `KeyStore` objects keyed by `(keystoreId, passPhrase)`.
Hazelcast maps (`HazelcastConfiguration.kt`) also cache ETKs and KGSS keys. Spring Security
(`SecurityConfig.kt`) currently permits all paths — the real access control is possession of a valid keystore.

`RemoteKeystoreService` / `pkcs11/` support signing with a keystore that stays on the client side (eID / remote
PKCS#11) instead of being uploaded.

### Environment configuration (acceptance vs production)

The connector's own configuration is *not* Spring configuration. `ConfigFactory` loads a classpath properties file,
default `/acpt/org.taktik.connector.technical.properties`, overridable with the system property
`org.taktik.connector.technical.config.location` (e.g. `-Dorg.taktik.connector.technical.config.location=/prod/org.taktik.connector.technical.properties`).
`src/main/resources/acpt/` and `prod/` each hold that file plus the trust stores. `STSService.isAcceptance()` simply
tests whether `endpoint.sts` contains `-acpt`.

The real `acpt/org.taktik.connector.technical.properties` is gitignored; `*.template.properties` sits next to it.
Fill in `mycarenet.license.username` / `mycarenet.license.password` with credentials matching the target environment.

`MiddlewareApplication.main` creates `/opt/ehealth/{acpt,prod}/` at startup and extracts the truststores there
(`KEYSTORE_DIR=/opt/ehealth/`), so that path must be writable before the first run. On macOS `/opt` is root-owned, so
running the jar directly dies with `FileNotFoundException: /opt/ehealth/acpt/caCertificateKeystore.jks` unless you
`sudo mkdir -p /opt/ehealth && sudo chown $(whoami) /opt/ehealth`. To avoid that, run the Docker image with named
volumes instead:

```bash
docker volume create fhc-ehealth && docker volume create fhc-tmp
docker run -d --name fhc -p 8090:8090 -v fhc-ehealth:/opt/ehealth -v fhc-tmp:/tmp \
  docker.taktik.be/icure/freehealth-connector:<version>
```

`fhc-tmp` matters: the connector caches the BCP endpoint list and TSL state in `/tmp`
(`…bcp.EndpointUpdater.xml`, `…tsl.TrustStoreUpdater.properties`), and the Dockerfile otherwise mounts an anonymous
volume there that is discarded on every run. With it persisted, the second startup no longer logs
`Unable to load endpoints`. It boots in ~28 s and `/actuator/health` returns `{"status":"UP"}` with, for each
indicator, a `details` block: `uptime` (`UptimeHealthIndicator` — JVM uptime, human readable and in millis, plus the
start time), `diskSpace` and `db`. That nesting comes from `management.endpoint.health.show-details=always`; drop the
property and the answer collapses back to the bare status. `/actuator/**` is `permitAll`, so those details are
unauthenticated.

Spring-side config is `src/main/resources/application.properties` (port 8090, `spring.application.name=fhc`) plus
`icure.hazelcast.*` and CouchDB properties for the admin/login side.

### MyCareNet / CIN licence

Every MyCareNet domain needs a CIN licence (a username + password pair). Services resolve it in this order
(`Chapter4ServiceImpl.kt:203`, same shape in Dmg / Tarification / Eattest / Mhm):

```kotlin
username = principal?.mcnLicense ?: config.getProperty("mycarenet.license.username")
```

and `config.getProperty` goes through `SystemOverridenProperties`, which reads `System.getProperty(key)` **before** the
file. So: authenticated user › `-D` system property › properties file.

1. **`-D` at runtime** (best for secrets, nothing on disk):
   `-Dmycarenet.license.username=… -Dmycarenet.license.password=…`. In Docker, pass them through `-e JAVA_OPTS="…"`,
   since the entrypoint runs `exec java $JAVA_OPTS … -jar`.
2. **The properties file**, line 238 of `{acpt,prod}/org.taktik.connector.technical.properties`. **Do not put the
   licence here**: this file is *tracked* in the upstream repo (the `.gitignore` entry only covers files git does not
   already follow), so a real licence would be published on the next push. Use option 1 instead. All the derived keys
   (`chapterIV.`, `dmg.`, `genins.`, `mcn.tarification.`, `genericasync.*`) already interpolate
   `${mycarenet.license.*}`, so one pair covers every domain. Note these files live in `src/main/resources`, so the
   licence is **baked into the jar at build time** — change it and you must rebuild the jar *and* the image.
   `acpt/…properties` is gitignored; **`prod/…properties` is not**, so never put the production password there.
3. **Tests**: `src/test/resources/org/taktik/freehealth/middleware/mycarenet.license` holds the *password only*, as raw
   text, read by `EattestV2/V3ControllerTest` into the `mycarenet.license.password` system property. Write it with no
   trailing newline (`printf '%s' …`): `readText()` does not trim, and only the `McnConfigUtil` path trims later.
4. **Per user** (multi-tenant): `User.mcnLicense` / `mcnPassword` / `mcnPackageName`, from a CouchDB document, or from
   `freehealth.authentication.mcn-license` / `.mcn-password` for the single configured user.

The CIN ties the licence to a registered package name — `package.name=Freehealth-Connector` / `package.id=fhc`
(line 236). A mismatch gets calls rejected even with valid credentials. `apb.license.*` and `ftm.license.*` (pharmacy,
`prod` only) are separate licences and are **not** used in this deployment.

### Calling eAttest v3 (verified against acceptance)

The full chain works: `POST /sts/keystore` → `GET /sts/token/physiotherapist` → `POST /eattestv3/send/{patientSsin}/verbose`.
Hard-won details:

- The token's SAML attributes already carry the practitioner's identity — `nihii:physiotherapist:nihii11`, `givenname`,
  `surname`. Read them instead of asking the user for the NIHII.
- The `date` **query parameter** is `yyyyMMddHHmmss` (14 digits), unlike `date` inside each `Eattest.EattestCode`,
  which is `yyyyMMdd`. Passing 8 digits fails with `Value 0 for monthOfYear must be in the range [1,12]`.
- **Prefer omitting `date` entirely.** The KMEHR `request/id` is `<nihii>.<refDateTime>` where
  `refDateTime = dateTime(date) ?: now` (`EattestV3ServiceImpl.kt:602`), while `CommonInput.inputReference` is always
  `now` on the server clock (`InputReference()`). Supplying a `date` from another clock/timezone desynchronises them and
  MyCareNet answers `/SendTransactionRequest/request/id — La valeur de l'identification de la requête est incohérente
  avec celle du Web Service`.
- A 500 `Error while executing web service call` with `Activating switch mechanism` in the logs hides the real cause:
  the eHealth ESB returned a `SOA-01001/01002/02001/02002` fault, the connector failed over to the BCP endpoint
  (`pilot.mycarenet.be`), which is inactive and answers 404, and only that 404 surfaces.
  **Disable the failover to see the actual fault** — it is worth doing routinely in development:
  `-Dbe.ehealth.technicalconnector.handler.message.level.retry.activated=false` turns the opaque 500 into e.g.
  `502 — SOA-02001: Service is not available. Please contact service desk.` (platform outage, nothing to fix locally).
  Raising log levels does not help: the SOAP body is not logged even at DEBUG.

### Calling MDA (Member Data)

`GET /mda/{ssin}` requests four default facets (`MemberDataServiceImpl.kt:815`), including
`urn:be:cin:nippin:referencePharmacy` — which a physiotherapist is **not authorised to read**. The call then fails with
a SAML `AttributeQueryError` / `UNAUTHORIZED_FACET`. Use `POST /mda/{ssin}` instead and pass only the facets the
profession may read; for insurability that is:

```json
[{"id":"urn:be:cin:nippin:insurability",
  "dimensions":[{"id":"requestType","value":"information"},{"id":"contactType","value":"other"}]}]
```

This returns `Success` with the `patientData`, `period` (CT1/CT2, mutuality, registrationNumber) and `payment`
assertions. Verified against scenario 1 of the CIN test procedure.

Two traps when reading an MDA failure:

- **Facet ids must be the full URN.** `MemberDataServiceImpl.kt:814` is `facets ?: listOf(defaults)` — a client-supplied
  facet id goes into the `AttributeQuery` verbatim, with no prefixing. Sending `"insurability"` instead of
  `urn:be:cin:nippin:insurability` gets the whole query rejected.
- **Read `detailCode`, never `msgFr`.** The error text is rendered locally from `be/errors/MemberDataErrors.json`
  against the `detailCode` MyCareNet returned, and that catalogue is a translation of the CIN table, not the CIN's own
  words — upstream, uid 23 (`UNKNOWN_FACET`, *"A requested facet does not exist"*) carries a French message
  copy-pasted from uid 25 (`UNAUTHORIZED_FACET`), so an unknown facet reads as an access-rights refusal and the only
  tell is the article. That one entry is fixed here, but the same class of mistranslation may sit in the others.
  `MycarenetError` also carries `path` — indexed per facet and per dimension, e.g.
  `Facet[urn:be:cin:nippin:insurability]/Dimension[requestType]` — and `value`, which `extractError` fills with the
  offending node from your own request (`MemberDataServiceImpl.kt:912`). Those name what the message never does.

`requestType` and `hcpQuality` do not affect this. The `requestType` query parameter is only consumed by the default
facet list (line 819) — passing a `facets` body ignores it — and `hcpQuality` goes into `CommonInput`/`origin`
(`buildOriginType`, line 533), never into the `AttributeQuery`, which identifies the practitioner solely through
`issuer` = `urn:be:cin:nippin:nihii11` + the NIHII padded to 11 digits.

**The coverage window is `date` / `endDate`, in epoch milliseconds.** Unlike `requestType`, those two query parameters
are honoured whether the facets come from the body or from the defaults: `getAttrQuery` always writes them to
`Subject/SubjectConfirmation/SubjectConfirmationData/@NotBefore` and `@NotOnOrAfter`
(`MemberDataServiceImpl.kt:862-872`) — the XPath the catalogue indexes uid 53 to 59 on. There is no facet `Dimension`
for the period.

The trap is the unit. `MemberDataController.kt:71` does `Instant.ofEpochMilli(date)`, on all six MDA routes — while
eAttest v3 takes `yyyyMMddHHmmss` and `MapperConfiguration` serializes JSON dates as `yyyyMMdd` numbers. So a
`yyyyMMdd` value lands ~20 million ms after the epoch, i.e. **1970-01-01**: `date=20210101` returns
`PERIOD_TOO_FAR_IN_PAST` (uid 59, *"Request more than 5 years in the past"*) and `date=20260801&endDate=20260831` asks
for a **30 ms** window in 1970, answering `Success` with zero assertions and no error at all. Pass real millis
(`2021-08-25` = `1629849600000`), and pass **both**: the `endDate` default is `truncate(startDate to day) + 1 day`
(line 83), so a lone `date` five years back asks for one day five years ago, not the last five years.

The bounds come back on the same assertions, as `Assertion/Conditions/@NotBefore` / `@NotOnOrAfter` — no extra facet.
Two `Success` / `PartialAnswer` warnings matter once the window widens and must not be read as plain success: uid 96
`ONLY_FIVE_PERIODS_RETURNED` (more than five insurability periods exist, five were returned) and uid 68 `MUTATION`
(the member changed mutuality during the window).

Test data for physiotherapists lives in the CIN test procedure PDFs (scenario 8 is the nominal accepted case:
NISS2 + accord REF2 + codes `567011` and `567033`).

### eAgreement: this connector speaks v1 only

`AgreementServiceImpl.kt:94` hard-codes the SOAP actions
`urn:be:fgov:ehealth:mycarenet:agreement:protocol:**v1**:AskAgreement` / `:ConsultAgreement`, and
`endpoint.agreement` points at `.../MyCareNet/eAgreement/v1` — even though the payload builder is FHIR-based
(`Bundle`, `Claim`, `agreement-types`, `nihdi-physiotherapy-pathologysituationcode`), which is v2 vocabulary.
Measured against acceptance with a physiotherapist certificate:

- against `/eAgreement/v1` → `SOA-01002: Service call not authorized` (fault `Origin: Consumer`)
- against `/eAgreement/v2` (overridden via `-Dendpoint.agreement=…`) → `SOA-03004: WS-I compliance failure`,
  i.e. the endpoint exists but rejects the v1 SOAP action/namespace.

So eAgreement **v2**, the version the CIN physiotherapist test procedure targets, needs the v2 protocol binding, not
just a URL change. The port is small and every ingredient is already here (measured against the official
`connector-packaging-generic-5.1.0-java`, which ships `business-mycareneteagreementv2`):

- v2 keeps the **same two operations** (`askAgreement`, `consultAgreement`); only these change:
  SOAP actions `…agreement:protocol:**v2**:{Ask,Consult}Agreement`, config key **`endpoint.agreement2`**
  (UDDI default `$uddi{uddi:ehealth-fgov-be:business:mycareneteagreement:v2}`), and the payload types move from
  `commons.protocol.v3` to `commons.protocol.**v4**`.
- `be.fgov.ehealth.mycarenet.commons.protocol.v4` / `commons.core.v4` are **already in this repo** (eAttest v3 uses
  them), so nothing has to be generated for them.
- The five JAXB wrappers (`{Ask,Consult}Agreement{Request,Response}`, `ObjectFactory`) are 11-line classes extending
  `SendRequestType`/`SendResponseType` — write them from `mycarenet-agreement-protocol-2_0.xsd` using the existing v1
  files as the template. Official package name is `be.fgov.ehealth.mycarenet.agreement.protocol.v2` (note: v1 here
  lives under `be.fgov.ehealth.agreement.protocol.v1`, without `mycarenet`).

**The port is done** (`org.taktik.connector.business.agreementv2`, JAXB wrappers under
`be.fgov.ehealth.mycarenet.agreement.protocol.v2`, `EagreementServiceImpl` routed to it). Measured result on
`consultList` against acceptance:

| binding sent | endpoint | answer |
|---|---|---|
| v1 SOAP action | `/eAgreement/v1` | `SOA-01002` not authorized |
| v1 SOAP action | `/eAgreement/v2` | `SOA-03004` WS-I compliance failure |
| **v2 SOAP action** | `/eAgreement/v2` | **`SOA-01002` not authorized** |

`SOA-03004` is gone, so the v2 message is accepted as conformant. One more fix was needed: mycarenet commons v4 adds a
mandatory `MessageVersion` attribute on the Detail element which the v4 `BlobMapper` never copied — without it the
service answers `INVALID_DETAIL_REQUEST: messageVersion invalid (mandatory)`. eAgreement sets it to `V4`
(`EagreementServiceImpl.MESSAGE_VERSION`), the value the CIN message definition mandates.

**askAgreement then completes end to end**: HTTP 200, `acknowledged: true`, a NIP reference in `commonOutput`, and a
signed FHIR `be-eagreementdemandreply` from the insurer. No decision comes back synchronously — per the CIN scenarios
the medical adviser's ruling arrives over the async channel, which is still the second batch of work.

Two things remain:

- `consultAgreement` still answers `SOA-01002` while `askAgreement` succeeds, so **authorisation is granted per
  operation**: ask the CIN to enable ConsultAgreement for the licence.
- The insurer returns one FHIR *warning* on the request bundle: `MessageHeader.sender` references
  `PractitionerRole/PractitionerRole1` while that entry's `fullUrl` is a random `urn:uuid:…`, which breaks Bundle
  resolution rules. It does not block the request but should be fixed in `EagreementServiceUtilsImpl`.

The **async** channel is a separate second batch: the CIN scenarios have the medical adviser's decisions arriving
asynchronously, FHC points at `genericasync.eagreement.v1`, and the official packaging ships a distinct
`business-mycareneteagreementasyncv2` module.

### eFact

Transport works: `GET /efact/{nihii}/{language}` (GenAsync message pull) returns `200 []` with a physiotherapist
token — no SOA fault, the mailbox is simply empty until something is sent.

`POST /efact/flat/test` renders the 920000 flat file locally, with no network call and no auth headers — the fastest
way to validate an `InvoicesBatch` before sending it. Two fields are dereferenced with `!!` and are effectively
mandatory although the DTO types them nullable, each costing a bare `KotlinNullPointerException` (HTTP 500, no
message):

- `sender.phoneNumber` (`BelgianInsuranceInvoicingFormatWriter.kt:140`)
- `invoice.reason` — an `InvoicingTreatmentReasonCode`, use `"Other"` for ordinary care (`EfactServiceImpl.kt:155`)

`isTest = true` stamps field 304 with `9991999` instead of `1999`, which is how the OA tells a test batch apart.

### Addressbook and eHealthBox (read paths verified)

Both work out of the box with a physiotherapist token, no special configuration:

- `GET /ab/hcp/nihii/{nihii}` and `GET /ab/hcp/ssin/{ssin}` return the practitioner with `professionCodes`
  (`PHYSIOTHERAPIST`, authentic source `EHP`) and, keyed by NIHII, their `ehealthBoxes` entry — that is how you resolve
  a colleague's eHealthBox address before sending. `GET /ab/search/hcp/{lastName}` returns `[]` for acceptance test
  practitioners: they are not in the search index, so do not read an empty list as a failure.
- `GET /ab/search/hcp` is the broad search: every criterion is a query parameter and all are optional —
  `lastName`, `firstName`, `profession`, `nihii`, `ssin`, `zipCode`, `city`, `email`, `offset`, `limit`. The eHealth
  XSD declares `NIHII|SSIN` and `City|ZipCode` as `xs:choice`, so each pair is mutually exclusive (400 otherwise), and
  only the empty query is rejected — beyond that **the eHealth service decides**, see the table below. Omitting
  `profession` (or passing `ALL`) leaves the element out — the older `GET /ab/search/hcp/{lastName}` silently defaults
  it to `PHYSICIAN`. Names accept a `*` wildcard (`Steeman*`). The search response carries identity,
  `nihii`, `professionCodes` and `speciality` only — **no address and no eHealthBox**: `SearchProfessionalsResponse`
  returns `aa:HealthCareProfessional`, which has no address. Resolve those with `GET /ab/hcp/nihii/{nihii}` once the
  user picks a result.
- **What the addressbook actually accepts** (measured on acceptance with a physiotherapist token, 2026-08-24 — the
  XSD makes everything optional, the service does not). A refusal surfaces as
  `Requester - This combination of search criteria is not supported.`:

  The rule is `profession` **plus** either a name or a location, or else an identifier on its own:

  | criteria | result |
  |---|---|
  | `lastName` (+ `firstName`) + `profession` | OK |
  | `profession` + `zipCode` or + `city` | OK |
  | `nihii` alone, `ssin` alone | OK |
  | `profession` alone | refused |
  | `lastName` alone, `zipCode` alone (no profession) | refused |
  | name **and** location together | refused |
  | `firstName` without `lastName` | refused |
  | `email` | refused |

  So "search by name anywhere" and "search by zip code across professions" both need one call **per profession**;
  valid profession codes are `PHYSICIAN`, `DENTIST`, `PHARMACIST`, `PHYSIOTHERAPIST`, `NURSE`, `MIDWIFE`,
  `AUDIOLOGIST`, `DIETICIAN`, `LOGOPEDIST`, `ORTHOPEDIST`, `ORTHOPTIST`, `PODOLOGIST`, `PSYCHOLOGIST` — the eHealthBox
  `QualityType` list also holds `AUDICIEN`, `PRACTICALNURSE`, `OPTICIEN`, `LABO`, which the addressbook refuses.
  `maxElements` is capped at **100** (`The maxElements paging attribute is too high.` above that) and the protocol
  returns **no total**, so page with `offset` until a page holds fewer than `limit` rows (1150 has 311
  physiotherapists over 4 pages).
- `GET /ehboxV3` returns the box summary (`boxId`, `quality`, `nbrMessagesInStandBy`, `currentSize`/`maxSize`);
  `GET /ehboxV3/{boxId}?limit=n` lists messages, with `boxId` one of `INBOX`, `SENTBOX`, `BININBOX`. Errors surface in
  the response's own `error` field rather than as an HTTP status.

**NIHII mismatch to be aware of**: the SAML token advertises `…501` in `nihii:physiotherapist:nihii11` while the
address book returns `…527` for the same person (same 8-digit root, different qualification suffix). eAttest accepted
the token's value. Do not assume the two sources agree.

### Active scope

Only these domains are in use: **eAttest**, **eAgreement**, **eFact**, and **MDA / Member Data**. Planned later:
**eHealthBox (read-only)** and **Addressbook**. No pharmacy licence, so anything under `/apb`, FTM, or the
`be.apb.gfddpp` Java tree is out of scope.

End users are **physiotherapists**, so every call needs `quality=physiotherapist` — both when requesting the token
(`/sts/token/physiotherapist`) and as `hcpQuality` on the business call. Two silent fallbacks make a missing or
misspelled value degrade into a *doctor* request instead of an error: `EattestV3ServiceImpl.kt:345`
(`hcpQuality ?: … ?: "doctor"`) and `getRequestAuthorCdHcParty` (line 1578, unknown quality → `persphysician`).
eFact is the exception: it carries no `hcpQuality`, the profession is encoded in `InvoiceSender.professionCode` /
`nihii` in the batch payload. Prioritise accordingly — a change touching `/eattest*`, `/eagreement`,
`/efact` or `/mda` is load-bearing; the other controllers are not exercised today.

## Conventions and gotchas

- `compiled/` and `decompiled/` (~1.3 GB) are **reference copies of released official eHealth connector versions**
  (3.13.1 … 4.7.3, RECIPE.1.7 … 1.9.1), kept to diff behaviour when eHealth ships a new connector. They are not build
  output and not on the classpath — never scan, modify, or grep them wholesale.
- Kotlin style: 4 spaces, 120 columns (`.editorconfig`); `ktlint` is available but deliberately not part of `check`,
  and much of the existing code would not pass it. Match the surrounding file rather than reformatting.
- Legacy dependencies are used on purpose (Dozer + Orika mapping, Velocity, joda-time, Lucene, Hibernate/H2). Several
  are flagged `//WTF... This must go away` in `build.gradle` — don't spread them further, don't crusade against them.
- Domains are versioned by eHealth, hence parallel `Eattest`/`EattestV2`/`EattestV3`, `Ehbox`/`EhboxV3`,
  `MemberData`/`memberdatav2`, `Mediprima`/`mediprimav2`/`mediprimaUma`. A fix usually belongs in one specific
  version — check which one the caller uses before touching all of them.
- JSON dates are serialized as `yyyyMMdd` / `yyyyMMddHHmmss` **numbers** (`MapperConfiguration.kt`), not ISO strings.
- `genJaxb` exists in `build.gradle` but is dormant (its dependencies are commented out); JAXB classes are committed.
- Swagger 2 / springfox: only some controllers are annotated (`Addressbook`, `Hub`, `STS`, `Eagreement`, `Dmg`,
  `Crypto`), with `@ApiOperation(value, notes)` on the method and `@ApiParam` on each parameter. **`@ApiParam`
  defaults `required` to `false` and springfox lets that override what Spring deduced**, so a mandatory
  `@RequestHeader` or `@PathVariable` that carries a bare `@ApiParam("…")` is published as optional — pass
  `required = true` explicitly (`HubController` still shows the wrong behaviour). New paths must also match the
  regex in `SwaggerConfiguration.kt` or they never appear in the descriptor.
- Licensed under AGPL v3; keep the existing file headers.
