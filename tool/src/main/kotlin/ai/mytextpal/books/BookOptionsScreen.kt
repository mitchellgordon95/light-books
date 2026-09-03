package ai.mytextpal.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Long-press a book in the library to get here. The screen's result is `true` when
 * something changed, so the library refreshes right away.
 */
class BookOptionsViewModel(
    private val store: PositionStore,
    val row: BookRow,
    initiallyArchived: Boolean,
) : LightViewModel<Boolean>() {
    val archived = MutableStateFlow(initiallyArchived)
    val confirmingRestart = MutableStateFlow(false)
    val restarted = MutableStateFlow(false)
    var changed = false
        private set
    private var confirmJob: Job? = null

    fun toggleArchived() {
        val next = !archived.value
        archived.value = next
        changed = true
        viewModelScope.launch { store.setArchived(row.book.id, next) }
    }

    fun tapRestart() {
        if (confirmingRestart.value) {
            confirmJob?.cancel()
            confirmingRestart.value = false
            restarted.value = true
            changed = true
            Playback.restart(row.book)
        } else {
            confirmingRestart.value = true
            confirmJob?.cancel()
            confirmJob = viewModelScope.launch {
                delay(3_000L)
                confirmingRestart.value = false
            }
        }
    }
}

class BookOptionsScreen(
    sealedActivity: SealedLightActivity,
    private val row: BookRow,
    private val initiallyArchived: Boolean,
) : LightScreen<Boolean, BookOptionsViewModel>(sealedActivity) {

    override val viewModelClass = BookOptionsViewModel::class.java
    override fun createViewModel() = BookOptionsViewModel(PositionStore(lightContext.dataStore), row, initiallyArchived)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val archived by viewModel.archived.collectAsState()
        val confirming by viewModel.confirmingRestart.collectAsState()
        val restarted by viewModel.restarted.collectAsState()

        LightTheme(colors = colors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(viewModel.changed) }),
                    center = LightTopBarCenter.Text("Book"),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = row.book.title,
                        variant = LightTextVariant.Heading,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = if (restarted) "Restarted from the beginning" else row.status,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    OptionRow(
                        title = if (archived) "UNARCHIVE" else "ARCHIVE",
                        subtitle = if (archived) "Show it in the library again" else "Hide it from the library; your place is kept",
                        onClick = viewModel::toggleArchived,
                    )
                    OptionRow(
                        title = if (confirming) "TAP AGAIN TO RESTART" else "RESTART FROM BEGINNING",
                        subtitle = "Forgets your place in this book",
                        onClick = viewModel::tapRestart,
                    )
                    LightText(
                        text = "To remove the files themselves, delete them from the phone's Audiobooks folder (the tool isn't allowed to).",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy)
        LightText(text = subtitle, variant = LightTextVariant.Fine, lighten = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
