package com.example.seeforyou.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun acceptsEverydaySurroundingsRequestsWithoutMatchingUnrelatedSpeech() {
        listOf("describe surrounding", "describe surroundings", "describe the surrounding",
            "describe my surroundings", "Could you describe the scene please?", "What's around me?",
            "what's in front of me", "tell me what is around me")
            .forEach { assertEquals(it, VoiceCommand.Describe, VoiceCommandParser.parse(it)) }
        assertNull(VoiceCommandParser.parse("don't describe my surroundings"))
        assertNull(VoiceCommandParser.parse("I described my surroundings yesterday"))
        assertNull(VoiceCommandParser.parseAlternatives(listOf("describe surrounding", "read the text")))
    }
    @Test fun understandsEverydayMixedLanguageRequests() {
        listOf("english mai baat kro", "English mein baat karo", "angrezi mein bolo", "please talk to me in english",
            "जरा अंग्रेज़ी में बोलिए", "इंग्लिश में बात करो", "english में बात करो", "mujhse english mein baat kariye na")
            .forEach { assertEquals(it, VoiceCommand.Language("en-IN"), VoiceCommandParser.parse(it)) }
        assertEquals(VoiceCommand.Language("hi-IN"), VoiceCommandParser.parse("hindi mai boliye"))
        assertEquals(VoiceCommand.Language("kn-IN"), VoiceCommandParser.parse("kannada me baat kro"))
        listOf("ye padh ke sunao", "read this for me", "इसे पढ़कर सुनाओ", "ಇದನ್ನು ಓದಿ ಕೊಡಿ")
            .forEach { assertEquals(it, VoiceCommand.Read, VoiceCommandParser.parse(it)) }
        listOf("don't talk to me in english", "english mai baat mat kro", "english ya hindi mein bolo",
            "I asked him to talk in english", "what does english mein baat karo mean")
            .forEach { assertNull(it, VoiceCommandParser.parse(it)) }
    }
    @Test fun reportedTranscriptionAliasDoesNotAcceptNegatedOrUnrelatedSpeech() {
        assertEquals(VoiceCommand.Language("hi-IN"), VoiceCommandParser.parse("chil the language to hindi"))
        assertNull(VoiceCommandParser.parse("chill in hindi"))
        assertNull(VoiceCommandParser.parseAlternatives(listOf("do not chil the language to hindi", "chil the language to hindi")))
        assertNull(VoiceCommandParser.parseAlternatives(listOf("chil the language to hindi", "switch to kannada")))
    }
    @Test fun acceptsReportedHindiAndNaturalEnglishCommands() {
        listOf("हिंदी में बात करो", "हिन्दी में बात करो", "hindi mai baat kro", "Hindi mein baat karo",
            "change the language to Hindi", "Can you please change the language to Hindi?",
            "Hey SeeForYou change the language to Hindi", "चेंज द लैंग्वेज टू हिंदी")
            .forEach { assertEquals(it, VoiceCommand.Language("hi-IN"), VoiceCommandParser.parse(it)) }
        assertEquals(VoiceCommand.Read, VoiceCommandParser.parse("Could you read this text please?"))
    }
    @Test fun alternativesAllowSpellingErrorsButRejectConflictsAndNegation() {
        assertEquals(VoiceCommand.Read, VoiceCommandParser.parseAlternatives(listOf("reed the text", "read the text")))
        assertNull(VoiceCommandParser.parseAlternatives(listOf("hindi", "kannada")))
        assertNull(VoiceCommandParser.parseAlternatives(listOf("do not read the text", "read the text")))
        assertNull(VoiceCommandParser.parseAlternatives(emptyList()))
    }
    @Test fun readsInAllThreeLanguages() {
        listOf("Read the text!", "please read this", "पाठ पढ़ो", "इसे पढ़ो", "ಪಠ್ಯ ಓದಿ", "ಇದನ್ನು ಓದಿ")
            .forEach { assertEquals(it, VoiceCommand.Read, VoiceCommandParser.parse(it)) }
    }
    @Test fun changesLanguageAcrossSourceLanguages() {
        listOf("change the language to kannada", "कन्नड़ में बदलो", "ಕನ್ನಡಕ್ಕೆ ಬದಲಿಸಿ")
            .forEach { assertEquals(VoiceCommand.Language("kn-IN"), VoiceCommandParser.parse(it)) }
        listOf("switch to hindi", "हिन्दी में बदलो", "ಹಿಂದಿಗೆ ಬದಲಿಸಿ")
            .forEach { assertEquals(VoiceCommand.Language("hi-IN"), VoiceCommandParser.parse(it)) }
        listOf("english", "अंग्रेजी में बदलो", "ಇಂಗ್ಲಿಷ್")
            .forEach { assertEquals(VoiceCommand.Language("en-IN"), VoiceCommandParser.parse(it)) }
    }
    @Test fun ignoresUnrelatedOrAmbiguousSpeech() {
        listOf("", "I read the text yesterday", "don't change the language to hindi",
            "change to hindi or kannada", "what is the hindi word for chair", "call emergency", "send sos")
            .forEach { assertNull(it, VoiceCommandParser.parse(it)) }
    }
    @Test fun commandsDoNotImplicitlyChangeLanguage() {
        assertEquals(VoiceCommand.Describe, VoiceCommandParser.parse("ಮುಂದೆ ಏನಿದೆ"))
        assertEquals(VoiceCommand.Repeat, VoiceCommandParser.parse("फिर से बोलो"))
        assertEquals(VoiceCommand.Disable, VoiceCommandParser.parse("stop listening"))
    }
}
