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

    /** English short names (ISO 3166-1). */
    private val names: Map<String, String> = """
        AD Andorra|AE United Arab Emirates|AF Afghanistan|AG Antigua and Barbuda|AI Anguilla|AL Albania|AM Armenia
        AO Angola|AR Argentina|AS American Samoa|AT Austria|AU Australia|AW Aruba|AX Åland Islands|AZ Azerbaijan
        BA Bosnia and Herzegovina|BB Barbados|BD Bangladesh|BE Belgium|BF Burkina Faso|BG Bulgaria|BH Bahrain
        BI Burundi|BJ Benin|BL Saint Barthélemy|BM Bermuda|BN Brunei|BO Bolivia|BQ Caribbean Netherlands|BR Brazil
        BS Bahamas|BT Bhutan|BW Botswana|BY Belarus|BZ Belize|CA Canada|CC Cocos (Keeling) Islands|CD DR Congo
        CF Central African Republic|CG Congo|CH Switzerland|CI Côte d’Ivoire|CK Cook Islands|CL Chile|CM Cameroon
        CN China|CO Colombia|CR Costa Rica|CU Cuba|CV Cabo Verde|CW Curaçao|CX Christmas Island|CY Cyprus
        CZ Czechia|DE Germany|DJ Djibouti|DK Denmark|DM Dominica|DO Dominican Republic|DZ Algeria|EC Ecuador
        EE Estonia|EG Egypt|EH Western Sahara|ER Eritrea|ES Spain|ET Ethiopia|FI Finland|FJ Fiji
        FK Falkland Islands|FM Micronesia|FO Faroe Islands|FR France|GA Gabon|GB United Kingdom|GD Grenada
        GE Georgia|GF French Guiana|GG Guernsey|GH Ghana|GI Gibraltar|GL Greenland|GM Gambia|GN Guinea
        GP Guadeloupe|GQ Equatorial Guinea|GR Greece|GT Guatemala|GU Guam|GW Guinea-Bissau|GY Guyana
        HK Hong Kong|HN Honduras|HR Croatia|HT Haiti|HU Hungary|ID Indonesia|IE Ireland|IL Israel|IM Isle of Man
        IN India|IO British Indian Ocean Territory|IQ Iraq|IR Iran|IS Iceland|IT Italy|JE Jersey|JM Jamaica
        JO Jordan|JP Japan|KE Kenya|KG Kyrgyzstan|KH Cambodia|KI Kiribati|KM Comoros|KN Saint Kitts and Nevis
        KP North Korea|KR South Korea|KW Kuwait|KY Cayman Islands|KZ Kazakhstan|LA Laos|LB Lebanon|LC Saint Lucia
        LI Liechtenstein|LK Sri Lanka|LR Liberia|LS Lesotho|LT Lithuania|LU Luxembourg|LV Latvia|LY Libya
        MA Morocco|MC Monaco|MD Moldova|ME Montenegro|MF Saint Martin|MG Madagascar|MH Marshall Islands
        MK North Macedonia|ML Mali|MM Myanmar|MN Mongolia|MO Macao|MP Northern Mariana Islands|MQ Martinique
        MR Mauritania|MS Montserrat|MT Malta|MU Mauritius|MV Maldives|MW Malawi|MX Mexico|MY Malaysia
        MZ Mozambique|NA Namibia|NC New Caledonia|NE Niger|NF Norfolk Island|NG Nigeria|NI Nicaragua
        NL Netherlands|NO Norway|NP Nepal|NR Nauru|NU Niue|NZ New Zealand|OM Oman|PA Panama|PE Peru
        PF French Polynesia|PG Papua New Guinea|PH Philippines|PK Pakistan|PL Poland|PM Saint Pierre and Miquelon
        PN Pitcairn Islands|PR Puerto Rico|PS Palestine|PT Portugal|PW Palau|PY Paraguay|QA Qatar|RE Réunion
        RO Romania|RS Serbia|RU Russia|RW Rwanda|SA Saudi Arabia|SB Solomon Islands|SC Seychelles|SD Sudan
        SE Sweden|SG Singapore|SH Saint Helena|SI Slovenia|SJ Svalbard and Jan Mayen|SK Slovakia|SL Sierra Leone
        SM San Marino|SN Senegal|SO Somalia|SR Suriname|SS South Sudan|ST São Tomé and Príncipe|SV El Salvador
        SX Sint Maarten|SY Syria|SZ Eswatini|TC Turks and Caicos Islands|TD Chad|TG Togo|TH Thailand
        TJ Tajikistan|TK Tokelau|TL Timor-Leste|TM Turkmenistan|TN Tunisia|TO Tonga|TR Türkiye
        TT Trinidad and Tobago|TV Tuvalu|TW Taiwan|TZ Tanzania|UA Ukraine|UG Uganda|UM U.S. Outlying Islands
        US United States|UY Uruguay|UZ Uzbekistan|VA Vatican City|VC Saint Vincent and the Grenadines
        VE Venezuela|VG British Virgin Islands|VI U.S. Virgin Islands|VN Vietnam|VU Vanuatu|WF Wallis and Futuna
        WS Samoa|XK Kosovo|YE Yemen|YT Mayotte|ZA South Africa|ZM Zambia|ZW Zimbabwe
    """.trimIndent().split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
        entry.substring(0, 2) to entry.substring(3)
    }

    fun name(countryCode: String): String = names[countryCode.uppercase()] ?: countryCode.uppercase()

    val allCodes: List<String> get() = currencyByCountry.keys.sorted()
}
