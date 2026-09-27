package app.parity.core.money

data class Language(val tag: String, val englishName: String, val nativeName: String)

/** Languages supported by on-device translation (ML Kit), used for the "your language" setting. */
object Languages {
    val all: List<Language> = """
        af|Afrikaans|Afrikaans
        sq|Albanian|Shqip
        ar|Arabic|العربية
        be|Belarusian|Беларуская
        bn|Bengali|বাংলা
        bg|Bulgarian|Български
        ca|Catalan|Català
        zh|Chinese|中文
        hr|Croatian|Hrvatski
        cs|Czech|Čeština
        da|Danish|Dansk
        nl|Dutch|Nederlands
        en|English|English
        eo|Esperanto|Esperanto
        et|Estonian|Eesti
        fi|Finnish|Suomi
        fr|French|Français
        gl|Galician|Galego
        ka|Georgian|ქართული
        de|German|Deutsch
        el|Greek|Ελληνικά
        gu|Gujarati|ગુજરાતી
        ht|Haitian Creole|Kreyòl ayisyen
        he|Hebrew|עברית
        hi|Hindi|हिन्दी
        hu|Hungarian|Magyar
        is|Icelandic|Íslenska
        id|Indonesian|Bahasa Indonesia
        ga|Irish|Gaeilge
        it|Italian|Italiano
        ja|Japanese|日本語
        kn|Kannada|ಕನ್ನಡ
        ko|Korean|한국어
        lv|Latvian|Latviešu
        lt|Lithuanian|Lietuvių
        mk|Macedonian|Македонски
        ms|Malay|Bahasa Melayu
        mt|Maltese|Malti
        mr|Marathi|मराठी
        no|Norwegian|Norsk
        fa|Persian|فارسی
        pl|Polish|Polski
        pt|Portuguese|Português
        ro|Romanian|Română
        ru|Russian|Русский
        sk|Slovak|Slovenčina
        sl|Slovenian|Slovenščina
        es|Spanish|Español
        sw|Swahili|Kiswahili
        sv|Swedish|Svenska
        tl|Tagalog|Tagalog
        ta|Tamil|தமிழ்
        te|Telugu|తెలుగు
        th|Thai|ไทย
        tr|Turkish|Türkçe
        uk|Ukrainian|Українська
        ur|Urdu|اردو
        vi|Vietnamese|Tiếng Việt
        cy|Welsh|Cymraeg
    """.trimIndent().lines().map { line ->
        val (tag, english, native) = line.split('|')
        Language(tag, english, native)
    }

    fun find(tag: String): Language? = all.firstOrNull { it.tag == tag.lowercase() }

    /** Main language of product labels by country, used to pick OCR models and a translation source. */
    private val labelLanguage = mapOf(
        "GE" to "ka", "AM" to "hy", "RU" to "ru", "BY" to "ru", "KZ" to "ru", "KG" to "ru", "UA" to "uk",
        "GR" to "el", "CY" to "el", "IL" to "he", "TH" to "th", "JP" to "ja", "KR" to "ko", "CN" to "zh",
        "TW" to "zh", "HK" to "zh", "DE" to "de", "AT" to "de", "FR" to "fr", "ES" to "es", "MX" to "es",
        "IT" to "it", "PT" to "pt", "BR" to "pt", "TR" to "tr", "PL" to "pl", "NL" to "nl",
    )

    fun labelLanguageFor(countryCode: String?): String? = countryCode?.let { labelLanguage[it.uppercase()] }
}
