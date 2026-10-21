package com.example.seeforyou.voice

import java.util.Locale
import java.text.Normalizer

sealed class VoiceCommand {
    data class Language(val tag: String) : VoiceCommand()
    object Read : VoiceCommand()
    object Describe : VoiceCommand()
    object Repeat : VoiceCommand()
    object Stop : VoiceCommand()
    object Settings : VoiceCommand()
    object Disable : VoiceCommand()
}

/** Deliberately bounded commands: unrelated speech must not trigger an action. */
object VoiceCommandParser {
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("\\p{Cf}"), "")
        .replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()
    fun parse(text: String): VoiceCommand? {
        var normalized = normalize(text)
        listOf("hey see for you ", "hey seeforyou ", "can you ", "could you ", "would you ",
            "please ", "कृपया ", "ದಯವಿಟ್ಟು ", "zara ", "jara ", "जरा ", "ज़रा ")
            .forEach { normalized = normalized.removePrefix(it) }
        listOf(" please", " na", " naa", " ना").forEach { normalized = normalized.removeSuffix(it) }
        return commands[normalized]
    }
    fun parseAlternatives(texts: List<String>): VoiceCommand? {
        // Do not execute a lower-ranked guess that contradicts the best transcript.
        val choices = texts.take(5).mapNotNull { parse(it) }.distinct()
        if (choices.size != 1) return null
        val best = normalize(texts.firstOrNull().orEmpty())
        if (Regex("\\b(don t|do not|not|never|or|mat|nahi|nahin|ya)\\b|नहीं|मत | या |ಬೇಡ|ಅಥವಾ").containsMatchIn(best)) return null
        return choices.single()
    }
    private val commands = buildMap<String, VoiceCommand> {
        fun add(command: VoiceCommand, vararg phrases: String) {
            phrases.forEach { put(normalize(it), command) }
        }
        // Compose bounded request forms, supporting code-switching and transliteration.
        // Full-utterance matching avoids treating mentions of languages as commands.
        mapOf(
            "en-IN" to listOf("english", "angrezi", "angreji", "अंग्रेजी", "अंग्रेज़ी", "इंग्लिश", "ಇಂಗ್ಲಿಷ್"),
            "hi-IN" to listOf("hindi", "हिंदी", "हिन्दी", "ಹಿಂದಿ"),
            "kn-IN" to listOf("kannada", "kannad", "कन्नड़", "कन्नडा", "ಕನ್ನಡ")
        ).forEach { (tag, names) -> names.forEach { name ->
            val intent = VoiceCommand.Language(tag)
            listOf("mein", "mai", "me", "में").forEach { inWord ->
                listOf("baat karo", "baat kro", "baat karna", "baat kijiye", "baat kariye", "baat karein",
                    "bolo", "boliye", "bolna", "baat kar", "बात करो", "बात करें", "बात कीजिए", "बोलो", "बोलिए").forEach { action ->
                    add(intent, "$name $inWord $action", "mujhse $name $inWord $action", "मुझसे $name $inWord $action")
                }
            }
            add(intent, "talk in $name", "talk to me in $name", "speak to me in $name",
                "use $name", "switch the language to $name", "$name mein switch karo",
                "$name mein badal do", "language $name kar do", "bhasha $name kar do")
        } }
        add(VoiceCommand.Read, "ye padho", "yeh padho", "ise padho", "isko padho", "ye padh ke sunao",
            "yeh padhkar sunao", "padh ke sunao", "text padh do", "text padh ke sunao", "read this for me",
            "read this to me", "read this aloud", "ये पढ़ो", "यह पढ़कर सुनाओ", "इसे पढ़कर सुनाओ",
            "ಇದನ್ನು ಓದಿ ಹೇಳಿ", "ಇದನ್ನು ಓದಿ ಕೊಡಿ")
        add(VoiceCommand.Describe, "mere samne kya hai", "mere saamne kya hai", "samne kya hai",
            "aas paas kya hai", "मेरे सामने क्या है", "मेरे आसपास क्या है", "tell me what is ahead")
        add(VoiceCommand.Repeat, "phir se bolo", "fir se bolo", "dobara bolo", "ek baar aur bolo", "say that again")
        add(VoiceCommand.Stop, "bolna band karo", "padhna band karo", "bas karo", "be quiet")
        add(VoiceCommand.Settings, "settings kholo", "setting kholo", "open the settings")
        add(VoiceCommand.Read, "read", "read text", "read the text", "read this", "read aloud",
            "read this text", "read out the text", "read the text aloud", "read it", "read it out", "read the text for me",
            "रीड द टेक्स्ट", "रीड टेक्स्ट", "ರೀಡ್ ದ ಟೆಕ್ಸ್ಟ್", "यह टेक्स्ट पढ़ो", "पढ़कर सुनाओ",
            "पढ़ो", "पाठ पढ़ो", "पाठ पढ़ें", "इसे पढ़ो", "यह पढ़ो", "टेक्स्ट पढ़ो", "टेक्स्ट पढ़ें",
            "ಓದು", "ಓದಿ", "ಪಠ್ಯ ಓದಿ", "ಇದನ್ನು ಓದಿ", "ಪಠ್ಯವನ್ನು ಓದಿ", "ಟೆಕ್ಸ್ಟ್ ಓದಿ",
            "padho", "text padho", "path padho", "odi", "idannu odi")
        add(VoiceCommand.Describe, "describe", "describe surroundings", "what is ahead", "what is in front of me",
            "describe surrounding", "describe the surrounding", "describe the surroundings",
            "describe my surrounding", "describe my surroundings", "describe the scene", "describe this scene",
            "what is around me", "what s around me", "what s in front of me", "tell me what is around me",
            "सामने क्या है", "आसपास क्या है", "ಮುಂದೆ ಏನಿದೆ", "ಸುತ್ತಮುತ್ತ ಏನಿದೆ")
        add(VoiceCommand.Repeat, "repeat", "repeat that", "say again", "दोहराओ", "दोहराएँ", "फिर से बोलो",
            "ಮತ್ತೆ ಹೇಳಿ", "ಮತ್ತೊಮ್ಮೆ ಹೇಳಿ")
        add(VoiceCommand.Stop, "stop speaking", "stop reading", "चुप हो जाओ", "बोलना बंद करो",
            "पढ़ना बंद करो", "ಮಾತು ನಿಲ್ಲಿಸಿ", "ಓದುವುದನ್ನು ನಿಲ್ಲಿಸಿ")
        add(VoiceCommand.Settings, "settings", "open settings", "सेटिंग्स खोलो", "सेटिंग्स खोलें", "ಸೆಟ್ಟಿಂಗ್ಸ್ ತೆರೆಯಿರಿ", "ಸೆಟ್ಟಿಂಗ್ ತೆರೆಯಿರಿ")
        add(VoiceCommand.Disable, "turn off hands free", "turn off handsfree", "stop listening",
            "सुनना बंद करो", "हैंड्स फ्री बंद करो", "ಕೇಳುವುದನ್ನು ನಿಲ್ಲಿಸಿ")
        add(VoiceCommand.Language("hi-IN"), "hindi", "switch to hindi", "change language to hindi",
            "chil the language to hindi", // Exact user-reported recognition error; no fuzzy verb matching.
            "change the language to hindi", "हिंदी", "हिन्दी", "हिंदी में बदलो", "हिन्दी में बदलो",
            "भाषा हिंदी में बदलो", "हिंदी में बोलो", "ಹಿಂದಿ", "ಹಿಂದಿಗೆ ಬದಲಿಸಿ", "ಭಾಷೆಯನ್ನು ಹಿಂದಿಗೆ ಬದಲಿಸಿ")
        add(VoiceCommand.Language("kn-IN"), "kannada", "switch to kannada", "change language to kannada",
            "change the language to kannada", "कन्नड़", "कन्नड़ में बदलो", "कन्नड़ में बोलो",
            "भाषा कन्नड़ में बदलो", "ಕನ್ನಡ", "ಕನ್ನಡಕ್ಕೆ ಬದಲಿಸಿ", "ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡಿ", "ಭಾಷೆಯನ್ನು ಕನ್ನಡಕ್ಕೆ ಬದಲಿಸಿ")
        add(VoiceCommand.Language("en-IN"), "english", "switch to english", "change language to english",
            "change the language to english", "अंग्रेजी", "अंग्रेजी में बदलो", "अंग्रेज़ी में बदलो",
            "ಇಂಗ್ಲಿಷ್", "ಇಂಗ್ಲಿಷ್ ಗೆ ಬದಲಿಸಿ", "ಇಂಗ್ಲಿಷ್‌ಗೆ ಬದಲಿಸಿ")
        listOf("hindi" to "hi-IN", "kannada" to "kn-IN", "english" to "en-IN").forEach { (name, tag) ->
            add(VoiceCommand.Language(tag), "change to $name", "change language $name",
                "change the language $name", "set language to $name", "set the language to $name",
                "change the app language to $name", "change my language to $name", "speak in $name")
        }
        add(VoiceCommand.Language("hi-IN"), "हिंदी में बदलें", "भाषा हिंदी में बदलें", "हिंदी भाषा में बदलो")
        add(VoiceCommand.Language("hi-IN"), "हिंदी में बात करो", "हिन्दी में बात करो", "हिंदी में बात करें",
            "हिंदी में बात करिए", "हिंदी में बात कर", "hindi mein baat karo", "hindi mai baat kro",
            "hindi me baat karo", "हिंदी में बात करो ना", "चेंज द लैंग्वेज टू हिंदी",
            "चेंज द लैंग्वेज टू हिन्दी", "ಚೇಂಜ್ ದ ಲ್ಯಾಂಗ್ವೇಜ್ ಟು ಹಿಂದಿ")
        add(VoiceCommand.Language("kn-IN"), "कन्नड़ में बात करो", "ಕನ್ನಡದಲ್ಲಿ ಮಾತಾಡಿ", "ಕನ್ನಡದಲ್ಲಿ ಮಾತಾಡು",
            "kannada mein baat karo", "kannadadalli matanadi")
        add(VoiceCommand.Language("kn-IN"), "कन्नड़ में बदलें", "भाषा कन्नड़ में बदलें", "ಕನ್ನಡ ಭಾಷೆಗೆ ಬದಲಿಸಿ")
    }
}
