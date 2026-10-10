package ai.mytextpal.books

import android.util.Log
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerException
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one player for the tool, living for the whole process rather than one screen.
 *
 * Detached playback means the SDK's media service keeps playing after the screen that
 * started it is gone — but somebody still has to keep saving the position while the book
 * plays in the background. Screens come and go; this object doesn't.
 *
 * Handle lifetime: while the handle is open the SDK service never idles out. So ten
 * minutes after a pause we let go of it (the service then stops itself after its own
 * 15-minute idle rule), which also hands the earbuds' media button back to whatever
 * played before. Opening a book, or pressing play, reconnects — to the still-live queue
 * if the service is around, or to a fresh one loaded from the saved position if not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Playback {
    private const val TAG = "BooksPlayback"
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val IDLE_RELEASE_MS = 10 * 60_000L
    private const val PREPARE_TIMEOUT_MS = 8_000L
    /** Keep trying a resume seek this long before giving up and letting saves through. */
    private const val SEEK_GIVE_UP_MS = 60_000L
    private const val SEEK_RETRY_MS = 250L
    private const val SEEK_TOLERANCE_MS = 1_500L
    /** The SDK polls the position every 250 ms; a part change shows up before the next poll. */
    private const val POSITION_SETTLE_MS = 400L
    private const val SAVE_DEBOUNCE_MS = 1_000L
    private const val SEEK_ATTEMPTS = 8
    const val SKIP_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var audio: LightAudio? = null
    private var store: PositionStore? = null
    private var started = false

    private val playerFlow = MutableStateFlow<LightAudioPlayer?>(null)

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    val isPlaying: StateFlow<Boolean> = playerFlow
        .flatMapLatest { it?.isPlaying ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    val positionMs: StateFlow<Long> = playerFlow
        .flatMapLatest { it?.positionMs ?: flowOf(0L) }
        .stateIn(scope, SharingStarted.Eagerly, 0L)
    val durationMs: StateFlow<Long> = playerFlow
        .flatMapLatest { it?.durationMs ?: flowOf(0L) }
        .stateIn(scope, SharingStarted.Eagerly, 0L)
    val partIndex: StateFlow<Int> = playerFlow
        .flatMapLatest { it?.currentMediaItemIndex ?: flowOf(-1) }
        .stateIn(scope, SharingStarted.Eagerly, -1)
    val error: StateFlow<LightAudioError?> = playerFlow
        .flatMapLatest { it?.error ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Table of contents for the open book (one entry per file when files have no markers). */
    private val _chapters = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapters: StateFlow<List<BookChapter>> = _chapters.asStateFlow()

    val currentChapter: StateFlow<BookChapter?> = combine(_chapters, partIndex, positionMs) { list, part, pos ->
        if (part < 0) null else BookChapters.at(list, part, pos)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Sleep timer variant: pause when the current chapter ends. */
    private val _sleepAtChapterEnd = MutableStateFlow(false)
    val sleepAtChapterEnd: StateFlow<Boolean> = _sleepAtChapterEnd.asStateFlow()

    /** Epoch ms when the sleep timer pauses playback; 0 = no timer. */
    private val _sleepEndsAt = MutableStateFlow(0L)
    val sleepEndsAt: StateFlow<Long> = _sleepEndsAt.asStateFlow()

    /** Set when a player command fails outright (no capability, service refused). */
    private val _fault = MutableStateFlow<String?>(null)
    val fault: StateFlow<String?> = _fault.asStateFlow()

    /** True while a book's queue is being prepared and seeked; the UI shows "Opening…". */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var pausedAt = 0L
    private var idleJob: Job? = null
    private var sleepJob: Job? = null
    private var saveSoonJob: Job? = null
    /** Queue loads in flight; saves are held while any is. Overlapping loads happen on double taps. */
    private var loadsInFlight = 0
    private val isLoading: Boolean get() = loadsInFlight > 0

    /**
     * A resume seek we have issued but not yet seen land. The SDK clamps seeks to zero until
     * the part reports its duration, so a save in that window would overwrite the listener's
     * real spot with the start of the file. Saves are held until this clears.
     */
    private var pendingSeekMs: Long? = null
    private var seekJob: Job? = null

    /** Set by a chapter jump so "sleep at end of chapter" ignores that one boundary. */
    private var skipChapterSleepOnce = false

    /** Called by the first screen. Later calls just refresh the audio factory (new activity). */
    fun start(audio: LightAudio, store: PositionStore) {
        this.audio = audio
        this.store = store
        if (started) return
        started = true

        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (isPlaying.value) saveNow()
            }
        }
        scope.launch {
            isPlaying.drop(1).collect { playing ->
                if (playing) {
                    idleJob?.cancel()
                } else {
                    pausedAt = System.currentTimeMillis()
                    saveNow()
                    scheduleIdleRelease()
                }
            }
        }
        scope.launch {
            // The index moves before the next position poll, so saving at once would pair the
            // new part with the old part's end. Let the position catch up first.
            partIndex.drop(1).collectLatest { index ->
                if (index < 0) return@collectLatest
                delay(POSITION_SETTLE_MS)
                saveNow()
            }
        }
        scope.launch {
            // "Sleep at end of chapter": the moment the chapter index moves on, stop — unless
            // the listener moved it themselves.
            var last: Int? = null
            currentChapter.collect { ch ->
                val idx = ch?.index
                if (last != null && idx != null && idx != last) {
                    if (skipChapterSleepOnce) {
                        skipChapterSleepOnce = false
                    } else if (_sleepAtChapterEnd.value && isPlaying.value) {
                        playerFlow.value?.pause()
                        _sleepAtChapterEnd.value = false
                    }
                }
                last = idx
            }
        }
    }

    /** Jump to chapter [index] of the open book and play. Same file: a seek; other file: a reload. */
    fun jumpToChapter(index: Int) {
        val book = _book.value ?: return
        val ch = _chapters.value.getOrNull(index) ?: return
        scope.launch {
            if (ch.index != currentChapter.value?.index) skipChapterSleepOnce = true
            val player = playerFlow.value
            if (player != null && player.currentMediaItemIndex.value == ch.fileIndex && !isLoading) {
                seekTo(ch.startMs)
                player.play()
            } else {
                loadAndPlay(book, StartPoint(ch.fileIndex, ch.startMs, _speed.value))
            }
        }
    }

    fun nextChapter() {
        val ch = currentChapter.value ?: return
        jumpToChapter(ch.index + 1)
    }

    /** Back to the start of this chapter, or to the previous one when already near its start. */
    fun previousChapter() {
        val ch = currentChapter.value ?: return
        val intoChapter = positionMs.value - ch.startMs
        if (intoChapter > 3_000L || ch.index == 0) jumpToChapter(ch.index) else jumpToChapter(ch.index - 1)
    }

    /** Start (or continue) [book] from where the listener left off. */
    fun open(book: Book) {
        scope.launch {
            if (_book.value?.id == book.id) {
                // Already open: a second tap while it loads is a no-op, otherwise just play.
                if (isLoading) return@launch
                if (playerFlow.value != null) {
                    if (!isPlaying.value) resume()
                    return@launch
                }
            }
            saveNow()
            loadAndPlay(book)
        }
    }

    fun togglePlayPause() {
        val player = playerFlow.value
        if (player != null && isPlaying.value) player.pause() else scope.launch { resume() }
    }

    /** An explicit seek by the listener. Wins over any resume seek still pending. */
    fun seekTo(ms: Long) {
        val player = playerFlow.value ?: return
        clearPendingSeek()
        player.seekTo(ms.coerceIn(0L, durationMs.value.coerceAtLeast(0L)))
        // The playing ticker would get this within 5 s; a seek while paused has nobody else.
        saveSoon()
    }

    fun skip(deltaMs: Long) = seekTo(positionMs.value + deltaMs)

    fun nextPart() {
        playerFlow.value?.skipToNext()
    }

    fun previousPart() {
        playerFlow.value?.skipToPrevious()
    }

    fun cycleSpeed() = setSpeed(nextSpeed(_speed.value))

    fun setSpeed(value: Float) {
        _speed.value = value
        playerFlow.value?.speed = value
        scope.launch { saveNow() }
    }

    /** off → 15 → 30 → 45 → 60 min → end of chapter (when the book has chapters) → off. */
    fun cycleSleep() {
        if (_sleepAtChapterEnd.value) {
            setSleep(0L)
            return
        }
        val remaining = (_sleepEndsAt.value - System.currentTimeMillis()).coerceAtLeast(0L)
        val next = nextSleepMs(remaining)
        if (next == 0L && BookChapters.isNavigable(_chapters.value)) {
            setSleep(0L)
            _sleepAtChapterEnd.value = true
        } else {
            setSleep(next)
        }
    }

    fun setSleep(ms: Long) {
        sleepJob?.cancel()
        _sleepAtChapterEnd.value = false
        if (ms <= 0L) {
            _sleepEndsAt.value = 0L
            return
        }
        _sleepEndsAt.value = System.currentTimeMillis() + ms
        sleepJob = scope.launch {
            delay(ms)
            playerFlow.value?.pause()
            _sleepEndsAt.value = 0L
        }
    }

    /** Forget the saved spot and start [book] from the top. */
    fun restart(book: Book) {
        scope.launch {
            val isOpen = _book.value?.id == book.id
            // Drop the book first: a save tick landing while the store is cleared would otherwise
            // write the old spot straight back and the restart would silently not happen.
            if (isOpen) {
                clearPendingSeek()
                _book.value = null
            }
            store?.clear(book.id)
            if (isOpen) loadAndPlay(book)
        }
    }

    // ---- internals ----

    private suspend fun resume() {
        val book = _book.value ?: return
        val player = ensurePlayer() ?: return
        if (player.currentMediaItemIndex.value < 0) {
            // The service idled out while we were away; rebuild from the saved spot.
            loadAndPlay(book)
            return
        }
        // pausedAt is 0 when nothing has paused since the load (play never started, say); the
        // load already applied its own rewind, so don't add another 30 s on top.
        val rewind = if (pausedAt > 0L) resumeRewindMs(System.currentTimeMillis() - pausedAt) else 0L
        if (rewind > 0L && positionMs.value > 0L) player.seekTo((positionMs.value - rewind).coerceAtLeast(0L))
        player.play()
    }

    /** Load [book]'s queue and play from [at], or from the saved spot when [at] is null. */
    private suspend fun loadAndPlay(book: Book, at: StartPoint? = null) {
        val store = store ?: return
        clearPendingSeek()
        loadsInFlight++
        _loading.value = true
        try {
            val player = ensurePlayer() ?: return
            if (_book.value?.id != book.id) _chapters.value = emptyList()
            _book.value = book
            pausedAt = 0L
            val start = at ?: startPoint(store.load(book.id), book.files.size, System.currentTimeMillis())
            _speed.value = start.speed
            player.speed = start.speed
            player.setMediaQueue(book.toQueue(), start.fileIndex)
            if (start.posMs > 0L) {
                pendingSeekMs = start.posMs
                val t0 = System.currentTimeMillis()
                val landed = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { seekWhenPrepared(player, start.posMs) }
                Log.i(TAG, "Resume seek to ${start.posMs}: landed=$landed after ${System.currentTimeMillis() - t0} ms")
                if (landed == true) {
                    pendingSeekMs = null
                } else {
                    // Still not prepared. Start the audio rather than hang, keep trying behind
                    // it, and hold saves until the seek lands (see pendingSeekMs).
                    seekJob = scope.launch {
                        val ok = withTimeoutOrNull(SEEK_GIVE_UP_MS) { seekWhenPrepared(player, start.posMs) }
                        if (ok != true) Log.w(TAG, "Gave up resuming at ${start.posMs}; saving from the live position")
                        if (pendingSeekMs == start.posMs) pendingSeekMs = null
                    }
                }
            }
            player.play()
        } finally {
            loadsInFlight--
            _loading.value = isLoading
        }
        if (_chapters.value.isEmpty()) {
            // Read (or fetch from cache) after playback has started so it never delays the audio.
            val embedded = store.chapters(book)
            val durations = store.partDurations(book)
            if (_book.value?.id == book.id) _chapters.value = BookChapters.assemble(book, embedded, durations)
        }
    }

    /**
     * Seeks to [posMs] once the part reports a duration (the SDK clamps seeks to zero before
     * that) and confirms the seek took. A stale duration from the previous queue makes the
     * first attempt clamp; the retry catches it. Returns false after too many misses.
     */
    private suspend fun seekWhenPrepared(player: LightAudioPlayer, posMs: Long): Boolean {
        repeat(SEEK_ATTEMPTS) { attempt ->
            val duration = player.durationMs.first { it > 0L }
            val target = minOf(posMs, duration)
            player.seekTo(target)
            // seekTo refreshes positionMs synchronously, so a clamped seek reads as ~0 right away.
            if (player.positionMs.value >= target - SEEK_TOLERANCE_MS) return true
            Log.w(TAG, "Seek to $target read back ${player.positionMs.value} (duration $duration, attempt $attempt)")
            delay(SEEK_RETRY_MS)
        }
        return false
    }

    private fun clearPendingSeek() {
        seekJob?.cancel()
        seekJob = null
        pendingSeekMs = null
    }

    private fun saveSoon() {
        saveSoonJob?.cancel()
        saveSoonJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveNow()
        }
    }

    private suspend fun ensurePlayer(): LightAudioPlayer? {
        playerFlow.value?.let { return it }
        val audio = audio ?: return null
        val player = try {
            audio.newPlayer(usage = LightAudioUsage.Speech, playback = LightAudioPlayback.Detached)
        } catch (e: LightAudioPlayerException) {
            Log.e(TAG, "Cannot create detached player", e)
            _fault.value = e.message ?: "Cannot start audio"
            return null
        }
        if (!player.awaitReady()) {
            Log.e(TAG, "Player released before it became ready")
            _fault.value = "Audio service unavailable"
            return null
        }
        _fault.value = null
        playerFlow.value = player
        return player
    }

    private suspend fun saveNow() {
        val store = store ?: return
        val book = _book.value ?: return
        val index = partIndex.value
        // While a queue is being swapped in, the flows briefly describe the wrong book; while a
        // resume seek is pending, the position is the start of the file, not the listener's.
        if (index < 0 || isLoading || pendingSeekMs != null) return
        val finished = isBookFinished(index, book.files.size, positionMs.value, durationMs.value) && !isPlaying.value
        store.save(
            book.id,
            SavedPosition(
                fileIndex = if (finished) 0 else index,
                posMs = if (finished) 0L else positionMs.value.coerceAtLeast(0L),
                speed = _speed.value,
                updatedAt = System.currentTimeMillis(),
                finished = finished,
            ),
        )
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (isPlaying.value) return@launch
            saveNow()
            playerFlow.value?.release()
            playerFlow.value = null
            setSleep(0L)
        }
    }

    private fun Book.toQueue(): List<LightAudioItem> = files.mapIndexed { i, file ->
        LightAudioItem(
            source = LightAudioSource.FileSource(file),
            metadata = LightMediaMetadata(
                title = title,
                artist = if (files.size > 1) "Part ${i + 1} of ${files.size}" else file.nameWithoutExtension,
                album = title,
            ),
        )
    }
}
