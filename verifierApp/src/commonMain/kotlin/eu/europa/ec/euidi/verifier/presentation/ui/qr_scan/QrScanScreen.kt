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

package eu.europa.ec.euidi.verifier.presentation.ui.qr_scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.euidi.verifier.presentation.component.content.ContentScreen
import eu.europa.ec.euidi.verifier.presentation.component.content.ScreenNavigateAction
import eu.europa.ec.euidi.verifier.presentation.component.content.ToolbarConfig
import eu.europa.ec.euidi.verifier.presentation.component.utils.OneTimeLaunchedEffect
import eu.europa.ec.euidi.verifier.presentation.model.RequestedDocsHolder
import eu.europa.ec.euidi.verifier.presentation.navigation.NavItem
import eu.europa.ec.euidi.verifier.presentation.navigation.getFromPreviousBackStack
import eu.europa.ec.euidi.verifier.presentation.navigation.saveToPreviousBackStack
import eu.europa.ec.euidi.verifier.presentation.ui.qr_scan.QrScanViewModelContract.Effect
import eu.europa.ec.euidi.verifier.presentation.ui.qr_scan.QrScanViewModelContract.Event
import eu.europa.ec.euidi.verifier.presentation.ui.qr_scan.QrScanViewModelContract.State
import eu.europa.ec.euidi.verifier.presentation.utils.Constants
import eudiverifier.verifierapp.generated.resources.Res
import eudiverifier.verifierapp.generated.resources.content_description_check_icon
import eudiverifier.verifierapp.generated.resources.ic_qr_code_scan
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import qrscanner.CameraLens
import qrscanner.QrScanner

@Composable
fun QrScanScreen(
    navController: NavController,
    viewModel: QrScanViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val toolbarConfig = remember(state.screenTitle) {
        ToolbarConfig(
            title = state.screenTitle,
        )
    }

    ContentScreen(
        isLoading = state.isLoading,
        toolBarConfig = toolbarConfig,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.OnBackClicked) },
        contentErrorConfig = state.error,
    ) { padding ->
        Content(
            state = state,
            onEventSend = viewModel::setEvent,
            effectFlow = viewModel.effect,
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(navigationEffect, navController)
            },
            paddingValues = padding,
        )
    }

    OneTimeLaunchedEffect {
        val documents =
            navController.getFromPreviousBackStack<RequestedDocsHolder>(Constants.REQUESTED_DOCUMENTS)
        viewModel.setEvent(Event.Init(docs = documents?.items))
    }
}

private fun handleNavigationEffect(
    navigationEffect: Effect.Navigation,
    navController: NavController,
) {
    when (navigationEffect) {
        is Effect.Navigation.Pop -> navController.popBackStack()

        is Effect.Navigation.NavigateToTransferStatusScreen -> {
            navController.saveToPreviousBackStack<RequestedDocsHolder>(
                key = Constants.REQUESTED_DOCUMENTS,
                value = navigationEffect.requestedDocs
            )
            navController.navigate(route = NavItem.TransferStatus(qrCode = navigationEffect.qrCode)) {
                popUpTo(route = NavItem.QrScan) {
                    inclusive = true
                }
            }
        }
    }
}

@Composable
private fun Content(
    state: State,
    onEventSend: (Event) -> Unit,
    effectFlow: Flow<Effect>,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = paddingValues.calculateTopPadding()
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.White),
            contentAlignment = Alignment.Center
        )
        {
            if (!state.finishedScanning) {
                QrScanner(
                    modifier = Modifier.fillMaxSize(),
                    onCompletion = {
                        onEventSend(Event.OnQrScanned(code = it))
                    },
                    onFailure = {
                        onEventSend(Event.OnQrScanFailed(error = it))
                    },
                    overlayColor = Color.White,
                    cameraLens = CameraLens.Front,
                    overlayBorderColor = Color.White,
                    flashlightOn = false,
                    openImagePicker = false,
                    imagePickerHandler = {},
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Scan QR-koden frå lommeboka di",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.width(15.dp))

                    Icon(
                        painter = painterResource(Res.drawable.ic_qr_code_scan),
                        contentDescription = stringResource(Res.string.content_description_check_icon),
                        tint = Color.Black,
                        modifier = Modifier.size(250.dp)
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.collect { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)
            }
        }
    }
}