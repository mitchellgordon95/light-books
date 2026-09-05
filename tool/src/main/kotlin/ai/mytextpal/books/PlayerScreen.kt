package ai.mytextpal.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel : LightViewModel<Unit>() {
    /** Ticks once a second while visible so the sleep countdown moves. */
    val now = MutableStateFlow(System.currentTimeMillis())
    private var tickJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                now.value = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        tickJob?.cancel()
        tickJob = null
    }
}

class PlayerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() = PlayerViewModel()

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val book by Playback.book.collectAsState()
        val playing by Playback.isPlaying.collectAsState()
        val position by Playback.positionMs.collectAsState()
        val duration by Playback.durationMs.collectAsState()
        val part by Playback.partIndex.collectAsState()
        val chapters by Playback.chapters.collectAsState()
        val chapter by Playback.currentChapter.collectAsState()
        val speed by Playback.speed.collectAsState()
        val sleepEndsAt by Playback.sleepEndsAt.collectAsState()
        val sleepAtChapterEnd by Playback.sleepAtChapterEnd.collectAsState()
        val error by Playback.error.collectAsState()
        val fault by Playback.fault.collectAsState()
        val now by viewModel.now.collectAsState()
        val loading by Playback.loading.collectAsState()

        val current = book
        val partCount = current?.files?.size ?: 0
        val navigable = BookChapters.isNavigable(chapters)
        val ch = chapter

        // With chapters, the bar and the clocks describe the chapter; otherwise the file.
        val windowStart = if (navigable && ch != null) ch.startMs else 0L
        val windowEnd = if (navigable && ch != null && ch.endMs > ch.startMs) minOf(ch.endMs, if (duration > 0L) duration else ch.endMs) else duration
        val windowLen = (windowEnd - windowStart).coerceAtLeast(0L)
        val elapsed = (position - windowStart).coerceIn(0L, windowLen)

        val subline = when {
            current == null -> ""
            navigable && ch != null -> "Chapter ${ch.index + 1} of ${chapters.size}"
            partCount > 1 && part >= 0 -> "Part ${part + 1} of $partCount"
            else -> ""
        }

        LightTheme(colors = colors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Now playing"),
                )

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    Spacer(Modifier.weight(1f))
                    LightText(
                        text = current?.title ?: "Nothing playing",
                        variant = LightTextVariant.Heading,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (navigable && ch != null) {
                        LightText(
                            text = ch.title,
                            variant = LightTextVariant.Copy,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 0.5f.gridUnitsAsDp())
                                .lightClickable { navigateTo(::ChaptersScreen) },
                        )
                    }
                    if (subline.isNotEmpty()) {
                        LightText(
                            text = subline,
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                    }
                    Spacer(Modifier.weight(1f))

                    LightTouchableProgressBar(
                        colors = LightThemeTokens.colors,
                        progress = if (windowLen > 0L) elapsed.toFloat() / windowLen else 0f,
                        onValueChange = { fraction -> if (windowLen > 0L) Playback.seekTo(windowStart + (fraction * windowLen).toLong()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        LightText(
                            text = if (loading) "Opening…" else formatDuration(elapsed),
                            variant = LightTextVariant.Fine,
                            monospace = true,
                            modifier = Modifier.weight(1f),
                        )
                        LightText(
                            text = if (windowLen > 0L && !loading) "-" + formatDuration(windowLen - elapsed) else "--:--",
                            variant = LightTextVariant.Fine,
                            monospace = true,
                            align = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    (fault ?: error?.let { "${it.kind}: ${it.diagnostic}" })?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            align = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 1f.gridUnitsAsDp()),
                    ) {
                        Option("SPEED", speedLabel(speed), Modifier.weight(1f), Playback::cycleSpeed)
                        Option(
                            "SLEEP",
                            when {
                                sleepAtChapterEnd -> "chapter"
                                sleepEndsAt > now -> formatDuration(sleepEndsAt - now)
                                else -> "off"
                            },
                            Modifier.weight(1f),
                            Playback::cycleSleep,
                        )
                        Option(
                            "CHAPTERS",
                            if (navigable) "${chapters.size}" else if (chapters.isEmpty()) "…" else "none",
                            Modifier.weight(1f),
                        ) { if (navigable) navigateTo(::ChaptersScreen) }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            LightIcons.REWIND,
                            when {
                                navigable -> ({ Playback.previousChapter() })
                                partCount > 1 -> ({ Playback.previousPart() })
                                else -> null
                            },
                            contentDescription = "Previous chapter",
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.SKIP_BACKWARD_FIFTEEN,
                            { Playback.skip(-Playback.SKIP_MS) },
                        ),
                        LightBarButton.LightIcon(
                            if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                            Playback::togglePlayPause,
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.SKIP_FORWARD_FIFTEEN,
                            { Playback.skip(Playback.SKIP_MS) },
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.FAST_FORWARD,
                            when {
                                navigable -> ({ Playback.nextChapter() })
                                partCount > 1 -> ({ Playback.nextPart() })
                                else -> null
                            },
                            contentDescription = "Next chapter",
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Option(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Superfine,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LightText(
            text = value,
            variant = LightTextVariant.Fine,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
