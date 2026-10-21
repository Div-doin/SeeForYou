package com.example.seeforyou.localization

import com.example.seeforyou.utils.QuietScenePolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PhraseBookTest {
    @Test fun translationsKeepAllFormattingArguments() {
        val placeholder = Regex("%\\d+\\$[sd]")
        PhraseBook.phrases.forEach { (key, translations) ->
            assertEquals(key, 3, translations.size)
            translations.forEach {
                assertTrue(key, it.isNotBlank())
                assertEquals(key, placeholder.findAll(translations.first()).map { m -> m.value }.sorted().toList(),
                    placeholder.findAll(it).map { m -> m.value }.sorted().toList())
            }
        }
    }

    @Test fun screenAndSpeechTranslationsStayAligned() {
        listOf("values", "values-hi", "values-kn").forEachIndexed { column, folder ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$folder/language.xml"))
            val nodes = document.getElementsByTagName("string")
            val strings = (0 until nodes.length).associate {
                val node = nodes.item(it)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
                    .replace("\\n", "\n").replace("\\'", "'")
            }
            assertEquals(PhraseBook.phrases.keys, strings.keys)
            PhraseBook.phrases.forEach { (key, translations) ->
                assertEquals("$folder/$key", translations[column], strings[key])
            }
        }
    }

    @Test fun allDetectorLabelsHaveNativeNames() {
        assertEquals(80, PhraseBook.objects.size)
        PhraseBook.objects.forEach { (label, translations) ->
            assertEquals(label, 3, translations.size)
            assertTrue(label, translations[1].any { it in '\u0900'..'\u097F' })
            assertTrue(label, translations[2].any { it in '\u0C80'..'\u0CFF' })
        }
    }

    @Test fun descriptionsAndAlertsUseSelectedLanguageWithoutChangingQuietPolicy() {
        listOf("hi-IN", "kn-IN").forEach { tag ->
            val book = PhraseBook(tag)
            val policy = QuietScenePolicy(book)
            repeat(3) { frame ->
                policy.observe(listOf(QuietScenePolicy.ObjectInView("chair", .9f, .5f, .6f)), frame * 100L)
            }
            assertTrue(policy.description(200).contains(book.objectName("chair")))
            assertFalse(policy.description(200).contains("chair"))
            assertTrue(policy.navigationAlert(200)!!.contains(book.objectName("chair")))
            assertNull(policy.navigationAlert(300))
        }
    }

    @Test fun directionsTranslateActionsAndPreservePlaceNames() {
        listOf("hi-IN", "kn-IN").forEach { tag ->
            val book = PhraseBook(tag)
            for (type in 0..13) {
                val result = book.maneuver(type, "MG Road", "English instruction")
                assertFalse(result.contains("English instruction"))
                assertTrue(result.contains("MG Road"))
            }
            assertTrue(book.maneuver(11, "", "Head northeast").contains(book.text("heading_northeast")))
            assertTrue(book.maneuver(7, "", "", 3).contains("3"))
            assertEquals(book.text("direction_unknown"), book.maneuver(99, "", "unknown"))
        }
        assertEquals("Turn left", PhraseBook("en-US").maneuver(0, "", "Turn left"))
    }
}
