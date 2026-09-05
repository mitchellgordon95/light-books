package ai.mytextpal.books

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The whole point of this tool: remembers where you are in every book. Backed by the
 * SDK's DataStore, which survives process death, reboots, and reinstalls.
 */
class PositionStore(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun positionKey(bookId: String) = stringPreferencesKey("$POS_PREFIX$bookId")

    suspend fun load(bookId: String): SavedPosition? =
        dataStore.data.first()[positionKey(bookId)]?.let(::decode)

    suspend fun save(bookId: String, saved: SavedPosition) {
        dataStore.edit { it[positionKey(bookId)] = json.encodeToString(SavedPosition.serializer(), saved) }
    }

    suspend fun clear(bookId: String) {
        dataStore.edit { it.remove(positionKey(bookId)) }
    }

    /** Every saved position, keyed by book id. One read for the whole library screen. */
    suspend fun loadAll(): Map<String, SavedPosition> =
        dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith(POS_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? String)?.let(::decode)?.let { key.name.removePrefix(POS_PREFIX) to it }
            }
            .toMap()

    /** Per-file durations, cached because extracting them opens every file. */
    suspend fun partDurations(book: Book): List<Long> {
        val prefs = dataStore.data.first()
        val missing = mutableMapOf<Preferences.Key<Long>, Long>()
        val result = withContext(Dispatchers.IO) {
            book.files.map { file ->
                val key = longPreferencesKey(DUR_PREFIX + Library.durationKey(file))
                prefs[key] ?: Library.readDurationMs(file).also { missing[key] = it }
            }
        }
        if (missing.isNotEmpty()) {
            dataStore.edit { prefs -> missing.forEach { (k, v) -> prefs[k] = v } }
        }
        return result
    }

    /** Embedded chapters per file, cached because reading them opens the file. */
    suspend fun chapters(book: Book): List<List<Chapter>> {
        val prefs = dataStore.data.first()
        val missing = mutableMapOf<Preferences.Key<String>, String>()
        val result = withContext(Dispatchers.IO) {
            book.files.map { file ->
                val key = stringPreferencesKey(CHAP_PREFIX + Library.durationKey(file))
                prefs[key]?.let { raw -> runCatching { json.decodeFromString(chapterListSerializer, raw) }.getOrNull() }
                    ?: ChapterReader.read(file).also { missing[key] = json.encodeToString(chapterListSerializer, it) }
            }
        }
        if (missing.isNotEmpty()) {
            dataStore.edit { prefs -> missing.forEach { (k, v) -> prefs[k] = v } }
        }
        return result
    }

    /** Ids of books hidden from the main list. Positions are kept, so unarchiving resumes. */
    suspend fun archived(): Set<String> = dataStore.data.first()[ARCHIVED_KEY].orEmpty()

    suspend fun setArchived(bookId: String, archived: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[ARCHIVED_KEY].orEmpty()
            prefs[ARCHIVED_KEY] = if (archived) current + bookId else current - bookId
        }
    }

    private fun decode(raw: String): SavedPosition? =
        runCatching { json.decodeFromString(SavedPosition.serializer(), raw) }.getOrNull()

    private val chapterListSerializer = ListSerializer(Chapter.serializer())

    private companion object {
        const val POS_PREFIX = "pos:"
        const val DUR_PREFIX = "dur:"
        const val CHAP_PREFIX = "chp:"
        val ARCHIVED_KEY = stringSetPreferencesKey("archived")
    }
}

