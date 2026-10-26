package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LrcSynchronizerTest {

    @Test
    fun testParseStandardLrc() {
        val lrc = """
            [ti:Test Track]
            [ar:Artist Name]
            [00:02.50]First line of lyrics
            [00:05.00]Second line of lyrics
            [00:08.20]Third line of lyrics
        """.trimIndent()

        val sync = LrcSynchronizer.parse(lrc)
        assertEquals(3, sync.lineCount)
        assertEquals("Test Track", sync.metadata.title)
        assertEquals("Artist Name", sync.metadata.artist)

        assertEquals(2500, sync.getLine(0).timeMs)
        assertEquals("First line of lyrics", sync.getLine(0).text)
        assertEquals(5000, sync.getLine(1).timeMs)
        assertEquals(8200, sync.getLine(2).timeMs)
    }

    @Test
    fun testMultiTimestampLines() {
        val lrc = """
            [00:01.00][00:04.00]Repeated chorus phrase
        """.trimIndent()

        val sync = LrcSynchronizer.parse(lrc)
        assertEquals(2, sync.lineCount)
        assertEquals(1000, sync.getLine(0).timeMs)
        assertEquals("Repeated chorus phrase", sync.getLine(0).text)
        assertEquals(4000, sync.getLine(1).timeMs)
        assertEquals("Repeated chorus phrase", sync.getLine(1).text)
    }

    @Test
    fun testActiveIndexBinarySearch() {
        val lrc = """
            [00:02.00]Line 1
            [00:06.00]Line 2
            [00:10.00]Line 3
        """.trimIndent()

        val sync = LrcSynchronizer.parse(lrc)

        // Before first lyric
        assertEquals(-1, sync.findActiveIndex(1000))
        assertNull(sync.getActiveLine(1000))

        // At exactly start of Line 1
        assertEquals(0, sync.findActiveIndex(2000))
        assertEquals("Line 1", sync.getActiveLine(2000).text)

        // Mid-way Line 1
        assertEquals(0, sync.findActiveIndex(4500))
        assertEquals("Line 1", sync.getActiveLine(4500).text)
        assertEquals("Line 2", sync.getNextLine(4500).text)

        // Line 2
        assertEquals(1, sync.findActiveIndex(6000))
        assertEquals(1, sync.findActiveIndex(8000))

        // Line 3 and beyond
        assertEquals(2, sync.findActiveIndex(10000))
        assertEquals(2, sync.findActiveIndex(25000))
        assertNull(sync.getNextLine(25000))
    }

    @Test
    fun testLineProgress() {
        val lrc = """
            [00:00.00]Start
            [00:10.00]End
        """.trimIndent()

        val sync = LrcSynchronizer.parse(lrc)
        assertEquals(0.0f, sync.getLineProgress(0), 0.01f)
        assertEquals(0.5f, sync.getLineProgress(5000), 0.01f)
        assertEquals(0.99f, sync.getLineProgress(9900), 0.01f)
        assertEquals(0.0f, sync.getLineProgress(10000), 0.01f)
    }

    @Test
    fun testOffsetHandling() {
        val lrc = """
            [offset:500]
            [00:01.00]Offset test line
        """.trimIndent()

        val sync = LrcSynchronizer.parse(lrc)
        assertEquals(1, sync.lineCount)
        assertEquals(1500, sync.getLine(0).timeMs)
    }
}
