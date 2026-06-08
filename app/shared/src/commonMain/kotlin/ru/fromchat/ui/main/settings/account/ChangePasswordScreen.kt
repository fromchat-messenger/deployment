package ru.fromchat.ui.main.settings.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.pr0gramm3r101.utils.crypto.deriveAuthSecret
import com.pr0gramm3r101.utils.imeScrollWithKeyboard
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ru.fromchat.Res
import ru.fromchat.api.ApiClient
import ru.fromchat.back
import ru.fromchat.error_unexpected
import ru.fromchat.fill_all_fields
import ru.fromchat.password_length_error
import ru.fromchat.passwords_dont_match
import ru.fromchat.settings_change_password
import ru.fromchat.settings_confirm_new_password
import ru.fromchat.settings_current_password
import ru.fromchat.settings_new_password
import ru.fromchat.settings_next
import ru.fromchat.settings_password_changed
import ru.fromchat.settings_security_step_confirm_body
import ru.fromchat.settings_security_step_confirm_title
import ru.fromchat.settings_security_step_current_body
import ru.fromchat.settings_security_step_current_title
import ru.fromchat.settings_security_step_new_body
import ru.fromchat.settings_security_step_new_title
import ru.fromchat.ui.components.CtaShape
import ru.fromchat.ui.components.FromChatSnackbarHost
import ru.fromchat.ui.components.PredictiveBackHandler
import ru.fromchat.ui.components.Text
import ru.fromchat.ui.main.settings.SettingsStepHorizontalPadding

/** Outline shape for password step text fields. */
val SettingsPasswordOutlineFieldShape = RoundedCornerShape(18.dp)

/** Steps in the change-password flow (single navigation destination; drives hero morph + form slide). */
enum class SecurityPasswordFlowStep {
    Current,
    New,
    Confirm,
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): SecurityPasswordFlowStep =
            entries.getOrElse(ordinal.coerceIn(0, entries.lastIndex)) { Current }
    }
}

