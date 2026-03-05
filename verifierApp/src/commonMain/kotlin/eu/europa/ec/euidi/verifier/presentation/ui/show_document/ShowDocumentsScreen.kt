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

package eu.europa.ec.euidi.verifier.presentation.ui.show_document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.euidi.verifier.presentation.component.IconDataUi
import eu.europa.ec.euidi.verifier.presentation.component.ListItemDataUi
import eu.europa.ec.euidi.verifier.presentation.component.ListItemLeadingContentDataUi
import eu.europa.ec.euidi.verifier.presentation.component.content.ContentScreen
import eu.europa.ec.euidi.verifier.presentation.component.content.ScreenNavigateAction
import eu.europa.ec.euidi.verifier.presentation.component.content.ToolbarConfig
import eu.europa.ec.euidi.verifier.presentation.component.rememberBase64DecodedBitmap
import eu.europa.ec.euidi.verifier.presentation.component.utils.OneTimeLaunchedEffect
import eu.europa.ec.euidi.verifier.presentation.component.utils.SIZE_MEDIUM
import eu.europa.ec.euidi.verifier.presentation.component.utils.SIZE_SMALL
import eu.europa.ec.euidi.verifier.presentation.component.utils.SPACING_EXTRA_LARGE
import eu.europa.ec.euidi.verifier.presentation.component.utils.SPACING_MEDIUM
import eu.europa.ec.euidi.verifier.presentation.component.utils.SPACING_SMALL
import eu.europa.ec.euidi.verifier.presentation.component.wrap.ButtonType
import eu.europa.ec.euidi.verifier.presentation.component.wrap.StickyBottomConfig
import eu.europa.ec.euidi.verifier.presentation.component.wrap.StickyBottomType
import eu.europa.ec.euidi.verifier.presentation.component.wrap.WrapCard
import eu.europa.ec.euidi.verifier.presentation.component.wrap.WrapImage
import eu.europa.ec.euidi.verifier.presentation.component.wrap.WrapListItems
import eu.europa.ec.euidi.verifier.presentation.component.wrap.WrapStickyBottomContent
import eu.europa.ec.euidi.verifier.presentation.component.wrap.rememberButtonConfig
import eu.europa.ec.euidi.verifier.presentation.model.ReceivedDocsHolder
import eu.europa.ec.euidi.verifier.presentation.navigation.getFromPreviousBackStack
import eu.europa.ec.euidi.verifier.presentation.ui.show_document.model.DocumentUi
import eu.europa.ec.euidi.verifier.presentation.utils.Constants
import eudiverifier.verifierapp.generated.resources.Res
import eudiverifier.verifierapp.generated.resources.content_description_check_icon
import eudiverifier.verifierapp.generated.resources.content_description_image_or_placeholder_icon
import eudiverifier.verifierapp.generated.resources.generic_ok
import eudiverifier.verifierapp.generated.resources.ic_check
import eudiverifier.verifierapp.generated.resources.ic_check_mark
import eudiverifier.verifierapp.generated.resources.show_documents_screen_document_header
import eudiverifier.verifierapp.generated.resources.show_documents_screen_num_of_docs_description
import eudiverifier.verifierapp.generated.resources.show_documents_screen_title
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ShowDocumentsScreen(
    navController: NavController,
    viewModel: ShowDocumentsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ContentScreen(
        navigatableAction = ScreenNavigateAction.BACKABLE,
        toolBarConfig = ToolbarConfig(
            title = stringResource(Res.string.show_documents_screen_title)
        ),
        onBack = {
            viewModel.setEvent(ShowDocumentViewModelContract.Event.OnBackClick)
        },
        stickyBottom = { stickyBottomPaddings ->
            StickyBottomSection(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(stickyBottomPaddings),
                enabled = !state.isLoading,
                onClick = {
                    viewModel.setEvent(ShowDocumentViewModelContract.Event.OnDoneClick)
                }
            )
        }
    ) { padding ->
        ContentSuccess(
            state = state,
            effectFlow = viewModel.effect,
            onNavigationRequested = { navigationEffect ->
                handleNavigationEffect(
                    navController = navController,
                    navigationEffect = navigationEffect
                )
            },
            paddingValues = padding
        )

        OneTimeLaunchedEffect {
            navController
                .getFromPreviousBackStack<ReceivedDocsHolder>(Constants.RECEIVED_DOCUMENTS)
                ?.let { docsHolder ->
                    viewModel.setEvent(
                        ShowDocumentViewModelContract.Event.Init(
                            items = docsHolder.items,
                        )
                    )
                }
        }
    }
}

private fun handleNavigationEffect(
    navController: NavController,
    navigationEffect: ShowDocumentViewModelContract.Effect.Navigation
) {
    when (navigationEffect) {
        is ShowDocumentViewModelContract.Effect.Navigation.PopTo -> {
            navController.popBackStack(
                route = navigationEffect.route,
                inclusive = navigationEffect.inclusive
            )
        }
    }
}

