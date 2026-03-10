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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.euidi.verifier.presentation.component.ListItemLeadingContentDataUi
import eu.europa.ec.euidi.verifier.presentation.component.content.ContentScreen
import eu.europa.ec.euidi.verifier.presentation.component.content.ScreenNavigateAction
import eu.europa.ec.euidi.verifier.presentation.component.content.ToolbarConfig
import eu.europa.ec.euidi.verifier.presentation.component.rememberBase64DecodedBitmap
import eu.europa.ec.euidi.verifier.presentation.component.utils.OneTimeLaunchedEffect
import eu.europa.ec.euidi.verifier.presentation.component.wrap.ButtonType
import eu.europa.ec.euidi.verifier.presentation.component.wrap.StickyBottomConfig
import eu.europa.ec.euidi.verifier.presentation.component.wrap.StickyBottomType
import eu.europa.ec.euidi.verifier.presentation.component.wrap.WrapImage
import eu.europa.ec.euidi.verifier.presentation.component.wrap.WrapStickyBottomContent
import eu.europa.ec.euidi.verifier.presentation.component.wrap.rememberButtonConfig
import eu.europa.ec.euidi.verifier.presentation.model.ReceivedDocsHolder
import eu.europa.ec.euidi.verifier.presentation.navigation.getFromPreviousBackStack
import eu.europa.ec.euidi.verifier.presentation.utils.Constants
import eudiverifier.verifierapp.generated.resources.Res
import eudiverifier.verifierapp.generated.resources.content_description_check_icon
import eudiverifier.verifierapp.generated.resources.content_description_image_or_placeholder_icon
import eudiverifier.verifierapp.generated.resources.generic_ok
import eudiverifier.verifierapp.generated.resources.ic_check_mark
import eudiverifier.verifierapp.generated.resources.ic_error_icon
import eudiverifier.verifierapp.generated.resources.show_documents_screen_title
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// grøn 0xFF3C853C
var bacgroundColor = Color.White

val frameBrush = Brush.linearGradient(
    colors = listOf(
        Color(0x313C853C),
        Color(0x193C853C),
        Color(0x083C853C),
    ),
    tileMode = TileMode.Clamp
)

@Composable
fun ShowDocumentsScreen(
    navController: NavController,
    viewModel: ShowDocumentsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ContentScreen(
        navigatableAction = ScreenNavigateAction.BACKABLE,
        toolBarConfig = ToolbarConfig(
            title = stringResource(Res.string.show_documents_screen_title),
            backgroundColor = bacgroundColor,
            textColor = Color.Black
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
        },
        backgroundColor = Color.Transparent
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
            .background(bacgroundColor)
    ) {
        WrapStickyBottomContent(
            stickyBottomModifier = Modifier
                .fillMaxWidth()
                .background(bacgroundColor),
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(paddingValues)
            .padding(0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White) // gradient frame background
            .clip(RoundedCornerShape(16.dp))
            .background(frameBrush) // inner surface
            .border(1.dp, Color(0x1F3C853C), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(Color.Transparent)
                .padding(
                    top = 10.dp,
                    bottom = 10.dp,
                )
                .fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(50.dp))

            var imageValue = ""
            var over16value = false
            var over16exist = false
            var over18value = false
            var over18exist = false
            val bildeText = "Bilde"
            val over16Text = "Over 16"
            val over18Text = "Over 18"
            //var claimsApproved: List<String> = emptyList()

            state.items.forEach { document ->

                document.uiClaims.forEach { claim ->
                    if (claim.overlineText == bildeText) {
                        val data = claim.leadingContentData as ListItemLeadingContentDataUi.UserImage
                        imageValue = data.userBase64Image
                    } else if (claim.overlineText == over16Text) {
                        over16exist = true
                        if (claim.mainContentData.toString().contains("yes")){
                            over16value = true
                        }
                        //claimsApproved = claimsApproved.plus("Over 16")
                    }else if (claim.overlineText == over18Text) {
                        over18exist = true
                        if (claim.mainContentData.toString().contains("yes")){
                            over18value = true
                        }
                        //claimsApproved = claimsApproved.plus("Over 18")
                    }
                }
            }

            val bitmap = rememberBase64DecodedBitmap(base64Image = imageValue)
            val mod = Modifier
                //.padding(end = SIZE_SMALL.dp)
                .size(300.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 1.dp,
                    color = Color.Black,
                    shape = RoundedCornerShape(12.dp))

            WrapImage(
                modifier = mod,
                bitmap = bitmap,
                contentDescription = stringResource(resource = Res.string.content_description_image_or_placeholder_icon)
            )

            Spacer(modifier = Modifier.height(30.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (over16exist) {
                    if (over16value) {
                        TextSection(
                            text = "$over16Text år",
                            textColor = Color(0xFF3C853C),
                            icon = Res.drawable.ic_check_mark,
                            iconSize = Modifier.size(50.dp)
                        )
                    }else {
                        TextSection(
                            text = "$over16Text år",
                            textColor = Color(0xFFC94F4F),
                            icon = Res.drawable.ic_error_icon,
                            iconSize = Modifier.size(40.dp)
                        )
                    }
                }

                if (over18exist) {
                    if (over18value) {
                        TextSection(
                            text = "$over18Text år",
                            textColor = Color.Black,
                            icon = Res.drawable.ic_check_mark,
                            iconSize = Modifier.size(50.dp)
                        )
                    }else {
                        TextSection(
                            text = "$over18Text år",
                            textColor = Color(0xFFC94F4F),
                            icon = Res.drawable.ic_error_icon,
                            iconSize = Modifier.size(40.dp)
                        )
                    }
                }
            }
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
private fun TextSection(
    text: String,
    textColor: Color,
    icon: DrawableResource,
    iconSize: Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 49.sp
            ),
            color = textColor,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.width(15.dp))

        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(Res.string.content_description_check_icon),
            tint = textColor,
            modifier = iconSize
        )
    }
}