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

    /**
     * Languages shelf labels are written in that aren't in [all]: products can be read and
     * translated from them (online), but they can't be chosen as "your language".
     */
    val labelOnly: List<Language> = """
        am|Amharic|አማርኛ
        az|Azerbaijani|Azərbaycan
        bs|Bosnian|Bosanski
        dv|Dhivehi|ދިވެހި
        fo|Faroese|Føroyskt
        hy|Armenian|Հայերեն
        kk|Kazakh|Қазақ
        km|Khmer|ខ្មែរ
        ky|Kyrgyz|Кыргызча
        lo|Lao|ລາວ
        mn|Mongolian|Монгол
        my|Burmese|မြန်မာ
        ne|Nepali|नेपाली
        si|Sinhala|සිංහල
        so|Somali|Soomaali
        sr|Serbian|Српски
        sr-Latn|Serbian|Srpski
        tg|Tajik|Тоҷикӣ
        ti|Tigrinya|ትግርኛ
        tk|Turkmen|Türkmençe
        uz|Uzbek|Oʻzbekcha
    """.trimIndent().lines().map { line ->
        val (tag, english, native) = line.split('|')
        Language(tag, english, native)
    }

    fun find(tag: String): Language? =
        all.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: labelOnly.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    /**
     * Main language of shelf labels in each country, which picks the text reader and the translation
     * source. Where labels are commonly in two languages, the one most tags lead with.
     */
    private val labelLanguage: Map<String, String> = """
        AD ca|AE ar|AF fa|AG en|AI en|AL sq|AM hy|AO pt|AR es|AS en|AT de|AU en|AW nl|AX sv|AZ az
        BA bs|BB en|BD bn|BE nl|BF fr|BG bg|BH ar|BI fr|BJ fr|BL fr|BM en|BN ms|BO es|BQ nl|BR pt
        BS en|BT en|BW en|BY ru|BZ en|CA en|CC en|CD fr|CF fr|CG fr|CH de|CI fr|CK en|CL es|CM fr
        CN zh|CO es|CR es|CU es|CV pt|CW nl|CX en|CY el|CZ cs|DE de|DJ fr|DK da|DM en|DO es|DZ ar
        EC es|EE et|EG ar|EH ar|ER ti|ES es|ET am|FI fi|FJ en|FK en|FM en|FO fo|FR fr|GA fr|GB en
        GD en|GE ka|GF fr|GG en|GH en|GI en|GL da|GM en|GN fr|GP fr|GQ es|GR el|GT es|GU en|GW pt
        GY en|HK zh|HN es|HR hr|HT fr|HU hu|ID id|IE en|IL he|IM en|IN hi|IO en|IQ ar|IR fa|IS is
        IT it|JE en|JM en|JO ar|JP ja|KE en|KG ky|KH km|KI en|KM fr|KN en|KP ko|KR ko|KW ar|KY en
        KZ kk|LA lo|LB ar|LC en|LI de|LK si|LR en|LS en|LT lt|LU fr|LV lv|LY ar|MA ar|MC fr|MD ro
        ME sr-Latn|MF fr|MG fr|MH en|MK mk|ML fr|MM my|MN mn|MO zh|MP en|MQ fr|MR ar|MS en|MT en
        MU en|MV dv|MW en|MX es|MY ms|MZ pt|NA en|NC fr|NE fr|NF en|NG en|NI es|NL nl|NO no|NP ne
        NR en|NU en|NZ en|OM ar|PA es|PE es|PF fr|PG en|PH en|PK ur|PL pl|PM fr|PN en|PR es|PS ar
        PT pt|PW en|PY es|QA ar|RE fr|RO ro|RS sr|RU ru|RW en|SA ar|SB en|SC en|SD ar|SE sv|SG en
        SH en|SI sl|SJ no|SK sk|SL en|SM it|SN fr|SO so|SR nl|SS en|ST pt|SV es|SX nl|SY ar|SZ en
        TC en|TD fr|TG fr|TH th|TJ tg|TK en|TL pt|TM tk|TN ar|TO en|TR tr|TT en|TV en|TW zh|TZ sw
        UA uk|UG en|UM en|US en|UY es|UZ uz|VA it|VC en|VE es|VG en|VI en|VN vi|VU en|WF fr|WS en
        XK sq|YE ar|YT fr|ZA en|ZM en|ZW en
    """.trimIndent().split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
        val (country, language) = entry.split(' ')
        country to language
    }

    fun labelLanguageFor(countryCode: String?): String? = countryCode?.let { labelLanguage[it.uppercase()] }

    /** Other languages shelf labels are commonly in, where there's more than one (Swiss French, Canadian French). */
    private val otherLabelLanguages = mapOf(
        "CH" to setOf("fr", "it"), "BE" to setOf("fr"), "CA" to setOf("fr"), "LU" to setOf("de"), "FI" to setOf("sv"),
        "ES" to setOf("ca"), "MA" to setOf("fr"), "DZ" to setOf("fr"), "TN" to setOf("fr"), "LB" to setOf("fr", "en"),
        "IN" to setOf("en"), "PK" to setOf("en"), "PH" to setOf("tl"), "ZA" to setOf("af"), "SG" to setOf("zh", "ms"),
        "MY" to setOf("en", "zh"), "IE" to setOf("ga"), "MT" to setOf("mt"), "KE" to setOf("sw"), "TZ" to setOf("en"),
    )

    /** True when labels in [countryCode] are commonly in [language] besides the main one. */
    fun isOtherLabelLanguage(countryCode: String?, language: String): Boolean =
        countryCode != null && base(language) in otherLabelLanguages[countryCode.uppercase()].orEmpty()

    /**
     * Label language where prices are in [localCurrency]: the country's, unless the local currency
     * was set by hand to another country's (GEL while located in the US), whose labels it follows.
     * Dollars and euros are priced in far from home (Georgia, Cambodia), so they don't change it.
     */
    fun labelLanguageFor(countryCode: String?, localCurrency: CurrencyCode?): String? {
        val byCountry = labelLanguageFor(countryCode)
        if (localCurrency == null || (countryCode != null && Countries.currencyFor(countryCode) == localCurrency)) return byCountry
        if (byCountry != null && localCurrency.code in circulatingWidely) return byCountry
        return labelLanguageFor(Countries.homeCountryOf(localCurrency)) ?: byCountry
    }

    private val circulatingWidely = setOf("USD", "EUR")

    /** The plain language code of a label language: "sr-Latn" → "sr". */
    fun base(language: String): String = language.substringBefore('-')
}
