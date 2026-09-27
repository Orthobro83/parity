package app.parity.core.money

/** ISO-3166 alpha-2 country code → local currency, plus alternatives where two are in common use. */
object Countries {
    private val table = """
        AD EUR|AE AED|AF AFN|AG XCD|AI XCD|AL ALL|AM AMD|AO AOA|AR ARS|AS USD|AT EUR|AU AUD|AW AWG|AX EUR
        AZ AZN|BA BAM|BB BBD|BD BDT|BE EUR|BF XOF|BG EUR|BH BHD|BI BIF|BJ XOF|BL EUR|BM BMD|BN BND|BO BOB
        BQ USD|BR BRL|BS BSD|BT BTN|BW BWP|BY BYN|BZ BZD|CA CAD|CC AUD|CD CDF|CF XAF|CG XAF|CH CHF|CI XOF
        CK NZD|CL CLP|CM XAF|CN CNY|CO COP|CR CRC|CU CUP|CV CVE|CW XCG|CX AUD|CY EUR|CZ CZK|DE EUR|DJ DJF
        DK DKK|DM XCD|DO DOP|DZ DZD|EC USD|EE EUR|EG EGP|EH MAD|ER ERN|ES EUR|ET ETB|FI EUR|FJ FJD|FK FKP
        FM USD|FO DKK|FR EUR|GA XAF|GB GBP|GD XCD|GE GEL|GF EUR|GG GBP|GH GHS|GI GIP|GL DKK|GM GMD|GN GNF
        GP EUR|GQ XAF|GR EUR|GT GTQ|GU USD|GW XOF|GY GYD|HK HKD|HN HNL|HR EUR|HT HTG|HU HUF|ID IDR|IE EUR
        IL ILS|IM GBP|IN INR|IO USD|IQ IQD|IR IRR|IS ISK|IT EUR|JE GBP|JM JMD|JO JOD|JP JPY|KE KES|KG KGS
        KH KHR|KI AUD|KM KMF|KN XCD|KP KPW|KR KRW|KW KWD|KY KYD|KZ KZT|LA LAK|LB LBP|LC XCD|LI CHF|LK LKR
        LR LRD|LS LSL|LT EUR|LU EUR|LV EUR|LY LYD|MA MAD|MC EUR|MD MDL|ME EUR|MF EUR|MG MGA|MH USD|MK MKD
        ML XOF|MM MMK|MN MNT|MO MOP|MP USD|MQ EUR|MR MRU|MS XCD|MT EUR|MU MUR|MV MVR|MW MWK|MX MXN|MY MYR
        MZ MZN|NA NAD|NC XPF|NE XOF|NF AUD|NG NGN|NI NIO|NL EUR|NO NOK|NP NPR|NR AUD|NU NZD|NZ NZD|OM OMR
        PA USD|PE PEN|PF XPF|PG PGK|PH PHP|PK PKR|PL PLN|PM EUR|PN NZD|PR USD|PS ILS|PT EUR|PW USD|PY PYG
        QA QAR|RE EUR|RO RON|RS RSD|RU RUB|RW RWF|SA SAR|SB SBD|SC SCR|SD SDG|SE SEK|SG SGD|SH SHP|SI EUR
        SJ NOK|SK EUR|SL SLE|SM EUR|SN XOF|SO SOS|SR SRD|SS SSP|ST STN|SV USD|SX XCG|SY SYP|SZ SZL|TC USD
        TD XAF|TG XOF|TH THB|TJ TJS|TK NZD|TL USD|TM TMT|TN TND|TO TOP|TR TRY|TT TTD|TV AUD|TW TWD|TZ TZS
        UA UAH|UG UGX|UM USD|US USD|UY UYU|UZ UZS|VA EUR|VC XCD|VE VES|VG USD|VI USD|VN VND|VU VUV|WF XPF
        WS WST|XK EUR|YE YER|YT EUR|ZA ZAR|ZM ZMW|ZW ZWG
    """.trimIndent()

    private val currencyByCountry: Map<String, CurrencyCode> =
        table.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
            val (country, currency) = entry.split(' ')
            country to CurrencyCode(currency)
        }

    /** Countries where a second currency is in everyday use; the user is offered a choice. */
    val alternatives: Map<String, List<CurrencyCode>> = mapOf(
        "PA" to listOf("USD", "PAB"), "ZW" to listOf("ZWG", "USD"), "KH" to listOf("KHR", "USD"),
        "BT" to listOf("BTN", "INR"), "LS" to listOf("LSL", "ZAR"), "NA" to listOf("NAD", "ZAR"),
        "SZ" to listOf("SZL", "ZAR"), "SV" to listOf("USD", "BTC"), "LB" to listOf("LBP", "USD"),
        "VE" to listOf("VES", "USD"), "CU" to listOf("CUP", "USD"),
    ).mapValues { (_, codes) -> codes.map(::CurrencyCode) }

    fun currencyFor(countryCode: String): CurrencyCode? = currencyByCountry[countryCode.uppercase()]

    /** Unicode regional-indicator flag for a two-letter country code, e.g. "GE" → 🇬🇪. */
    fun flag(countryCode: String): String {
        if (countryCode.length != 2) return ""
        return countryCode.uppercase().map { c ->
            val cp = 0x1F1E6 + (c - 'A')
            val high = ((cp - 0x10000) shr 10) + 0xD800
            val low = ((cp - 0x10000) and 0x3FF) + 0xDC00
            "${high.toChar()}${low.toChar()}"
        }.joinToString("")
    }

    /** English display names for the countries most relevant to travel; others fall back to the code. */
    private val names = mapOf(
        "GE" to "Georgia", "US" to "United States", "CA" to "Canada", "GB" to "United Kingdom",
        "AM" to "Armenia", "AZ" to "Azerbaijan", "TR" to "Türkiye", "RU" to "Russia", "UA" to "Ukraine",
        "DE" to "Germany", "FR" to "France", "IT" to "Italy", "ES" to "Spain", "PT" to "Portugal",
        "NL" to "Netherlands", "BE" to "Belgium", "AT" to "Austria", "CH" to "Switzerland", "PL" to "Poland",
        "CZ" to "Czechia", "HU" to "Hungary", "RO" to "Romania", "BG" to "Bulgaria", "GR" to "Greece",
        "RS" to "Serbia", "HR" to "Croatia", "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark",
        "FI" to "Finland", "IE" to "Ireland", "IS" to "Iceland", "MX" to "Mexico", "BR" to "Brazil",
        "AR" to "Argentina", "CL" to "Chile", "CO" to "Colombia", "PE" to "Peru", "JP" to "Japan",
        "KR" to "South Korea", "CN" to "China", "HK" to "Hong Kong", "TW" to "Taiwan", "TH" to "Thailand",
        "VN" to "Vietnam", "ID" to "Indonesia", "MY" to "Malaysia", "SG" to "Singapore", "PH" to "Philippines",
        "IN" to "India", "AE" to "United Arab Emirates", "IL" to "Israel", "EG" to "Egypt", "MA" to "Morocco",
        "ZA" to "South Africa", "AU" to "Australia", "NZ" to "New Zealand", "KZ" to "Kazakhstan",
        "UZ" to "Uzbekistan", "KG" to "Kyrgyzstan", "SV" to "El Salvador", "PA" to "Panama",
    )

    fun name(countryCode: String): String = names[countryCode.uppercase()] ?: countryCode.uppercase()

    val allCodes: List<String> get() = currencyByCountry.keys.sorted()
}
