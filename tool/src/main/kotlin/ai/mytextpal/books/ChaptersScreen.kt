package ai.mytextpal.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class ChaptersViewModel : LightViewModel<Unit>()

/** The table of contents: every chapter with its length, the playing one marked. Tap to jump. */
class ChaptersScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ChaptersViewModel>(sealedActivity) {

    override val viewModelClass = ChaptersViewModel::class.java
    override fun createViewModel() = ChaptersViewModel()

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val book by Playback.book.collectAsState()
        val chapters by Playback.chapters.collectAsState()
        val current by Playback.currentChapter.collectAsState()

        LightTheme(colors = colors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Chapters"),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = book?.title ?: "",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                    if (chapters.isEmpty()) {
                        LightText(text = "Reading chapters…", variant = LightTextVariant.Copy, lighten = true)
                    }
                    chapters.forEach { ch ->
                        val playing = ch.index == current?.index
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    Playback.jumpToChapter(ch.index)
                                    goBack()
                                }
                                .padding(vertical = 0.5f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = if (playing) "›" else "${ch.index + 1}",
                                variant = LightTextVariant.Copy,
                                lighten = !playing,
                                align = TextAlign.End,
                                modifier = Modifier.width(2f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = ch.title,
                                variant = LightTextVariant.Copy,
                                lighten = !playing,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 0.75f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = if (ch.lengthMs > 0L) formatDuration(ch.lengthMs) else "",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                monospace = true,
                                align = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}