@Composable
private fun StickyBottomSection(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
    ) {
        WrapStickyBottomContent(
            stickyBottomModifier = Modifier.fillMaxWidth(),
            stickyBottomConfig = StickyBottomConfig(
                type = StickyBottomType.OneButton(
                    config = rememberButtonConfig(
                        type = ButtonType.PRIMARY,
                        onClick = onClick,
                        enabled = enabled,
                        content = {
                            Text(
                                text = stringResource(
                                    Res.string.generic_ok
                                )
                            )
                        }
                    )
                )
            )
        )
    }
}

@Composable
private fun ContentSuccess(
    state: ShowDocumentViewModelContract.State,
    effectFlow: Flow<ShowDocumentViewModelContract.Effect>,
    onNavigationRequested: (ShowDocumentViewModelContract.Effect.Navigation) -> Unit,
    paddingValues: PaddingValues
) {

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .background(Color(0xFF3C853C))
            .padding(
                top = 10.dp,
                bottom = 10.dp,
            )
            .fillMaxSize()
    ) {
        Spacer(modifier = Modifier.height(50.dp))

        var imageValue = ""
        state.items.forEach { document ->


            document.uiClaims.forEach { claim ->
                if (claim.overlineText == "Bilde") {
                    val data = claim.leadingContentData as ListItemLeadingContentDataUi.UserImage
                    imageValue = data.userBase64Image
                }
            }
        }

        val bitmap = rememberBase64DecodedBitmap(base64Image = imageValue)
        val mod = Modifier
            //.padding(end = SIZE_SMALL.dp)
            .size(300.dp)

        WrapImage(
            modifier = mod,
            bitmap = bitmap,
            contentDescription = stringResource(resource = Res.string.content_description_image_or_placeholder_icon)
            //contentScale = contentScale,
        )

        Spacer(modifier = Modifier.height(30.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Age 21+",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.width(15.dp))

            Icon(
                painter = painterResource(resource = Res.drawable.ic_check_mark),
                contentDescription = stringResource(Res.string.content_description_check_icon),
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.collect { effect ->
            when (effect) {
                is ShowDocumentViewModelContract.Effect.Navigation -> onNavigationRequested(effect)
            }
        }
    }

}

@Composable
private fun Content(
    state: ShowDocumentViewModelContract.State,
    effectFlow: Flow<ShowDocumentViewModelContract.Effect>,
    onNavigationRequested: (ShowDocumentViewModelContract.Effect.Navigation) -> Unit,
    paddingValues: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SPACING_EXTRA_LARGE.dp),
    ) {
        /*DocumentsHeader(
            size = state.items.size,
        )*/

        /*state.items.forEach { document ->
            DocumentDetails(
                document = document,
                modifier = Modifier.fillMaxWidth()
            )
        }*/

        var imageValue = ""
        state.items.forEach { document ->


            document.uiClaims.forEach { claim ->
                if (claim.overlineText == "Bilde") {
                    val data = claim.leadingContentData as ListItemLeadingContentDataUi.UserImage
                    imageValue = data.userBase64Image
                }
            }
        }

        val bitmap = rememberBase64DecodedBitmap(base64Image = imageValue)
        val mod = Modifier
            .padding(end = SIZE_SMALL.dp)
            .size(300.dp)

        WrapImage(
            modifier = mod,
            bitmap = bitmap,
            contentDescription = stringResource(resource = Res.string.content_description_image_or_placeholder_icon)
            //contentScale = contentScale,
        )

    }

    LaunchedEffect(Unit) {
        effectFlow.collect { effect ->
            when (effect) {
                is ShowDocumentViewModelContract.Effect.Navigation -> onNavigationRequested(effect)
            }
        }
    }
}

@Composable
private fun DocumentsHeader(
    size: Int,
) {
    WrapCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SPACING_MEDIUM.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = buildAnnotatedString {
                    append(stringResource(Res.string.show_documents_screen_num_of_docs_description))
                    append(": ")
                    append(size.toString())
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DocumentDetails(
    document: DocumentUi,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)
    ) {
        Text(
            text = buildAnnotatedString {
                append(stringResource(Res.string.show_documents_screen_document_header))
                append(": ")
                append(document.docType)
            },
            style = MaterialTheme.typography.labelLarge
        )

        WrapListItems(
            modifier = Modifier.fillMaxWidth(),
            items = document.validityInfo,
            onItemClick = null,
            mainContentVerticalPadding = SPACING_SMALL.dp
        )

        WrapListItems(
            modifier = Modifier.fillMaxWidth(),
            items = document.uiClaims,
            onItemClick = null,
            mainContentVerticalPadding = SPACING_MEDIUM.dp
        )
    }
}