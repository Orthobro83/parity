package app.parity.core.scan

import app.parity.core.money.Languages

/** The phone's fast text recognizers (ML Kit): each also reads Latin letters and digits. */
enum class TextScript { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

/**
 * How shelf labels in each language are read and translated (design §14). Languages in scripts the
 * fast recognizers cover are read by them, live; all others by Tesseract with that language's
 * model, from the frame a price locked on. Every label language in [Languages] is covered.
 */
object LabelReading {
    private val fastScripts = mapOf(
        "zh" to TextScript.CHINESE, "ja" to TextScript.JAPANESE, "ko" to TextScript.KOREAN,
        "hi" to TextScript.DEVANAGARI, "mr" to TextScript.DEVANAGARI, "ne" to TextScript.DEVANAGARI,
    )

    /** Tesseract models (tessdata_fast) for label languages in scripts the fast recognizers can't read. */
    private val tesseractModels = mapOf(
        "am" to "amh", "ar" to "ara", "be" to "bel", "bg" to "bul", "bn" to "ben", "dv" to "div",
        "el" to "ell", "fa" to "fas", "gu" to "guj", "he" to "heb", "hy" to "hye", "ka" to "kat",
        "kk" to "kaz", "km" to "khm", "kn" to "kan", "ky" to "kir", "lo" to "lao", "mk" to "mkd",
        "ml" to "mal", "mn" to "mon", "my" to "mya", "pa" to "pan", "ps" to "pus", "ru" to "rus",
        "si" to "sin", "sr" to "srp", "ta" to "tam", "te" to "tel", "tg" to "tgk", "th" to "tha",
        "ti" to "tir", "uk" to "ukr", "ur" to "urd", "yi" to "yid",
    )

    /** The fast recognizer that reads [language]'s labels, or null when they need Tesseract. */
    fun fastScript(language: String?): TextScript? {
        if (language == null) return TextScript.LATIN
        fastScripts[Languages.base(language)]?.let { return it }
        return if (tesseractModel(language) == null) TextScript.LATIN else null
    }

    /** The Tesseract model for [language], e.g. "hye" for Armenian, or null when a fast recognizer reads it. */
    fun tesseractModel(language: String?): String? {
        if (language == null || language.endsWith("-Latn")) return null
        return tesseractModels[Languages.base(language)]
    }

    /** Tesseract language string for [language]'s labels: its model plus English for brands. */
    fun tesseractLanguages(language: String?): String? = tesseractModel(language)?.let { "$it+eng" }

    /** True when [language] is written in a script of its own rather than Latin letters. */
    fun hasOwnScript(language: String?): Boolean = NameText.scriptOf(language) != null

    /**
     * The language to translate [language] from with an offline pack (ML Kit), or null when there's
     * none. Serbian and Bosnian use the Croatian pack: they're one language in standard variants.
     */
    fun offlineSource(language: String, offlineLanguages: Set<String>): String? {
        val base = Languages.base(language)
        if (base in offlineLanguages) return base
        return if (base == "sr" || base == "bs") "hr".takeIf { it in offlineLanguages } else null
    }

    /** [text] prepared for [offlineSource]: Serbian Cyrillic is written in Latin letters for the Croatian pack. */
    fun forOfflineTranslation(text: String, language: String): String =
        if (Languages.base(language) == "sr") serbianToLatin(text) else text

    private val serbianLatin = mapOf(
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D", 'Ђ' to "Đ", 'Е' to "E", 'Ж' to "Ž",
        'З' to "Z", 'И' to "I", 'Ј' to "J", 'К' to "K", 'Л' to "L", 'Љ' to "Lj", 'М' to "M", 'Н' to "N",
        'Њ' to "Nj", 'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T", 'Ћ' to "Ć", 'У' to "U",
        'Ф' to "F", 'Х' to "H", 'Ц' to "C", 'Ч' to "Č", 'Џ' to "Dž", 'Ш' to "Š",
    )

    fun serbianToLatin(text: String): String = buildString {
        for (c in text) {
            val upper = serbianLatin[c]
            val lower = serbianLatin[c.uppercaseChar()]
            when {
                upper != null -> append(upper)
                lower != null -> append(lower.lowercase())
                else -> append(c)
            }
        }
    }
}

/**
 * Prices written with other digits: Arabic-Indic (٢٥٫٩٠), Persian, Devanagari, Bengali, Thai, Lao,
 * Khmer, Burmese and full-width (２５．９０) digits and separators become ASCII, one character for
 * one, so positions in the text are unchanged.
 */
object Digits {
    fun normalize(text: String): String {
        if (text.all { it.code < 0x80 }) return text
        return buildString(text.length) {
            for (c in text) {
                append(
                    when {
                        c.code >= 0x80 && c.isDigit() -> ('0' + c.digitToInt())
                        c == '٫' || c == '．' -> '.' // Arabic decimal separator, full-width stop
                        c == '٬' || c == '，' -> ',' // Arabic thousands separator, full-width comma
                        c == '٪' || c == '％' -> '%'
                        c == '＋' -> '+'
                        c == '／' -> '/'
                        c == '￥' -> '¥'
                        c == '＄' -> '$'
                        else -> c
                    },
                )
            }
        }
    }
}
