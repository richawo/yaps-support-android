package ai.yaps.support.ui

import ai.yaps.support.R
import ai.yaps.support.internal.core.ChatState
import ai.yaps.support.internal.core.ConnectionStatus
import ai.yaps.support.internal.core.ResolvedAppearance
import ai.yaps.support.internal.outbox.PendingAttachment
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** The stateless chat screen. [ai.yaps.support.SupportChat] wires it to the engine; previews feed it fake state. */
@Composable
internal fun ChatContent(
    state: ChatState,
    appearance: ResolvedAppearance,
    env: BubbleEnv,
    draft: String,
    staged: List<PendingAttachment>,
    preparing: Int,
    composer: ComposerActions,
    onLoadEarlier: () -> Unit,
    onClose: (() -> Unit)?,
    hint: String?,
    trayOpen: Boolean,
    onTrayOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSupportColors.current
    val timeline = remember(state.messages, state.pending, state.uploadProgress, state.agentReadSeq) { buildTimeline(state) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val title = appearance.title ?: stringResource(R.string.yaps_support_title)

    // Entrance bookkeeping: the last three rows of the first view stagger in 50ms apart; anything that
    // arrives later rises (theirs) or flies out of the composer (yours); rows scrolled back into view don't animate.
    val seen = remember { HashSet<String>() }
    var firstView by remember { mutableStateOf<Set<String>?>(null) }
    if (firstView == null && (state.loaded || timeline.isNotEmpty())) {
        firstView = timeline.takeLast(3).map { it.key }.toSet()
        seen.addAll(timeline.dropLast(3).map { it.key })
    }

    // Newest message at the bottom: the list is reversed, so index 0 is the newest row. Stay pinned
    // to the end unless the reader has scrolled away.
    val pinned by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < with(density) { 140.dp.toPx() } } }
    var unseenReply by remember { mutableStateOf(false) }
    val newestKey = timeline.lastOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey == null) return@LaunchedEffect
        if (pinned || listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
        else if ((timeline.last() as? TimelineItem.Server)?.message?.isFromCustomer == false) unseenReply = true
    }
    LaunchedEffect(pinned) { if (pinned) unseenReply = false }
    val nearTop by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3 } }
    LaunchedEffect(listState, state.hasMore) {
        snapshotFlow { nearTop }.distinctUntilChanged().filter { it }.collect { if (state.hasMore) onLoadEarlier() }
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            Header(title, appearance, state, env, onClose, scrolled = !pinned || listState.canScrollForward)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite },
                    contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
                ) {
                    if (state.agentTyping) item(key = "typing") { TypingIndicator(state.agentTypingName.takeIf { env.showNames }) }
                    val reversed = timeline.asReversed()
                    itemsIndexed(reversed, key = { _, it -> it.key }, contentType = { _, it -> it::class }) { index, item ->
                        val (entrance, delay) = remember(item.key) {
                            val fv = firstView
                            when {
                                item.key in seen -> Entrance.None to 0
                                fv != null && item.key in fv -> Entrance.Rise to (130 + (fv.size - 1 - index).coerceAtLeast(0) * 50)
                                item is TimelineItem.Pending -> Entrance.Fly to 0
                                else -> Entrance.Rise to 0
                            }.also { seen += item.key }
                        }
                        when (item) {
                            is TimelineItem.DayHeader -> Box(Modifier.entrance(entrance, delay)) { DayHeader(item.dayStartMillis) }
                            is TimelineItem.Server -> ServerMessageRow(item, env, appearance.fadeOlderMessages && index > FADE_AFTER, entrance, delay)
                            is TimelineItem.Pending -> PendingMessageRow(item, env, entrance)
                        }
                    }
                    if (state.hasMore) {
                        item(key = "earlier") {
                            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                                if (state.loadingEarlier) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onIncoming, trackColor = colors.divider)
                                else Pill(stringResource(R.string.yaps_support_load_earlier), strong = false, onClick = onLoadEarlier)
                            }
                        }
                    } else if (state.loaded || state.messages.isNotEmpty() || state.pending.isNotEmpty()) {
                        item(key = "welcome") { WelcomeHeader(appearance, env) }
                    }
                }
                JumpPill(
                    visible = !pinned,
                    newReply = unseenReply,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
            Composer(
                text = draft,
                staged = staged,
                preparing = preparing,
                restriction = state.restriction,
                trayOpen = trayOpen,
                onTrayOpenChange = onTrayOpenChange,
                hint = hint,
                actions = composer,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime).union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
            )
        }
    }
}

private const val FADE_AFTER = 12

/**
 * Header from the mobile-chat brief: 40dp round control at the start, the identity centred (34dp
 * avatar, name 16/700/−0.3, a 12.5 status that crossfades with a 6dp lift), and a centre-faded
 * hairline at the bottom edge once the thread scrolls under it. Connection and typing states live in
 * the status line instead of a banner.
 */
