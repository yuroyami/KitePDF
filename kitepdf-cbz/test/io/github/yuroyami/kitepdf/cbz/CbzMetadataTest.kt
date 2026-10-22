package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CbzMetadataTest {
    private fun book(xml: ByteArray, name: String = "ComicInfo.xml"): CbzDocument = CbzDocument.open(
        CbzFixtures.comic(
            "page10.bmp" to CbzFixtures.bmp2x1(),
            "page2.bmp" to CbzFixtures.bmp2x1(),
            "page1.bmp" to CbzFixtures.bmp2x1(),
            name to xml,
        ),
    )

    @Test
    fun exposes_comic_fields_and_shared_reader_metadata() {
        val doc = book("""
            <ComicInfo>
              <Title> A &amp; B </Title><Series>Example</Series><Number>1a</Number>
              <Writer>Alice, Bob</Writer><LanguageISO>ja</LanguageISO>
              <Manga>YesAndRightToLeft</Manga><Summary><![CDATA[Part <one>]]></Summary>
              <Publisher>Publisher</Publisher><StoryArcNumber>2</StoryArcNumber>
            </ComicInfo>
        """.encodeToByteArray())
        val info = requireNotNull(doc.comicMetadata)
        assertEquals("A & B", doc.metadata.title)
        assertEquals(listOf("Alice", "Bob"), doc.metadata.authors)
        assertEquals("ja", doc.metadata.language)
        assertTrue(doc.metadata.rightToLeft)
        assertEquals("Example", info.series)
        assertEquals("1a", info.number)
        assertEquals("Part <one>", info.summary)
        assertEquals("2", info.fields["storyarcnumber"])
        assertEquals(3, doc.pageCount)
    }

    @Test
    fun page_bookmarks_follow_natural_image_order_and_skip_invalid_indices() {
        val doc = book("""
            <ComicInfo><Pages>
              <Page Image="2" Bookmark="Finale" DoublePage="true"/>
              <Page Image="0" Bookmark="Start" Type="FrontCover"/>
              <Page Image="1"/>
              <Page Image="-1" Bookmark="Invalid"/>
              <Page Image="3" Bookmark="Outside"/>
              <Page Image="bad" Bookmark="Malformed"/>
            </Pages></ComicInfo>
        """.encodeToByteArray())
        assertEquals(listOf("Start", "Finale"), doc.outline.map { it.title })
        assertEquals(listOf(0, 2), doc.outline.map { it.pageIndex })
        assertEquals(KiteBookmark.Page(2), doc.outline.last().target)
        assertEquals("page10.bmp", doc.entryNames[doc.outline.last().pageIndex!!])
        assertEquals(3, doc.comicMetadata!!.pages.size)
        assertTrue(doc.comicMetadata!!.pages.first().doublePage)
    }

    @Test
    fun absent_unrelated_and_damaged_metadata_never_prevent_opening() {
        for (xml in listOf("", "<unrelated><Title>Wrong</Title></unrelated>", "<")) {
            val doc = book(xml.encodeToByteArray())
            assertEquals(3, doc.pageCount)
            assertNull(doc.comicMetadata)
            assertNull(doc.metadata.title)
            assertTrue(doc.outline.isEmpty())
        }
        val nested = book("<ComicInfo><Title>Wrong</Title></ComicInfo>".encodeToByteArray(), "extra/ComicInfo.xml")
        assertNull(nested.comicMetadata)
        val salvaged = book("<ComicInfo><Title>Still readable</Title><Pages><Page Image='bad'/></Pages>".encodeToByteArray())
        assertEquals("Still readable", salvaged.metadata.title)
    }

    @Test
    fun case_insensitive_root_filename_and_utf16_bom_are_supported() {
        val xml = "<ComicInfo><Title>日本語</Title></ComicInfo>"
        for (little in listOf(true, false)) {
            val bytes = ByteArray(2 + xml.length * 2)
            bytes[0] = (if (little) 0xff else 0xfe).toByte()
            bytes[1] = (if (little) 0xfe else 0xff).toByte()
            xml.forEachIndexed { i, c ->
                bytes[2 + i * 2] = (if (little) c.code else c.code ushr 8).toByte()
                bytes[3 + i * 2] = (if (little) c.code ushr 8 else c.code).toByte()
            }
            assertEquals("日本語", book(bytes, "comicinfo.XML").metadata.title)
        }
    }
}