/**
 * Predefined [RoundedPolygon]s from the Material 3 shape library ([MaterialShapes]), one per step.
 * Uses the same “cookie” family so morphs stay subtle between steps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun securityHeroMaterialPolygon(step: SecurityPasswordFlowStep): RoundedPolygon =
    when (step) {
        SecurityPasswordFlowStep.Current -> MaterialShapes.Cookie4Sided
        SecurityPasswordFlowStep.New -> MaterialShapes.Cookie6Sided
        SecurityPasswordFlowStep.Confirm -> MaterialShapes.Cookie7Sided
    }.normalized()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberSecurityPasswordHeroMorph(step: SecurityPasswordFlowStep): Pair<Morph, Animatable<Float, *>> {
    var fromStep by remember { mutableStateOf(step) }
    var toStep by remember { mutableStateOf(step) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(step) {
        if (step != toStep) {
            fromStep = toStep
            toStep = step
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
            )
        }
    }
    val morph = remember(fromStep, toStep) {
        Morph(securityHeroMaterialPolygon(fromStep), securityHeroMaterialPolygon(toStep))
    }
    return morph to progress
}

private object SecurityPasswordDraft {
    var current: String = ""
    var newPassword: String = ""
    var confirmPassword: String = ""

    fun clear() {
        current = ""
        newPassword = ""
        confirmPassword = ""
    }
}

@Composable
private fun SecurityPasswordOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: TextFieldColors,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        colors = colors,
        shape = SettingsPasswordOutlineFieldShape,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
fun SettingsSecurityPasswordFlowScreen(onBack: () -> Unit, onDonePopToHub: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var current by remember { mutableStateOf("") }
    var newP by remember { mutableStateOf("") }
    var confirmP by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val showSnack = { text: String ->
        scope.launch {
            snackbarHostState.showSnackbar(
                message = text,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val fillAll = stringResource(Res.string.fill_all_fields)
    val pwdLen = stringResource(Res.string.password_length_error)
    val pwdMatch = stringResource(Res.string.passwords_dont_match)
    val okMsg = stringResource(Res.string.settings_password_changed)
    val errUnexpected = stringResource(Res.string.error_unexpected)
    val username = ApiClient.user?.username.orEmpty()

    LaunchedEffect(Unit) {
        SecurityPasswordDraft.clear()
        current = ""
        newP = ""
        confirmP = ""
        busy = false
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { SecurityPasswordFlowStep.entries.size },
    )

    val step = SecurityPasswordFlowStep.fromOrdinal(pagerState.currentPage)
    val pageOffset by derivedStateOf { pagerState.currentPageOffsetFraction }

    var predictiveFromStep by remember { mutableStateOf<SecurityPasswordFlowStep?>(null) }
    var predictiveToStep by remember { mutableStateOf<SecurityPasswordFlowStep?>(null) }
    var predictiveProgress by remember { mutableFloatStateOf(0f) }
    val predictiveThreshold = 0.15f

    PredictiveBackHandler(
        enabled = pagerState.currentPage > 0,
        onProgress = { p ->
            val clamped = p.coerceIn(0f, 1f)
            if (clamped <= 0f) {
                predictiveProgress = 0f
                predictiveFromStep = null
                predictiveToStep = null
            } else {
                if (predictiveFromStep == null || predictiveToStep == null) {
                    val fromPage = pagerState.currentPage
                    val toPage = (fromPage - 1).coerceAtLeast(0)
                    predictiveFromStep = SecurityPasswordFlowStep.fromOrdinal(fromPage)
                    predictiveToStep = SecurityPasswordFlowStep.fromOrdinal(toPage)
                }
                predictiveProgress = clamped
            }
        },
        onCommit = {
            val fromStepSnapshot = predictiveFromStep
            val toStepSnapshot = predictiveToStep
            val lastProgress = predictiveProgress.coerceIn(0f, 1f)
            // If progress is below threshold, treat as cancel and animate back to 0.
            if (lastProgress < predictiveThreshold || fromStepSnapshot == null || toStepSnapshot == null) {
                scope.launch {
                    Animatable(lastProgress).animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 220),
                    ) {
                        predictiveProgress = value
                    }
                    val fromPage = fromStepSnapshot?.ordinal ?: pagerState.currentPage
                    pagerState.scrollToPage(fromPage)
                    predictiveFromStep = null
                    predictiveToStep = null
                    predictiveProgress = 0f
                }
            } else {
                scope.launch {
                    Animatable(lastProgress).animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 220),
                    ) {
                        predictiveProgress = value
                    }
                    pagerState.scrollToPage(toStepSnapshot.ordinal)
                    predictiveFromStep = null
                    predictiveToStep = null
                    predictiveProgress = 0f
                }
            }
        },
        onCancel = {
            val fromStepSnapshot = predictiveFromStep
            val lastProgress = predictiveProgress.coerceIn(0f, 1f)
            if (fromStepSnapshot == null) {
                predictiveProgress = 0f
                predictiveFromStep = null
                predictiveToStep = null
                return@PredictiveBackHandler
            }
            scope.launch {
                val target = if (lastProgress >= predictiveThreshold) 1f else 0f
                Animatable(lastProgress).animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 220),
                ) {
                    predictiveProgress = value
                }
                val commit = lastProgress >= predictiveThreshold
                val targetPage = if (commit) {
                    (fromStepSnapshot.ordinal - 1).coerceAtLeast(0)
                } else {
                    fromStepSnapshot.ordinal
                }
                pagerState.scrollToPage(targetPage)
                predictiveFromStep = null
                predictiveToStep = null
                predictiveProgress = 0f
            }
        },
    )

    LaunchedEffect(predictiveFromStep, predictiveProgress) {
        val fromStepForPager = predictiveFromStep ?: return@LaunchedEffect
        val toStepForPager = predictiveToStep ?: return@LaunchedEffect
        val clamped = predictiveProgress.coerceIn(0f, 1f)
        if (clamped <= 0f) return@LaunchedEffect
        val page: Int
        val offset: Float
        if (clamped <= 0.5f) {
            page = fromStepForPager.ordinal
            offset = (-clamped).coerceIn(-0.5f, 0f)
        } else {
            page = toStepForPager.ordinal
            offset = (1f - clamped).coerceIn(0f, 0.5f)
        }
        pagerState.scrollToPage(
            page = page,
            pageOffsetFraction = offset,
        )
    }

    val lastIndex = SecurityPasswordFlowStep.entries.lastIndex
    val fromIndex: Int
    val toIndex: Int
    val morphProgress: Float
    if (predictiveFromStep != null && predictiveToStep != null && predictiveProgress > 0f) {
        fromIndex = predictiveFromStep!!.ordinal
        toIndex = predictiveToStep!!.ordinal
        morphProgress = predictiveProgress
    } else if (pageOffset < 0f) {
        fromIndex = pagerState.currentPage
        toIndex = (fromIndex - 1).coerceAtLeast(0)
        morphProgress = -pageOffset
    } else if (pageOffset > 0f) {
        fromIndex = pagerState.currentPage
        toIndex = (fromIndex + 1).coerceAtMost(lastIndex)
        morphProgress = pageOffset
    } else {
        fromIndex = pagerState.currentPage
        toIndex = fromIndex
        morphProgress = 0f
    }
    val fromStep = SecurityPasswordFlowStep.fromOrdinal(fromIndex)
    val toStep = SecurityPasswordFlowStep.fromOrdinal(toIndex)
    val effectiveMorphProgress = morphProgress.coerceIn(0f, 1f)

    val scheme = MaterialTheme.colorScheme
    val passwordFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        disabledLabelColor = scheme.onSurfaceVariant.copy(alpha = 0.38f),
        cursorColor = scheme.primary,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        disabledBorderColor = scheme.onSurface.copy(alpha = 0.12f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.surface,
        contentColor = scheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .imeScrollWithKeyboard()
                            .statusBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(40.dp))
                        Spacer(Modifier.height(4.dp))

                        SettingsSecurityMorphedPasswordHero(
                            step = step,
                            predictiveProgress = if (fromStep != toStep) effectiveMorphProgress else null,
                            predictiveFromStep = if (fromStep != toStep) fromStep else null,
                            predictiveToStep = if (fromStep != toStep) toStep else null,
                        )

                        Spacer(Modifier.height(16.dp))

                        // Pager layout height is max(cross-axis size) of composed pages (visible + beyondViewport).
                        // Default beyondViewport is small, so after predictive back only page 0 may be measured and
                        // the slot height can shrink vs the two-page gesture — content jumps up. Composing neighbors
                        // on both sides keeps max height stable across steps without visiting the last page first.
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = false,
                            beyondViewportPageCount = SecurityPasswordFlowStep.entries.lastIndex,
                            modifier = Modifier.fillMaxWidth(),
                        ) { page ->
                            val pageStep = SecurityPasswordFlowStep.fromOrdinal(page)
                            Box(Modifier.padding(horizontal = SettingsStepHorizontalPadding)) {
                                SecurityPasswordStepPage(
                                    step = pageStep,
                                    scheme = scheme,
                                    passwordFieldColors = passwordFieldColors,
                                    current = current,
                                    onCurrentChange = { current = it },
                                    newP = newP,
                                    onNewPChange = { newP = it },
                                    confirmP = confirmP,
                                    onConfirmPChange = { confirmP = it },
                                    busy = busy,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }

                    IconButton(
                        onClick = {
                            if (pagerState.currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 4.dp, top = 4.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = SettingsStepHorizontalPadding)
                        .padding(top = 12.dp, bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        when (step) {
                            SecurityPasswordFlowStep.Current -> {
                                Button(
                                    onClick = {
                                        if (current.isBlank()) {
                                            showSnack(fillAll)
                                            return@Button
                                        }
                                        SecurityPasswordDraft.current = current
                                        scope.launch { pagerState.animateScrollToPage(1) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp),
                                    shape = CtaShape,
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 0.dp,
                                        pressedElevation = 0.dp,
                                        focusedElevation = 0.dp,
                                        hoveredElevation = 0.dp,
                                        disabledElevation = 0.dp,
                                    ),
                                ) {
                                    PasswordFlowBottomButtonText(stringResource(Res.string.settings_next))
                                }
                            }

                            SecurityPasswordFlowStep.New -> {
                                Button(
                                    onClick = {
                                        if (newP.length !in 5..50) {
                                            showSnack(pwdLen)
                                            return@Button
                                        }
                                        SecurityPasswordDraft.newPassword = newP
                                        scope.launch { pagerState.animateScrollToPage(2) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp),
                                    shape = CtaShape,
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 0.dp,
                                        pressedElevation = 0.dp,
                                        focusedElevation = 0.dp,
                                        hoveredElevation = 0.dp,
                                        disabledElevation = 0.dp,
                                    ),
                                ) {
                                    PasswordFlowBottomButtonText(stringResource(Res.string.settings_next))
                                }
                            }

                            SecurityPasswordFlowStep.Confirm -> {
                                Button(
                                    onClick = {
                                        if (confirmP.isBlank()) {
                                            showSnack(fillAll)
                                            return@Button
                                        }
                                        if (SecurityPasswordDraft.newPassword != confirmP) {
                                            showSnack(pwdMatch)
                                            return@Button
                                        }
                                        if (SecurityPasswordDraft.newPassword.length !in 5..50) {
                                            showSnack(pwdLen)
                                            return@Button
                                        }
                                        if (username.isBlank()) {
                                            showSnack(errUnexpected)
                                            return@Button
                                        }
                                        scope.launch {
                                            busy = true
                                            runCatching {
                                                val curD = deriveAuthSecret(username, SecurityPasswordDraft.current)
                                                val newD = deriveAuthSecret(username, SecurityPasswordDraft.newPassword)
                                                ApiClient.changePassword(curD, newD, true)
                                            }.onSuccess {
                                                SecurityPasswordDraft.clear()
                                                showSnack(okMsg)
                                                onDonePopToHub()
                                            }.onFailure { e ->
                                                val msg = (e as? ClientRequestException)?.response?.let { "Error ${it.status.value}" }
                                                    ?: e.message
                                                    ?: errUnexpected
                                                showSnack(msg)
                                            }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp),
                                    shape = CtaShape,
                                    elevation = ButtonDefaults.buttonElevation(
                                        defaultElevation = 0.dp,
                                        pressedElevation = 0.dp,
                                        focusedElevation = 0.dp,
                                        hoveredElevation = 0.dp,
                                        disabledElevation = 0.dp,
                                    ),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AnimatedContent(
                                            targetState = busy,
                                            transitionSpec = {
                                                (fadeIn(tween(200)) + slideInVertically { it / 4 }) togetherWith
                                                        (fadeOut(tween(200)) + slideOutVertically { -it / 4 })
                                            },
                                            label = "change_password_cta"
                                        ) { loading ->
                                            if (loading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            } else {
                                                PasswordFlowBottomButtonText(stringResource(Res.string.settings_change_password))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FromChatSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SettingsStepHorizontalPadding)
                    .padding(bottom = 76.dp)
                    .fillMaxWidth(),
                snackbarModifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun PasswordFlowBottomButtonText(text: String) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                    (fadeOut(tween(220)) + slideOutVertically { -it / 3 })
        },
        label = "settings_password_button_text"
    ) { label ->
        Text(label)
    }
}

@Composable
private fun SecurityPasswordStepPage(
    step: SecurityPasswordFlowStep,
    scheme: androidx.compose.material3.ColorScheme,
    passwordFieldColors: TextFieldColors,
    current: String,
    onCurrentChange: (String) -> Unit,
    newP: String,
    onNewPChange: (String) -> Unit,
    confirmP: String,
    onConfirmPChange: (String) -> Unit,
    busy: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            SecurityPasswordFlowStep.Current -> {
                Text(
                    text = stringResource(Res.string.settings_security_step_current_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.settings_security_step_current_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                SecurityPasswordOutlinedField(
                    value = current,
                    onValueChange = onCurrentChange,
                    label = { Text(stringResource(Res.string.settings_current_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = passwordFieldColors,
                )
            }

            SecurityPasswordFlowStep.New -> {
                Text(
                    text = stringResource(Res.string.settings_security_step_new_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.settings_security_step_new_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                SecurityPasswordOutlinedField(
                    value = newP,
                    onValueChange = onNewPChange,
                    label = { Text(stringResource(Res.string.settings_new_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = passwordFieldColors,
                )
            }

            SecurityPasswordFlowStep.Confirm -> {
                Text(
                    text = stringResource(Res.string.settings_security_step_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.settings_security_step_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                SecurityPasswordOutlinedField(
                    value = confirmP,
                    onValueChange = onConfirmPChange,
                    label = { Text(stringResource(Res.string.settings_confirm_new_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    colors = passwordFieldColors,
                )
            }
        }
    }
}

/**
 * Large step icon with gradient fill; **shape morphs** between library polygons ([MaterialShapes] via [Morph]),
 * icon **crossfades**.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class)
@Composable
fun SettingsSecurityMorphedPasswordHero(
    step: SecurityPasswordFlowStep,
    modifier: Modifier = Modifier,
    containerSize: Dp = 132.dp,
    iconSize: Dp = 48.dp,
    predictiveProgress: Float? = null,
    predictiveFromStep: SecurityPasswordFlowStep? = null,
    predictiveToStep: SecurityPasswordFlowStep? = null,
) {
    val usePredictive = predictiveProgress != null && predictiveFromStep != null && predictiveToStep != null

    val fromStep = when {
        usePredictive -> predictiveFromStep
        else -> step
    }
    val toStep = when {
        usePredictive -> predictiveToStep
        else -> step
    }

    val morph = remember(fromStep, toStep) {
        Morph(
            securityHeroMaterialPolygon(fromStep),
            securityHeroMaterialPolygon(toStep),
        )
    }

    val scheme = MaterialTheme.colorScheme
    val fromContainer = when (fromStep) {
        SecurityPasswordFlowStep.Current -> scheme.secondaryContainer
        SecurityPasswordFlowStep.New -> scheme.tertiaryContainer
        SecurityPasswordFlowStep.Confirm -> scheme.primaryContainer
    }
    val toContainer = when (toStep) {
        SecurityPasswordFlowStep.Current -> scheme.secondaryContainer
        SecurityPasswordFlowStep.New -> scheme.tertiaryContainer
        SecurityPasswordFlowStep.Confirm -> scheme.primaryContainer
    }
    val fromContent = when (fromStep) {
        SecurityPasswordFlowStep.Current -> scheme.onSecondaryContainer
        SecurityPasswordFlowStep.New -> scheme.onTertiaryContainer
        SecurityPasswordFlowStep.Confirm -> scheme.onPrimaryContainer
    }
    val toContent = when (toStep) {
        SecurityPasswordFlowStep.Current -> scheme.onSecondaryContainer
        SecurityPasswordFlowStep.New -> scheme.onTertiaryContainer
        SecurityPasswordFlowStep.Confirm -> scheme.onPrimaryContainer
    }

    val p = (predictiveProgress ?: 0f).coerceIn(0f, 1f)
    val deep = lerp(fromContainer, toContainer, p)
    val contentColor = lerp(fromContent, toContent, p)
    val light = deep.copy(alpha = 0.72f)

    Box(
        modifier = modifier.size(containerSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = minOf(size.width, size.height) * 0.94f

            translate(left = size.width / 2f, top = size.height / 2f) {
                scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
                    translate(left = -0.5f, top = -0.5f) {
                        drawPath(
                            path = morph.toPath(p, Path()),
                            brush = Brush.linearGradient(
                                colors = listOf(light, deep),
                                start = Offset.Zero,
                                end = Offset(1f, 1f),
                            ),
                        )
                    }
                }
            }
        }

        val fromIcon = when (fromStep) {
            SecurityPasswordFlowStep.Current -> Icons.Filled.Key
            SecurityPasswordFlowStep.New -> Icons.Filled.Lock
            SecurityPasswordFlowStep.Confirm -> Icons.Filled.VerifiedUser
        }

        val toIcon = when (toStep) {
            SecurityPasswordFlowStep.Current -> Icons.Filled.Key
            SecurityPasswordFlowStep.New -> Icons.Filled.Lock
            SecurityPasswordFlowStep.Confirm -> Icons.Filled.VerifiedUser
        }

        if (usePredictive && fromStep != toStep) {
            Box(
                modifier = Modifier.size(iconSize),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fromIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 1f - p },
                    tint = contentColor,
                )
                Icon(
                    imageVector = toIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = p },
                    tint = contentColor,
                )
            }
        } else {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                },
                label = "security_hero_icon"
            ) { s ->
                val icon = when (s) {
                    SecurityPasswordFlowStep.Current -> Icons.Filled.Key
                    SecurityPasswordFlowStep.New -> Icons.Filled.Lock
                    SecurityPasswordFlowStep.Confirm -> Icons.Filled.VerifiedUser
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = contentColor
                )
            }
        }
    }
}