@Composable
private fun Header(
    title: String,
    appearance: ResolvedAppearance,
    state: ChatState,
    env: BubbleEnv,
    onClose: (() -> Unit)?,
    scrolled: Boolean,
) {
    val colors = LocalSupportColors.current
    val density = LocalDensity.current
    val status: Pair<String, Color?>? = when {
        state.connection == ConnectionStatus.OFFLINE -> stringResource(R.string.yaps_support_connection_offline) to colors.onIncoming
        state.connection == ConnectionStatus.CONNECTING -> stringResource(R.string.yaps_support_connection_connecting) to null
        state.connection == ConnectionStatus.PAUSED -> stringResource(R.string.yaps_support_connection_paused) to null
        state.agentTyping -> (state.agentTypingName?.takeIf { env.showNames }?.let { stringResource(R.string.yaps_support_typing, it) }
            ?: stringResource(R.string.yaps_support_typing_generic)) to colors.accent
        state.connection == ConnectionStatus.CONNECTED -> stringResource(R.string.yaps_support_connection_connected) to colors.accent
        else -> null
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .drawBehind { if (scrolled) drawFadedHairline(colors.divider, top = false) }
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp),
    ) {
        if (onClose != null && appearance.showCloseButton) {
            val interaction = remember { MutableInteractionSource() }
            val label = stringResource(R.string.yaps_support_close)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .entrance(Entrance.Rise, 0, 6.dp)
                    .squish(interaction, 0.9f)
                    .size(Metrics.Control)
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = label },
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.raised).border(BorderStroke(0.5.dp, colors.divider), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, contentDescription = null, tint = colors.onIncoming, modifier = Modifier.size(20.dp)) }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 52.dp)
                .entrance(Entrance.Rise, 50, 6.dp)
                .semantics(mergeDescendants = true) { heading() },
        ) {
            HeaderAvatar(appearance, env, title)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = TypeTokens.Name, color = colors.onIncoming, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedContent(
                    targetState = status,
                    transitionSpec = {
                        (fadeIn(tween(240)) + slideInVertically(tween(320, easing = EaseOutBrief)) { with(density) { 6.dp.roundToPx() } })
                            .togetherWith(fadeOut(tween(160)) + slideOutVertically(tween(200)) { -with(density) { 6.dp.roundToPx() } })
                    },
                    label = "status",
                ) { s ->
                    if (s != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            s.second?.let { dot ->
                                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(s.first, style = TypeTokens.Status, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderAvatar(appearance: ResolvedAppearance, env: BubbleEnv, title: String) {
    val colors = LocalSupportColors.current
    val modifier = Modifier.size(34.dp).clip(CircleShape)
    when {
        appearance.welcomeIconUrl != null && env.imageLoader != null ->
            AsyncImage(model = appearance.welcomeIconUrl, imageLoader = env.imageLoader, contentDescription = null, modifier = modifier.background(colors.raised))
        appearance.welcomeIconRes != null -> Image(painterResource(appearance.welcomeIconRes), contentDescription = null, modifier = modifier)
        else -> Box(
            modifier.background(colors.onIncoming.copy(alpha = 0.09f)).border(BorderStroke(0.5.dp, colors.divider), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text((env.appName ?: title).trim().firstOrNull()?.uppercase() ?: "·", style = TypeTokens.Name.copy(fontSize = 15.sp), color = colors.onIncoming)
        }
    }
}

@Composable
private fun JumpPill(visible: Boolean, newReply: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalSupportColors.current
    val interaction = remember { MutableInteractionSource() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(260, easing = EaseOutBrief), initialScale = 0.9f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .squish(interaction, 0.94f)
                .heightIn(min = Metrics.Control)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(36.dp)
                    .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                    .clip(CircleShape)
                    .background(colors.raised)
                    .border(BorderStroke(0.5.dp, colors.divider), CircleShape)
                    .padding(start = 14.dp, end = 10.dp),
            ) {
                if (newReply) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    stringResource(if (newReply) R.string.yaps_support_new_reply else R.string.yaps_support_latest),
                    style = TypeTokens.Status.copy(fontWeight = FontWeight.Medium),
                    color = colors.onIncoming,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.onIncoming, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun WelcomeHeader(appearance: ResolvedAppearance, env: BubbleEnv) {
    val colors = LocalSupportColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 12.dp),
    ) {
        Text(
            (env.appName ?: "").uppercase(),
            style = TypeTokens.Tag,
            color = colors.faintText,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            appearance.welcomeTitle ?: stringResource(R.string.yaps_support_welcome_title),
            style = TypeTokens.Figure.copy(fontSize = 26.sp, lineHeight = 28.sp, letterSpacing = (-0.8).sp),
            color = colors.onIncoming,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            appearance.welcomeText ?: stringResource(R.string.yaps_support_welcome_text),
            style = TypeTokens.Bubble.copy(fontSize = 15.sp, lineHeight = 22.sp),
            color = colors.secondaryText,
            textAlign = TextAlign.Center,
        )
    }
}
