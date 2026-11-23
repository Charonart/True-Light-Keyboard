package com.truelight.ime.lang

import com.truelight.ime.engine.HangulEngine
import com.truelight.ime.engine.InputEngine
import com.truelight.ime.engine.SimpleWordEngine
import com.truelight.ime.engine.TelexFsmEngine
import com.truelight.ime.engine.VniFsmEngine

enum class EngineType {
    SIMPLE,
    VI_TELEX,
    VI_VNI,
    KO_HANGUL
}

/**
 * Immutable language & layout specification for Group 1 languages.
 * Designed so Group 2 (Pinyin / Kana-Kanji) engines can be added cleanly later.
 */
class LanguageSpec(
    val id: String,
    val dictKey: String,
    val shortCode: String,
    val displayName: String,
    val nativeName: String,
    val spaceLabel: String,
    val engineType: EngineType,
    val rows: Array<Array<String>>,
    val shiftedRows: Array<Array<String>>? = null,
    val subLabels: Array<Array<String>>? = null,
    val accentPopupMap: Map<Char, Array<String>> = emptyMap()
) {
    fun isVietnamese(): Boolean = engineType == EngineType.VI_TELEX || engineType == EngineType.VI_VNI

    fun createEngine(vietnameseModernTone: Boolean, viAutoRestore: Boolean = true): InputEngine {
        return when (engineType) {
            EngineType.SIMPLE -> SimpleWordEngine()
            EngineType.VI_TELEX -> TelexFsmEngine().apply {
                modernTonePlacement = vietnameseModernTone
                autoRestoreEnabled = viAutoRestore
            }
            EngineType.VI_VNI -> VniFsmEngine().apply {
                modernTonePlacement = vietnameseModernTone
                autoRestoreEnabled = viAutoRestore
            }
            EngineType.KO_HANGUL -> HangulEngine()
        }
    }
}

object LanguageRegistry {

    private val STD_SUB_LABELS = arrayOf(
        arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        arrayOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
        arrayOf("*", "\"", "'", ":", ";", "!", "?")
    )

    private val EN_ACCENTS: Map<Char, Array<String>> = mapOf(
        'a' to arrayOf("à", "á", "â", "ä", "æ", "ã", "å"),
        'e' to arrayOf("é", "è", "ê", "ë"),
        'i' to arrayOf("í", "ì", "î", "ï"),
        'o' to arrayOf("ó", "ò", "ô", "ö", "œ", "õ", "ø"),
        'u' to arrayOf("ú", "ù", "û", "ü"),
        'c' to arrayOf("ç"),
        'n' to arrayOf("ñ"),
        's' to arrayOf("ß")
    )

    private val FR_ACCENTS: Map<Char, Array<String>> = mapOf(
        'a' to arrayOf("à", "â", "æ", "á", "ä"),
        'e' to arrayOf("é", "è", "ê", "ë"),
        'i' to arrayOf("î", "ï", "í", "ì"),
        'o' to arrayOf("ô", "œ", "ó", "ò", "ö"),
        'u' to arrayOf("ù", "û", "ü", "ú"),
        'c' to arrayOf("ç"),
        'y' to arrayOf("ÿ")
    )

    private val DE_ACCENTS: Map<Char, Array<String>> = mapOf(
        'a' to arrayOf("ä", "á", "à", "â"),
        'e' to arrayOf("é", "è", "ê", "ë"),
        'o' to arrayOf("ö", "ó", "ò", "ô", "œ"),
        'u' to arrayOf("ü", "ú", "ù", "û"),
        's' to arrayOf("ß", "ś", "š")
    )

    private val ES_ACCENTS: Map<Char, Array<String>> = mapOf(
        'a' to arrayOf("á", "à", "â", "ä"),
        'e' to arrayOf("é", "è", "ê", "ë"),
        'i' to arrayOf("í", "ì", "î", "ï"),
        'o' to arrayOf("ó", "ò", "ô", "ö"),
        'u' to arrayOf("ú", "ü", "ù", "û"),
        'n' to arrayOf("ñ"),
        '?' to arrayOf("¿"),
        '!' to arrayOf("¡")
    )

    private val RU_ACCENTS: Map<Char, Array<String>> = mapOf(
        'е' to arrayOf("ё"),
        'ь' to arrayOf("ъ")
    )

