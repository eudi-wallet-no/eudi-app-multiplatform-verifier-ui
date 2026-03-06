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
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.response.DeviceResponseParser
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executor
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
    private var scope: CoroutineScope? = null

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

        this.connectionMethods = MdocConnectionMethod.disambiguate(
            configuredConnectionMethods,
            MdocRole.MDOC_READER
        ).ifEmpty { configuredConnectionMethods }

        dataTransportOptions = DataTransportOptions.Builder()
            .setBleUseL2CAP(useL2Cap)
            .setBleClearCache(clearBleCache)
            .build()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun startEngagement(qrCode: String) {
        disableNfcReaderMode()
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
                    val helper = buildVerificationHelper(enableNfcNegotiatedHandover = true)
                    verificationHelper = helper
                    enableNfcReaderMode(nfcAdapter, activity, helper)
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

    private fun sendPendingRequest() {
        val helper = verificationHelper ?: return
        val safeRequest = pendingDeviceRequest ?: return

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

    private fun handleDeviceResponse(deviceResponseBytes: ByteArray) {
        scope?.launch {
            try {
                val helper = verificationHelper
                    ?: error("No active verification helper.")

                val parser = DeviceResponseParser(
                    deviceResponseBytes,
                    helper.sessionTranscript
                ).apply {
                    setEphemeralReaderKey(helper.eReaderKey)
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

    private fun enableNfcReaderMode(
        nfcAdapter: NfcAdapter,
        activity: ComponentActivity,
        helper: VerificationHelper
    ) {
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

                runCatching {
                    helper.nfcProcessOnTagDiscovered(tag)
                }.onFailure { error ->
                    if (error.isRecoverableNfcTagError()) {
                        _statuses.tryEmit(TransferStatus.Connecting)
                        return@enableReaderMode
                    }

                    _statuses.tryEmit(
                        TransferStatus.Error(
                            error.localizedMessage ?: resourceProvider.genericErrorMessage()
                        )
                    )
                }
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
}
