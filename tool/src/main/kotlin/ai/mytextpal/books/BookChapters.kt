package ai.mytextpal.books

/**
 * A book's table of contents, flattened across its files.
 *
 * Every file contributes either its embedded chapters or, when it has none, one chapter
 * for the whole file. So a folder of "01 - Chapter One.mp3" parts and a single M4B with
 * markers both end up as the same kind of list, and the player treats them alike.
 */
data class BookChapter(
    /** Position in the flattened list. */
    val index: Int,
    val fileIndex: Int,
    val title: String,
    /** Offset within the file, not within the book. */
    val startMs: Long,
    /** Within the file; 0 when the file's length is unknown. */
    val endMs: Long,
) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

object BookChapters {
    /**
     * @param embedded per file, the chapters read from it (empty when none)
     * @param fileDurationsMs per file, or 0 when unknown
     */
    fun assemble(book: Book, embedded: List<List<Chapter>>, fileDurationsMs: List<Long>): List<BookChapter> {
        val out = mutableListOf<BookChapter>()
        book.files.forEachIndexed { fi, file ->
            val duration = fileDurationsMs.getOrNull(fi) ?: 0L
            val inFile = embedded.getOrNull(fi).orEmpty()
            if (inFile.isEmpty()) {
                val title = if (book.files.size > 1) file.nameWithoutExtension else book.title
                out += BookChapter(out.size, fi, title, 0L, duration)
            } else {
                inFile.forEachIndexed { ci, c ->
                    val isLast = ci == inFile.lastIndex
                    val end = if (isLast && duration > c.startMs) duration else c.endMs
                    out += BookChapter(out.size, fi, c.title, c.startMs, end)
                }
            }
        }
        return out
    }

    /** The chapter playing at [posMs] in file [fileIndex]; the file's first chapter as a fallback. */
    fun at(chapters: List<BookChapter>, fileIndex: Int, posMs: Long): BookChapter? {
        val inFile = chapters.filter { it.fileIndex == fileIndex }
        if (inFile.isEmpty()) return null
        return inFile.lastOrNull { it.startMs <= posMs } ?: inFile.first()
    }

    /** True when the list is more than one whole-file entry, i.e. worth showing as chapters. */
    fun isNavigable(chapters: List<BookChapter>): Boolean = chapters.size > 1
}