    val EN_US = LanguageSpec(
        id = "en_us",
        dictKey = "en",
        shortCode = "EN",
        displayName = "English (US)",
        nativeName = "English (US)",
        spaceLabel = "Space",
        engineType = EngineType.SIMPLE,
        rows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        ),
        subLabels = STD_SUB_LABELS,
        accentPopupMap = EN_ACCENTS
    )

    val VI_TELEX = LanguageSpec(
        id = "vi_telex",
        dictKey = "vi",
        shortCode = "VI",
        displayName = "Vietnamese (Telex)",
        nativeName = "Tiếng Việt (Telex)",
        spaceLabel = "Dấu cách",
        engineType = EngineType.VI_TELEX,
        rows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        ),
        subLabels = STD_SUB_LABELS
    )

    val VI_VNI = LanguageSpec(
        id = "vi_vni",
        dictKey = "vi",
        shortCode = "VNI",
        displayName = "Vietnamese (VNI)",
        nativeName = "Tiếng Việt (VNI)",
        spaceLabel = "Dấu cách",
        engineType = EngineType.VI_VNI,
        rows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        ),
        subLabels = STD_SUB_LABELS
    )

    val FR_AZERTY = LanguageSpec(
        id = "fr_azerty",
        dictKey = "fr",
        shortCode = "FR",
        displayName = "French (AZERTY)",
        nativeName = "Français (AZERTY)",
        spaceLabel = "Espace",
        engineType = EngineType.SIMPLE,
        rows = arrayOf(
            arrayOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
            arrayOf("w", "x", "c", "v", "b", "n", "é")
        ),
        subLabels = arrayOf(
            arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            arrayOf("@", "#", "€", "%", "&", "-", "+", "(", ")", "'"),
            arrayOf("è", "ê", "ç", "à", "ù", "!", "?")
        ),
        accentPopupMap = FR_ACCENTS
    )

    val DE_QWERTZ = LanguageSpec(
        id = "de_qwertz",
        dictKey = "de",
        shortCode = "DE",
        displayName = "German (QWERTZ)",
        nativeName = "Deutsch (QWERTZ)",
        spaceLabel = "Leertaste",
        engineType = EngineType.SIMPLE,
        rows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            arrayOf("y", "x", "c", "v", "b", "n", "m")
        ),
        subLabels = arrayOf(
            arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            arrayOf("ä", "ß", "$", "%", "&", "-", "+", "ö", "ü"),
            arrayOf("*", "\"", "'", ":", ";", "!", "?")
        ),
        accentPopupMap = DE_ACCENTS
    )

    val ES_QWERTY = LanguageSpec(
        id = "es_qwerty",
        dictKey = "es",
        shortCode = "ES",
        displayName = "Spanish",
        nativeName = "Español",
        spaceLabel = "Espacio",
        engineType = EngineType.SIMPLE,
        rows = arrayOf(
            arrayOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            arrayOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"),
            arrayOf("z", "x", "c", "v", "b", "n", "m")
        ),
        subLabels = arrayOf(
            arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            arrayOf("á", "é", "í", "ó", "ú", "-", "+", "(", ")", "¿"),
            arrayOf("*", "\"", "'", ":", ";", "¡", "?")
        ),
        accentPopupMap = ES_ACCENTS
    )

    val RU_CYRILLIC = LanguageSpec(
        id = "ru_cyrillic",
        dictKey = "ru",
        shortCode = "RU",
        displayName = "Russian (Cyrillic)",
        nativeName = "Русский",
        spaceLabel = "Пробел",
        engineType = EngineType.SIMPLE,
        rows = arrayOf(
            arrayOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
            arrayOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"),
            arrayOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
        ),
        subLabels = null,
        accentPopupMap = RU_ACCENTS
    )

    val KO_HANGUL = LanguageSpec(
        id = "ko_hangul",
        dictKey = "ko",
        shortCode = "KO",
        displayName = "Korean (2-Bulsik)",
        nativeName = "한국어 (두벌식)",
        spaceLabel = "스페이스",
        engineType = EngineType.KO_HANGUL,
        rows = arrayOf(
            arrayOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ"),
            arrayOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"),
            arrayOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ")
        ),
        shiftedRows = arrayOf(
            arrayOf("ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", "ㅛ", "ㅕ", "ㅑ", "ㅒ", "ㅖ"),
            arrayOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"),
            arrayOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ")
        ),
        subLabels = STD_SUB_LABELS
    )

    val ALL_LANGUAGES: List<LanguageSpec> = listOf(
        EN_US,
        VI_TELEX,
        VI_VNI,
        FR_AZERTY,
        DE_QWERTZ,
        ES_QWERTY,
        RU_CYRILLIC,
        KO_HANGUL
    )

    fun getById(id: String): LanguageSpec {
        for (lang in ALL_LANGUAGES) {
            if (lang.id == id) return lang
        }
        return EN_US
    }
}
