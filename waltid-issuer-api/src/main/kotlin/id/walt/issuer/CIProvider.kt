@file:Suppress("ExtractKtorModule")

package id.walt.issuer


import id.walt.credentials.issuance.Issuer.mergingJwtIssue
import id.walt.credentials.issuance.Issuer.mergingSdJwtIssue
import id.walt.credentials.vc.vcs.W3CVC
import id.walt.crypto.keys.Key
import id.walt.crypto.keys.KeySerialization
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.LocalKey
import id.walt.did.dids.DidService
import id.walt.issuer.IssuanceExamples.openBadgeCredentialExample
import id.walt.issuer.base.config.ConfigManager
import id.walt.issuer.base.config.OIDCIssuerServiceConfig
import id.walt.oid4vc.data.CredentialFormat
import id.walt.oid4vc.data.CredentialSupported
import id.walt.oid4vc.definitions.JWTClaims
import id.walt.oid4vc.errors.CredentialError
import id.walt.oid4vc.errors.DeferredCredentialError
import id.walt.oid4vc.interfaces.CredentialResult
import id.walt.oid4vc.providers.CredentialIssuerConfig
import id.walt.oid4vc.providers.IssuanceSession
import id.walt.oid4vc.providers.OpenIDCredentialIssuer
import id.walt.oid4vc.providers.TokenTarget
import id.walt.oid4vc.requests.BatchCredentialRequest
import id.walt.oid4vc.requests.CredentialRequest
import id.walt.oid4vc.responses.BatchCredentialResponse
import id.walt.oid4vc.responses.CredentialErrorCode
import id.walt.oid4vc.responses.CredentialResponse
import id.walt.oid4vc.util.randomUUID
import id.walt.sdjwt.SDMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.minutes

/**
 * OIDC for Verifiable Credential Issuance service provider, implementing abstract service provider from OIDC4VC library.
 */
