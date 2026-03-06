/*
 * Copyright (c) 2025 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.euidi.verifier.core.controller

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Base64
import androidx.activity.ComponentActivity
import com.android.identity.android.mdoc.deviceretrieval.VerificationHelper
import com.android.identity.android.mdoc.transport.DataTransportOptions
import eu.europa.ec.eudi.verifier.core.EudiVerifier
import eu.europa.ec.eudi.verifier.core.EudiVerifierConfig
import eu.europa.ec.eudi.verifier.core.request.DeviceRequest
import eu.europa.ec.eudi.verifier.core.request.DocRequest
import eu.europa.ec.eudi.verifier.core.response.DeviceResponse
import eu.europa.ec.euidi.verifier.core.extension.flattenedClaims
import eu.europa.ec.euidi.verifier.core.provider.ActivityProvider
import eu.europa.ec.euidi.verifier.core.provider.ResourceProvider
import eu.europa.ec.euidi.verifier.domain.config.model.ClaimItem
import eu.europa.ec.euidi.verifier.domain.config.model.Logger
import eu.europa.ec.euidi.verifier.domain.model.DocumentValidityDomain
import eu.europa.ec.euidi.verifier.domain.model.ReceivedDocumentDomain
import eu.europa.ec.euidi.verifier.domain.model.ReceivedDocumentsDomain
import eu.europa.ec.euidi.verifier.presentation.model.RequestedDocumentUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.engagement.EngagementParser
import org.multipaz.mdoc.nfc.mdocReaderNfcHandover
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.response.DeviceResponseParser
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.nfc.NfcIsoTagAndroid
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class AndroidTransferController(
    private val context: Context,
    private val resourceProvider: ResourceProvider
) : TransferController {

    private lateinit var eudiVerifier: EudiVerifier
    private var verificationHelper: VerificationHelper? = null
    private var pendingDeviceRequest: DeviceRequest? = null
    private var readerModeEnabled: Boolean = false
    private var connectionMethods: List<MdocConnectionMethod> = emptyList()
    private var dataTransportOptions: DataTransportOptions =
        DataTransportOptions.Builder().build()
    private var bleUseL2Cap: Boolean = false
    private var scope: CoroutineScope? = null
    private var nfcSession: NfcSession? = null
    private val nfcTagProcessing = AtomicBoolean(false)

    private val _statuses = MutableSharedFlow<TransferStatus>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    private val statuses: SharedFlow<TransferStatus> = _statuses.asSharedFlow()

    override fun initializeVerifier(certificates: List<String>, logger: Logger) {
        if (::eudiVerifier.isInitialized.not()) {

            eudiVerifier = EudiVerifier(
                context = context,
                config = EudiVerifierConfig {
                    configureLogging(level = logger.level)
                }
            ) {

                val x509Certificates = certificates.mapNotNull {
                    pemToX509Certificate(it).getOrNull()
                }

                trustedCertificates(
                    certificatesProvided = x509Certificates
                )
            }
        }
    }

    override fun initializeTransferManager(
        bleCentralClientMode: Boolean,
        blePeripheralServerMode: Boolean,
        useL2Cap: Boolean,
        clearBleCache: Boolean
    ) {
        val configuredConnectionMethods = listOf<MdocConnectionMethod>(
            MdocConnectionMethodBle(
                supportsPeripheralServerMode = blePeripheralServerMode,
                supportsCentralClientMode = bleCentralClientMode,
                peripheralServerModeUuid = UUID.randomUUID(),
                centralClientModeUuid = UUID.randomUUID()
            )
        )

        val disambiguatedConnectionMethods = MdocConnectionMethod.disambiguate(
            configuredConnectionMethods,
            MdocRole.MDOC_READER
        ).ifEmpty { configuredConnectionMethods }

        // NFC negotiated handover interoperability is better when the reader offers one
        // unambiguous BLE method.
        this.connectionMethods = listOf(
            disambiguatedConnectionMethods.preferredReaderBleMethod()
                ?: disambiguatedConnectionMethods.first()
        )

        dataTransportOptions = DataTransportOptions.Builder()
            .setBleUseL2CAP(useL2Cap)
            .setBleClearCache(clearBleCache)
            .build()
        bleUseL2Cap = useL2Cap

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun startEngagement(qrCode: String) {
        disableNfcReaderMode()
        closeNfcSessionAsync()
        verificationHelper = buildVerificationHelper(enableNfcNegotiatedHandover = false).also { helper ->
            helper.setDeviceEngagementFromQrCode(qrCode)
        }
    }

    override fun startNfcEngagement() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        val activity = ActivityProvider.currentActivity as? ComponentActivity

        when {
            connectionMethods.isEmpty() -> {
                _statuses.tryEmit(
                    TransferStatus.Error(
                        "TransferManager is not initialized. Call initializeTransferManager() first."
                    )
                )
            }

            nfcAdapter == null -> {
                _statuses.tryEmit(
                    TransferStatus.Error("NFC is not available on this device.")
                )
            }

            activity == null -> {
                _statuses.tryEmit(
                    TransferStatus.Error("Missing foreground activity for NFC engagement.")
                )
            }

            else -> {
                runCatching {
                    verificationHelper?.disconnect()
                    verificationHelper = null
                    closeNfcSessionAsync()
                    enableNfcReaderMode(nfcAdapter, activity)
                    _statuses.tryEmit(TransferStatus.Connecting)
                }.onFailure { error ->
                    _statuses.tryEmit(
                        TransferStatus.Error(
                            error.localizedMessage ?: resourceProvider.genericErrorMessage()
                        )
                    )
                }
            }
        }
    }

    override fun sendRequest(
        requestedDocs: List<RequestedDocumentUi>,
        retainData: Boolean,
    ): Flow<TransferStatus> {
        pendingDeviceRequest = requestedDocs
            .map { it.transformToDocRequest(retainData) }
            .toDeviceRequest()
        return statuses
    }

    override fun stopConnection() {
        disableNfcReaderMode()
        verificationHelper?.disconnect()
        verificationHelper = null
        closeNfcSessionAsync()
        pendingDeviceRequest = null
        scope?.cancel()
        scope = null
    }

    private fun buildVerificationHelper(enableNfcNegotiatedHandover: Boolean): VerificationHelper {
        val listener = object : VerificationHelper.Listener {
            override fun onReaderEngagementReady(readerEngagement: ByteArray) = Unit

            override fun onDeviceEngagementReceived(connectionMethods: List<MdocConnectionMethod>) {
                val selectedMethod = MdocConnectionMethod.disambiguate(
                    connectionMethods,
                    MdocRole.MDOC_READER
                ).firstOrNull()

                if (selectedMethod == null) {
                    _statuses.tryEmit(
                        TransferStatus.Error("No mdoc connection method selected.")
                    )
                    return
                }

                _statuses.tryEmit(TransferStatus.DeviceEngagementCompleted)
                runCatching {
                    verificationHelper?.connect(selectedMethod)
                }.onFailure { error ->
                    _statuses.tryEmit(
                        TransferStatus.Error(
                            error.localizedMessage ?: resourceProvider.genericErrorMessage()
                        )
                    )
                }
            }

            override fun onMoveIntoNfcField() {
                _statuses.tryEmit(TransferStatus.Connecting)
            }

            override fun onDeviceConnected() {
                _statuses.tryEmit(TransferStatus.Connected)
                scope?.launch {
                    runCatching {
                        sendPendingRequestWithRetry()
                    }.onFailure { error ->
                        _statuses.tryEmit(
                            TransferStatus.Error(
                                error.localizedMessage ?: resourceProvider.genericErrorMessage()
                            )
                        )
                    }
                } ?: run {
                    _statuses.tryEmit(
                        TransferStatus.Error("Internal scope not available for request dispatch.")
                    )
                }
            }

            override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
                _statuses.tryEmit(TransferStatus.Disconnected)
            }

            override fun onResponseReceived(deviceResponseBytes: ByteArray) {
                handleDeviceResponse(deviceResponseBytes)
            }

            override fun onError(error: Throwable) {
                if (error.isRecoverableNfcTagError()) {
                    // Keep reader mode alive and wait for the next valid proximity event.
                    _statuses.tryEmit(TransferStatus.Connecting)
                    return
                }

                _statuses.tryEmit(
                    TransferStatus.Error(
                        error.localizedMessage ?: resourceProvider.genericErrorMessage()
                    )
                )
            }
        }

        val builder = VerificationHelper.Builder(
            context,
            listener,
            context.mainExecutor()
        ).setDataTransportOptions(dataTransportOptions)

        if (enableNfcNegotiatedHandover) {
            builder.setNegotiatedHandoverConnectionMethods(connectionMethods)
        }

        return builder.build()
    }

    private suspend fun sendPendingRequest() {
        val safeRequest = pendingDeviceRequest ?: return

        val nfcActiveSession = nfcSession
        if (nfcActiveSession != null) {
            val deviceRequestBytes = DeviceRequestGenerator(nfcActiveSession.sessionTranscript).apply {
                safeRequest.docRequests.forEach { doc ->
                    addDocumentRequest(
                        docType = doc.docType,
                        itemsToRequest = doc.itemsRequest,
                        readerKeyCertificateChain = null,
                        requestInfo = null,
                        readerKey = null,
                        signatureAlgorithm = org.multipaz.crypto.Algorithm.UNSET
                    )
                }
            }.generate()

            nfcActiveSession.transport.sendMessage(
                nfcActiveSession.sessionEncryption.encryptMessage(
                    messagePlaintext = deviceRequestBytes,
                    statusCode = null
                )
            )
            _statuses.tryEmit(TransferStatus.RequestSent)

            val encryptedResponse = nfcActiveSession.transport.waitForMessage()
            if (encryptedResponse.isEmpty()) {
                _statuses.tryEmit(TransferStatus.Disconnected)
                return
            }

            val (deviceResponseBytes, statusCode) = nfcActiveSession.sessionEncryption.decryptMessage(
                encryptedResponse
            )
            if (deviceResponseBytes != null) {
                handleDeviceResponse(
                    deviceResponseBytes = deviceResponseBytes,
                    sessionTranscript = nfcActiveSession.sessionTranscript,
                    eReaderKey = nfcActiveSession.eReaderKey
                )
            }

            if (statusCode == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                _statuses.tryEmit(TransferStatus.Disconnected)
            }
            return
        }

        val helper = verificationHelper ?: return
        val deviceRequestBytes = DeviceRequestGenerator(helper.sessionTranscript).apply {
            safeRequest.docRequests.forEach { doc ->
                addDocumentRequest(
                    docType = doc.docType,
                    itemsToRequest = doc.itemsRequest,
                    readerKeyCertificateChain = null,
                    requestInfo = null,
                    readerKey = null,
                    signatureAlgorithm = org.multipaz.crypto.Algorithm.UNSET
                )
            }
        }.generate()

        helper.sendRequest(deviceRequestBytes)
        _statuses.tryEmit(TransferStatus.RequestSent)
    }

    private suspend fun sendPendingRequestWithRetry(maxAttempts: Int = 2) {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            runCatching {
                sendPendingRequest()
                return
            }.onFailure { error ->
                lastError = error
                if (attempt < maxAttempts - 1) {
                    // Some devices are timing-sensitive right after NFC/BLE connect.
                    delay(300)
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to send device request.")
    }

    private fun handleDeviceResponse(
        deviceResponseBytes: ByteArray,
        sessionTranscript: ByteArray? = null,
        eReaderKey: EcPrivateKey? = null
    ) {
        scope?.launch {
            try {
                val transcript = sessionTranscript ?: verificationHelper?.sessionTranscript
                    ?: error("No active session transcript.")
                val parser = DeviceResponseParser(
                    deviceResponseBytes,
                    transcript
                )
                val ephemeralReaderKey = eReaderKey ?: verificationHelper?.eReaderKey
                if (ephemeralReaderKey != null) {
                    parser.setEphemeralReaderKey(ephemeralReaderKey)
                }

                val response = DeviceResponse(
                    parser.parse(),
                    deviceResponseBytes
                )

                val receivedDocuments = coroutineScope {
                    response.deviceResponse.documents
                        .zip(response.documentsClaims)
                        .map { (parserDoc, claimsDoc) ->
                            async {
                                val trusted = eudiVerifier
                                    .isDocumentTrusted(
                                        document = parserDoc,
                                        atTime = Clock.System.now()
                                    ).isTrusted

                                val validity = response.documentsValidity
                                    .find { it.docType == claimsDoc.docType }

                                ReceivedDocumentDomain(
                                    isTrusted = trusted,
                                    docType = claimsDoc.docType,
                                    claims = claimsDoc.flattenedClaims(resourceProvider),
                                    validity = DocumentValidityDomain(
                                        isDeviceSignatureValid = validity?.isDeviceSignatureValid,
                                        isIssuerSignatureValid = validity?.isIssuerSignatureValid,
                                        isDataIntegrityIntact = validity?.isDataIntegrityIntact,
                                        signed = validity?.msoValidity?.signed,
                                        validFrom = validity?.msoValidity?.validFrom,
                                        validUntil = validity?.msoValidity?.validUntil,
                                    )
                                )
                            }
                        }
                        .awaitAll()
                }

                _statuses.tryEmit(
                    TransferStatus.OnResponseReceived(
                        receivedDocs = ReceivedDocumentsDomain(documents = receivedDocuments)
                    )
                )
            } catch (t: Throwable) {
                _statuses.tryEmit(
                    TransferStatus.Error(
                        t.localizedMessage ?: resourceProvider.genericErrorMessage()
                    )
                )
            }
        }
    }

    private fun enableNfcReaderMode(nfcAdapter: NfcAdapter, activity: ComponentActivity) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        nfcAdapter.enableReaderMode(
            activity,
            { tag ->
                // ISO 18013-5 NFC handover requires Reader/Writer mode with Type 4 Tag (IsoDep).
                if (!tag.supportsIsoDep()) {
                    _statuses.tryEmit(TransferStatus.Connecting)
                    return@enableReaderMode
                }

                handleNfcTagDiscovered(tag)
            },
            flags,
            null
        )
        readerModeEnabled = true
    }

    private fun disableNfcReaderMode() {
        val activity = ActivityProvider.currentActivity as? ComponentActivity
        if (readerModeEnabled && activity != null) {
            NfcAdapter.getDefaultAdapter(context)?.disableReaderMode(activity)
        }
        readerModeEnabled = false
    }

    private fun pemToX509Certificate(pem: String): Result<X509Certificate> {
        return runCatching {
            val base64 = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s+".toRegex(), "")

            val der = Base64.decode(base64, Base64.DEFAULT)
            val cf = CertificateFactory.getInstance("X.509")

            cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
    }

    private fun RequestedDocumentUi.transformToDocRequest(
        retainData: Boolean
    ): DocRequest {
        val requestedClaims: Map<String, Boolean> = this
            .claims
            .associate { claimItem: ClaimItem ->
                claimItem.label to retainData
            }

        return DocRequest(
            docType = this.documentType.docType,
            itemsRequest = mapOf(
                this.documentType.namespace to requestedClaims
            ),
            readerAuthCertificate = null
        )
    }

    private fun List<DocRequest>.toDeviceRequest(): DeviceRequest =
        DeviceRequest(
            docRequests = this
        )

    private fun Context.mainExecutor(): Executor {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            mainExecutor
        } else {
            androidx.core.content.ContextCompat.getMainExecutor(this)
        }
    }

    private fun handleNfcTagDiscovered(tag: Tag) {
        if (!nfcTagProcessing.compareAndSet(false, true)) {
            return
        }

        val localScope = scope
        if (localScope == null) {
            nfcTagProcessing.set(false)
            _statuses.tryEmit(TransferStatus.Error("Internal scope not available for NFC handover."))
            return
        }

        localScope.launch {
            try {
                performNfcNegotiatedHandover(tag)
            } catch (error: Throwable) {
                if (error.isRecoverableNfcTagError()) {
                    _statuses.tryEmit(TransferStatus.Connecting)
                } else {
                    _statuses.tryEmit(
                        TransferStatus.Error(
                            error.localizedMessage ?: resourceProvider.genericErrorMessage()
                        )
                    )
                }
            } finally {
                nfcTagProcessing.set(false)
            }
        }
    }

    private suspend fun performNfcNegotiatedHandover(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: error("IsoDep tag not available.")
        withContext(Dispatchers.IO) {
            isoDep.connect()
            isoDep.timeout = 20_000
        }

        val isoTag = NfcIsoTagAndroid(
            tag = isoDep,
            tagContext = Dispatchers.IO
        )

        val handoverResult = mdocReaderNfcHandover(
            tag = isoTag,
            negotiatedHandoverConnectionMethods = connectionMethods
        )

        if (handoverResult == null) {
            _statuses.tryEmit(TransferStatus.Connecting)
            return
        }

        val selectedMethod = handoverResult.connectionMethods
            .preferredReaderBleMethod()
            ?: handoverResult.connectionMethods.firstOrNull()
            ?: error("No mdoc connection method selected.")

        val transport = MdocTransportFactory.Default.createTransport(
            connectionMethod = selectedMethod,
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(
                bleUseL2CAP = bleUseL2Cap
            )
        )

        if (transport is NfcTransportMdocReader) {
            transport.setTag(isoTag)
        }

        val engagement = EngagementParser(
            handoverResult.encodedDeviceEngagement.toByteArray()
        ).parse()
        val eDeviceKey = engagement.eSenderKey
        val eReaderKey = Crypto.createEcPrivateKey(eDeviceKey.curve)
        val encodedSessionTranscript = generateEncodedSessionTranscript(
            encodedDeviceEngagement = handoverResult.encodedDeviceEngagement.toByteArray(),
            handover = handoverResult.handover,
            eReaderKey = eReaderKey
        )

        val sessionEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey,
            encodedSessionTranscript = encodedSessionTranscript
        )

        transport.open(eDeviceKey)
        closeNfcSession()
        nfcSession = NfcSession(
            transport = transport,
            sessionEncryption = sessionEncryption,
            sessionTranscript = encodedSessionTranscript,
            eReaderKey = eReaderKey
        )

        _statuses.tryEmit(TransferStatus.DeviceEngagementCompleted)
        _statuses.tryEmit(TransferStatus.Connected)
        sendPendingRequestWithRetry()
    }

    private fun generateEncodedSessionTranscript(
        encodedDeviceEngagement: ByteArray,
        handover: org.multipaz.cbor.DataItem,
        eReaderKey: EcPrivateKey
    ): ByteArray {
        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        return Cbor.encode(
            buildCborArray {
                add(Tagged(24, Bstr(encodedDeviceEngagement)))
                add(Tagged(24, Bstr(encodedEReaderKey)))
                add(handover)
            }
        )
    }

    private suspend fun closeNfcSession() {
        nfcSession?.transport?.close()
        nfcSession = null
    }

    private fun closeNfcSessionAsync() {
        scope?.launch {
            closeNfcSession()
        } ?: run {
            nfcSession = null
        }
    }

    private fun Throwable.isRecoverableNfcTagError(): Boolean {
        val message = localizedMessage?.lowercase().orEmpty()
        return message.contains("unknown tag type") ||
            message.contains("empty tag") ||
            message.contains("tom tag") ||
            message.contains("tag lost")
    }

    private fun Tag.supportsIsoDep(): Boolean {
        return techList.contains(IsoDep::class.java.name)
    }

    private fun List<MdocConnectionMethod>.preferredReaderBleMethod(): MdocConnectionMethod? {
        return firstOrNull { method ->
            method is MdocConnectionMethodBle &&
                method.supportsCentralClientMode &&
                !method.supportsPeripheralServerMode
        }
    }

    private data class NfcSession(
        val transport: MdocTransport,
        val sessionEncryption: SessionEncryption,
        val sessionTranscript: ByteArray,
        val eReaderKey: EcPrivateKey
    )
}
