package ai.mytextpal.books

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChaptersTest {

    // ---- ID3 ----

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf(((v ushr 21) and 0x7F).toByte(), ((v ushr 14) and 0x7F).toByte(), ((v ushr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun frame(id: String, body: ByteArray, v4: Boolean): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + (if (v4) syncsafe(body.size) else be32(body.size)) + byteArrayOf(0, 0) + body

    private fun chap(elementId: String, start: Int, end: Int, title: String?, v4: Boolean, utf16: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(elementId.toByteArray(Charsets.ISO_8859_1)); out.write(0)
        out.write(be32(start)); out.write(be32(end)); out.write(be32(-1)); out.write(be32(-1))
        if (title != null) {
            val text = if (utf16) byteArrayOf(1) + byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + title.toByteArray(Charsets.UTF_16BE)
            else byteArrayOf(3) + title.toByteArray(Charsets.UTF_8)
            out.write(frame("TIT2", text, v4))
        }
        return frame("CHAP", out.toByteArray(), v4)
    }

    private fun id3(version: Int, frames: List<ByteArray>): Pair<ByteArray, ByteArray> {
        val tag = frames.fold(ByteArray(0)) { acc, f -> acc + f } + ByteArray(20) // padding
        val header = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncsafe(tag.size)
        return header to tag
    }

    @Test
    fun id3v23LibationStyleUsesElementIdAsTitle() {
        val (h, t) = id3(3, listOf(
            frame("TIT2", byteArrayOf(3) + "Book".toByteArray(), false),
            chap("Opening Credits", 0, 89179, null, false),
            chap("Disclaimer", 89179, 121354, null, false),
        ))
        val chapters = Id3Chapters.parse(h, t)
        assertEquals(listOf("Opening Credits", "Disclaimer"), chapters.map { it.title })
        assertEquals(listOf(0L, 89179L), chapters.map { it.startMs })
        assertEquals(listOf(89179L, 121354L), chapters.map { it.endMs })
    }

    @Test
    fun id3v23SubframeTitleWinsAndUtf16Decodes() {
        val (h, t) = id3(3, listOf(
            chap("ch1", 0, 1000, "One. The Basics", false, utf16 = true),
            chap("ch0", 1000, 2000, "Two", false),
        ))
        val chapters = Id3Chapters.parse(h, t)
        assertEquals(listOf("One. The Basics", "Two"), chapters.map { it.title })
    }

    @Test
    fun id3v24SyncsafeSizesAndOutOfOrderFramesAreSorted() {
        val (h, t) = id3(4, listOf(
            chap("b", 5000, 9000, "Second", true),
            chap("a", 0, 5000, "First", true),
        ))
        val chapters = Id3Chapters.parse(h, t)
        assertEquals(listOf("First", "Second"), chapters.map { it.title })
    }

    @Test
    fun overlappingEndsAreClippedToNextStart() {
        val tidy = ChapterReader.tidy(listOf(Chapter("a", 0, 99_999), Chapter("b", 5_000, 8_000)))
        assertEquals(5_000L, tidy[0].endMs)
    }

    // ---- MP4 ----

    private fun box(type: String, payload: ByteArray): ByteArray = be32(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun full(type: String, version: Int, payload: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    @Test
    fun neroChplChaptersParse() {
        val entries = ByteArrayOutputStream()
        fun entry(startMs: Long, title: String) {
            val hundredNs = startMs * 10_000L
            entries.write(be32((hundredNs ushr 32).toInt())); entries.write(be32(hundredNs.toInt()))
            val b = title.toByteArray(Charsets.UTF_8); entries.write(b.size); entries.write(b)
        }
        entry(0, "Intro"); entry(60_000, "Chapter 1"); entry(180_000, "Chapter 2")
        val chpl = full("chpl", 1, ByteArray(4) + byteArrayOf(3) + entries.toByteArray())
        val mvhd = full("mvhd", 0, ByteArray(8) + be32(1000) + be32(300_000)) // timescale 1000, 300 s
        val moov = Box("moov", mvhd + box("udta", chpl), 8)
        val chapters = Mp4Chapters.parse(moov) { _, _ -> ByteArray(0) }
        assertEquals(listOf("Intro", "Chapter 1", "Chapter 2"), chapters.map { it.title })
        assertEquals(listOf(0L, 60_000L, 180_000L), chapters.map { it.startMs })
        assertEquals(listOf(60_000L, 180_000L, 300_000L), chapters.map { it.endMs })
    }

    @Test
    fun quickTimeChapterTrackParses() {
        // Two text samples in a fake mdat; timescale 1000; sample times 0 and 30 s; track lasts 90 s.
        val s1 = "Prologue".toByteArray(); val s2 = "Chapter One".toByteArray()
        val sample1 = byteArrayOf((s1.size ushr 8).toByte(), s1.size.toByte()) + s1
        val sample2 = byteArrayOf((s2.size ushr 8).toByte(), s2.size.toByte()) + s2
        val mdat = sample1 + sample2
        val mdatOffset = 5000L
        val mdhd = full("mdhd", 0, be32(0) + be32(0) + be32(1000) + be32(90_000) + be32(0))
        val hdlr = full("hdlr", 0, be32(0) + "text".toByteArray() + ByteArray(12))
        val stts = full("stts", 0, be32(2) + be32(1) + be32(30_000) + be32(1) + be32(60_000))
        val stsz = full("stsz", 0, be32(0) + be32(2) + be32(sample1.size) + be32(sample2.size))
        val stsc = full("stsc", 0, be32(1) + be32(1) + be32(2) + be32(1))
        val stco = full("stco", 0, be32(1) + be32(mdatOffset.toInt()))
        val stbl = box("stbl", stts + stsz + stsc + stco)
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        val tkhd = full("tkhd", 0, be32(0) + be32(0) + be32(2) + ByteArray(60))
        val trak = box("trak", tkhd + mdia)
        val moov = Box("moov", trak, 8)
        val chapters = Mp4Chapters.parse(moov) { offset, size -> mdat.copyOfRange((offset - mdatOffset).toInt(), (offset - mdatOffset).toInt() + size) }
        assertEquals(listOf("Prologue", "Chapter One"), chapters.map { it.title })
        assertEquals(listOf(0L, 30_000L), chapters.map { it.startMs })
        assertEquals(listOf(30_000L, 90_000L), chapters.map { it.endMs })
    }

    @Test
    fun unknownFormatsReadAsNoChapters() {
        val f = File.createTempFile("chapters", ".ogg").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        assertTrue(ChapterReader.read(f).isEmpty())
    }

    /** Runs only on a machine that has a Libation export; a real-world check of the ID3 path. */
    @Test
    fun realLibationExportIfPresent() {
        val dir = File(System.getProperty("user.home"), "Music/Libation/Books")
        val file = dir.listFiles()?.firstOrNull { it.name.startsWith("Thinking in Systems") }
            ?.listFiles()?.firstOrNull { it.extension == "mp3" } ?: return
        val chapters = ChapterReader.read(file)
        assertEquals(15, chapters.size)
        assertEquals("Opening Credits", chapters.first().title)
        assertTrue(chapters.any { it.title == "One. The Basics" })
        assertTrue(chapters.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
    }

    // ---- assembly ----

    private fun book(vararg names: String) = Book("b", "Book", names.map { File("/tmp/$it") })

    @Test
    fun folderBookWithoutMarkersIsOneChapterPerFile() {
        val chapters = BookChapters.assemble(book("01 - One.mp3", "02 - Two.mp3"), listOf(emptyList(), emptyList()), listOf(100L, 200L))
        assertEquals(listOf("01 - One", "02 - Two"), chapters.map { it.title })
        assertEquals(listOf(0, 1), chapters.map { it.fileIndex })
        assertEquals(listOf(100L, 200L), chapters.map { it.endMs })
        assertTrue(BookChapters.isNavigable(chapters))
    }

    @Test
    fun singleFileWithMarkersFlattensAndClosesLastChapterAtFileEnd() {
        val embedded = listOf(listOf(Chapter("Intro", 0, 1000), Chapter("One", 1000, 0)))
        val chapters = BookChapters.assemble(book("book.mp3"), embedded, listOf(5000L))
        assertEquals(5000L, chapters[1].endMs)
        assertEquals("One", BookChapters.at(chapters, 0, 4000L)?.title)
        assertEquals("Intro", BookChapters.at(chapters, 0, 0L)?.title)
    }

    @Test
    fun singleFileWithoutMarkersIsNotNavigable() {
        val chapters = BookChapters.assemble(book("book.mp3"), listOf(emptyList()), listOf(5000L))
        assertEquals(1, chapters.size)
        assertEquals("Book", chapters[0].title)
        assertTrue(!BookChapters.isNavigable(chapters))
    }
}