open class CIProvider : OpenIDCredentialIssuer(
    baseUrl = let {
        ConfigManager.getConfig<OIDCIssuerServiceConfig>().baseUrl
    }, config = CredentialIssuerConfig(credentialConfigurationsSupported = mapOf(
//        "VerifiableCredential" to listOf("VerifiableCredential"),
        "BankId" to listOf("VerifiableCredential", "BankId"),
        "KycChecksCredential" to listOf("VerifiableCredential", "VerifiableAttestation", "KycChecksCredential"),
        "KycDataCredential" to listOf("VerifiableCredential", "VerifiableAttestation", "KycDataCredential"),
        "PassportCh" to listOf("VerifiableCredential", "VerifiableAttestation", "VerifiableId", "PassportCh"),
        "PND91Credential" to listOf("VerifiableCredential", "PND91Credential"),
        "MortgageEligibility" to listOf(
            "VerifiableCredential",
            "VerifiableAttestation",
            "VerifiableId",
            "MortgageEligibility"
        ),
        "PortableDocumentA1" to listOf("VerifiableCredential", "VerifiableAttestation", "PortableDocumentA1"),
        "OpenBadgeCredential" to listOf("VerifiableCredential", "OpenBadgeCredential"),
        "VaccinationCertificate" to listOf(
            "VerifiableCredential",
            "VerifiableAttestation",
            "VaccinationCertificate"
        ),
        "WalletHolderCredential" to listOf("VerifiableCredential", "WalletHolderCredential"),
        "UniversityDegree" to listOf("VerifiableCredential", "UniversityDegree"),
        "VerifiableId" to listOf("VerifiableCredential", "VerifiableAttestation", "VerifiableId"),
        "CTWalletSameAuthorisedInTime" to listOf("VerifiableCredential", "VerifiableAttestation", "CTWalletSameAuthorisedInTime"),
        "CTWalletSameAuthorisedDeferred" to listOf("VerifiableCredential", "VerifiableAttestation", "CTWalletSameAuthorisedDeferred")
    ).flatMap { entry ->
        CredentialFormat.values().map { format ->
            CredentialSupported(
                id = "${entry.key}_${format.value}",
                format = format,
                cryptographicBindingMethodsSupported = setOf("did"),
                cryptographicSuitesSupported = setOf("EdDSA", "ES256", "ES256K", "RSA"),
                types = entry.value
            )
        }
    }.associateBy { it.id })
) {
    companion object {

        val exampleIssuerKey by lazy { runBlocking { LocalKey.generate(KeyType.Ed25519) } }
        val exampleIssuerDid by lazy { runBlocking { DidService.registerByKey("jwk", exampleIssuerKey).did } }


        private val CI_TOKEN_KEY by lazy { runBlocking { LocalKey.generate(KeyType.Ed25519) } }

    }

    // -------------------------------
    // Simple in-memory session management
    private val authSessions: MutableMap<String, IssuanceSession> = mutableMapOf()


    var deferIssuance = false
    val deferredCredentialRequests = mutableMapOf<String, CredentialRequest>()
    override fun getSession(id: String): IssuanceSession? {
        println("RETRIEVING CI AUTH SESSION: $id")
        return authSessions[id]
    }

   override fun getSessionByIdTokenRequestState(idTokenRequestState: String): IssuanceSession? {
        println("RETRIEVING CI AUTH SESSION by idTokenRequestState: $idTokenRequestState")
        var properSession: IssuanceSession? = null
        authSessions.forEach { entry ->
            print("${entry.key} : ${entry.value}")
            val session = entry.value as IssuanceSession
            if (session.idTokenRequestState == idTokenRequestState) {
                properSession = session
            }
        }
        return properSession
    }


    override fun putSession(id: String, session: IssuanceSession): IssuanceSession? {
        println("SETTING CI AUTH SESSION: $id = $session")
        return authSessions.put(id, session)
    }

    override fun removeSession(id: String): IssuanceSession? {
        println("REMOVING CI AUTH SESSION: $id")
        return authSessions.remove(id)
    }


    // ------------------------------------------
    // Simple cryptographics operation interface implementations
    override fun signToken(target: TokenTarget, payload: JsonObject, header: JsonObject? , keyId: String?) =
        runBlocking {
            println("Signing JWS:   $payload")
            println("JWS Signature: target: $target, keyId: $keyId, header: $header")
            if (header != null && keyId != null) {
                val myPrivateKey = LocalKey.importJWK("{\"kty\":\"EC\",\"x\":\"bo4FsmViF9au5-iCZbvEy-WZGaRes_eZdpIucmg4XH8\",\"y\":\"htYUXUmIc-IxyR6QMFPwXHXAgj__Fqw9kuSVtSyulhI\",\"crv\":\"P-256\",\"d\":\"UPzeJStN6Wg7zXULIlGVhYh4gG5RN-5knejePt6deqY\"}")
                val headers = mapOf("alg" to "ES256", "type" to "jwt", "kid" to keyId)

                myPrivateKey.getOrThrow().signJws(payload.toString().toByteArray(), headers).also {
                    println("Signed JWS: >> $it")
                }

            } else {
                CI_TOKEN_KEY.signJws(payload.toString().toByteArray()).also {
                    println("Signed JWS: >> $it")
                }
            }


        }

    @OptIn(ExperimentalEncodingApi::class)
    override fun verifyTokenSignature(target: TokenTarget, token: String) = runBlocking {
        println("Verifying JWS: $token")
        println("JWS Verification: target: $target")

        val tokenHeader = Json.parseToJsonElement(Base64.decode(token.split(".")[0]).decodeToString()).jsonObject
        if (tokenHeader["kid"] != null) {
            val did = tokenHeader["kid"]!!.jsonPrimitive.content.split("#")[0]
            println("Resolving DID: $did")
            val key = DidService.resolveToKey(did).getOrThrow()
            println("Got key: $key")
            key.verifyJws(token)
        } else {
            CI_TOKEN_KEY.verifyJws(token)
        }
    }.isSuccess

    // -------------------------------------
    // Implementation of abstract issuer service provider interface
    @OptIn(ExperimentalEncodingApi::class)
    override fun generateCredential(credentialRequest: CredentialRequest): CredentialResult {
        println("GENERATING CREDENTIAL:")
        println("Credential request: $credentialRequest")
        println("CREDENTIAL REQUEST JSON -------:")
        println(Json.encodeToString(credentialRequest))

        val jwt = credentialRequest.proof?.jwt ?: throw IllegalArgumentException("No proof.jwt in credential request!")
        val jwtParts = jwt.split(".")

        fun decodeJwtPart(idx: Int) = Json.parseToJsonElement(Base64.decode(jwtParts[idx]).decodeToString()).jsonObject

        val header = decodeJwtPart(0)
        val payload = decodeJwtPart(1)

        val subjectDid =
            header["kid"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("No kid in proof.jwt header!")
        val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("No nonce in proof.jwt payload!")


        if (deferIssuance) return CredentialResult(credentialRequest.format, null, randomUUID()).also {
            deferredCredentialRequests[it.credentialId!!] = credentialRequest
        }
        return doGenerateCredential(credentialRequest, subjectDid, nonce)/*.also {
            // for testing purposes: defer next credential if multiple credentials are issued
            deferIssuance = !deferIssuance
        }*/
    }

    override fun getDeferredCredential(credentialID: String): CredentialResult {
        if (deferredCredentialRequests.containsKey(credentialID)) {
            return doGenerateCredential(
                deferredCredentialRequests[credentialID]!!, null, null
            ) // TODO: the null parameters
        }
        throw DeferredCredentialError(CredentialErrorCode.invalid_request, message = "Invalid credential ID given")
    }

    private fun doGenerateCredential(
        credentialRequest: CredentialRequest, subjectDid: String?, nonce: String?
    ): CredentialResult {
        if (credentialRequest.format == CredentialFormat.mso_mdoc) throw CredentialError(
            credentialRequest, CredentialErrorCode.unsupported_credential_format
        )
        /*val types = credentialRequest.types ?: credentialRequest.credentialDefinition?.types ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.unsupported_credential_type
        )*/
        val proofHeader = credentialRequest.proof?.jwt?.let { parseTokenHeader(it) } ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof must be JWT proof"
        )
        val holderKid = proofHeader[JWTClaims.Header.keyID]?.jsonPrimitive?.content ?: throw CredentialError(
            credentialRequest,
            CredentialErrorCode.invalid_or_missing_proof,
            message = "Proof JWT header must contain kid claim"
        )

        val proofPayload = credentialRequest.proof?.jwt?.let { parseTokenPayload(it) } ?: throw CredentialError(
            credentialRequest, CredentialErrorCode.invalid_or_missing_proof, message = "Proof must be JWT proof"
        )

        val holderDid = proofPayload[JWTClaims.Payload.issuer]?.jsonPrimitive?.content ?: throw CredentialError(
            credentialRequest,
            CredentialErrorCode.invalid_or_missing_proof,
            message = "Proof JWT payload must contain iss claim"
        )
        //val vc = W3CVC(universityDegreeCredentialExample.toList().associate { it.first to it.second.toJsonElement() })

        val data: IssuanceSessionData = (if (subjectDid == null || nonce == null) {
            repeat(10) {
                println("WARNING: RETURNING DEMO/EXAMPLE (= BOGUS) CREDENTIAL: subjectDid or nonce is null (was deferred issuance tried?)")
            }
            listOf(
                IssuanceSessionData(
                    exampleIssuerKey,
                    exampleIssuerDid,
                    IssuanceRequest(
                        Json.parseToJsonElement(KeySerialization.serializeKey(exampleIssuerKey)).jsonObject,
                        exampleIssuerDid,
                        "OpenBadgeCredential_${credentialRequest.format.value}",
                        W3CVC(openBadgeCredentialExample)
                    )
                )
            )
        } else {
            println("RETRIEVING VC FROM TOKEN MAPPING: $nonce")
            tokenCredentialMapping[nonce]
                ?: throw IllegalArgumentException("The issuanceIdCredentialMapping does not contain a mapping for: $nonce!")
        }).first()

//        println("IssuerDID: " + issuerKey)
        return CredentialResult(format = credentialRequest.format, credential = JsonPrimitive(runBlocking {
            val vc = data.request.credentialData

            data.run {
                when (credentialRequest.format) {
                    CredentialFormat.sd_jwt_vc -> vc.mergingSdJwtIssue(
                        issuerKey = issuerKey,
                        issuerDid = issuerDid,
                        // Why DID=KID?
                        // https://identity.foundation/jwt-vc-presentation-profile/#jwt-vc
                        subjectDid = holderDid,
                        mappings = request.mapping ?: JsonObject(emptyMap()),
                        additionalJwtHeader = emptyMap(),
                        additionalJwtOptions = emptyMap(),
                        disclosureMap = data.request.selectiveDisclosure ?: SDMap.Companion.generateSDMap(
                            JsonObject(emptyMap()),
                            JsonObject(emptyMap())
                        )
                    )
                    else -> vc.mergingJwtIssue(
                        issuerKey = issuerKey,
                        issuerDid = issuerDid,
                        subjectDid = holderDid,
                        mappings = request.mapping ?: JsonObject(emptyMap()),
                        additionalJwtHeader = emptyMap(),
                        additionalJwtOptions = emptyMap(),
                    )

                }
            }.also { println("Respond VC: $it") }
        }))
    }


    @OptIn(ExperimentalEncodingApi::class)
    override fun generateBatchCredentialResponse(
        batchCredentialRequest: BatchCredentialRequest,
        accessToken: String
    ): BatchCredentialResponse {
        if (batchCredentialRequest.credentialRequests.map { it.format }.distinct().size >= 2) {
            throw IllegalArgumentException("Credential request don't have the same format")
        }

        val keyIdsDistinct = batchCredentialRequest.credentialRequests.map { credReq ->
            credReq.proof?.jwt?.let { jwt -> parseTokenHeader(jwt) }
                ?.get(JWTClaims.Header.keyID)
                ?.jsonPrimitive?.content
                ?: throw CredentialError(
                    credReq,
                    CredentialErrorCode.invalid_or_missing_proof,
                    message = "Proof must be JWT proof"
                )
        }.distinct()

        if (keyIdsDistinct.size >= 2) {
            throw IllegalArgumentException("More than one key id requested")
        }

        val keyId = keyIdsDistinct.first()




        batchCredentialRequest.credentialRequests.first().let { credentialRequest ->
            val jwt =
                credentialRequest.proof?.jwt ?: throw IllegalArgumentException("No proof.jwt in credential request!")
            val jwtParts = jwt.split(".")

            fun decodeJwtPart(idx: Int) =
                Json.parseToJsonElement(Base64.decode(jwtParts[idx]).decodeToString()).jsonObject

            val header = decodeJwtPart(0)
            val payload = decodeJwtPart(1)

            val subjectDid =
                header["kid"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("No kid in proof.jwt header!")
            val nonce = payload["nonce"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("No nonce in proof.jwt payload!")

            println("RETRIEVING VC FROM TOKEN MAPPING: $nonce")
            val issuanceSessionData = tokenCredentialMapping[nonce]
                ?: throw IllegalArgumentException("The issuanceIdCredentialMapping does not contain a mapping for: $nonce!")

            val credentialResults = issuanceSessionData.map { data ->
                CredentialResponse.success(
                    format = credentialRequest.format,
                    credential = JsonPrimitive(
                        runBlocking {
                            val vc = data.request.credentialData

                            data.run {
                                when (credentialRequest.format) {
                                    CredentialFormat.sd_jwt_vc -> vc.mergingSdJwtIssue(
                                        issuerKey = issuerKey,
                                        issuerDid = issuerDid,
                                        subjectDid = subjectDid,
                                        mappings = request.mapping ?: JsonObject(emptyMap()),
                                        additionalJwtHeader = emptyMap(),
                                        additionalJwtOptions = emptyMap(),
                                        disclosureMap = data.request.selectiveDisclosure
                                            ?: SDMap.Companion.generateSDMap(
                                                JsonObject(emptyMap()),
                                                JsonObject(emptyMap())
                                            )
                                    )
                                    else -> vc.mergingJwtIssue(
                                        issuerKey = issuerKey,
                                        issuerDid = issuerDid,
                                        subjectDid = subjectDid,
                                        mappings = request.mapping ?: JsonObject(emptyMap()),
                                        additionalJwtHeader = emptyMap(),
                                        additionalJwtOptions = emptyMap(),
                                    )
                                }

                            }.also { println("Respond VC: $it") }
                        }
                    )
                )
            }

            return BatchCredentialResponse.success(credentialResults, accessToken, 5.minutes)
        }
    }


    data class IssuanceSessionData(
        val issuerKey: Key, val issuerDid: String, val request: IssuanceRequest
    )

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    val sessionCredentialPreMapping = HashMap<String, List<IssuanceSessionData>>() // session id -> VC

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    private val tokenCredentialMapping = HashMap<String, List<IssuanceSessionData>>() // token -> VC

    //private val sessionTokenMapping = HashMap<String, String>() // session id -> token

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    fun setIssuanceDataForIssuanceId(issuanceId: String, data: List<IssuanceSessionData>) {
        println("DEPOSITED CREDENTIAL FOR ISSUANCE ID: $issuanceId")
        sessionCredentialPreMapping[issuanceId] = data
    }

    // TODO: Hack as this is non stateless because of oidc4vc lib API
    fun mapSessionIdToToken(sessionId: String, token: String) {
        println("MAPPING SESSION ID TO TOKEN: $sessionId -->> $token")
        val premappedVc = sessionCredentialPreMapping.remove(sessionId)
            ?: throw IllegalArgumentException("No credential pre-mapped with any such session id: $sessionId (for use with token: $token)")
        println("SWAPPING PRE-MAPPED VC FROM SESSION ID TO NEW TOKEN: $token")
        tokenCredentialMapping[token] = premappedVc
    }

    fun mapSessionIdToIdAuthRequestState(sessionId: String, token: String) {
        println("MAPPING SESSION ID TO TOKEN: $sessionId -->> $token")
        val premappedVc = sessionCredentialPreMapping.remove(sessionId)
            ?: throw IllegalArgumentException("No credential pre-mapped with any such session id: $sessionId (for use with token: $token)")
        println("SWAPPING PRE-MAPPED VC FROM SESSION ID TO NEW TOKEN: $token")
        tokenCredentialMapping[token] = premappedVc
    }
}
