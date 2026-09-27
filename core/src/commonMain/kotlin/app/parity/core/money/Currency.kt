package app.parity.core.money

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** An ISO-4217 code ("USD", "GEL") or a crypto ticker ("BTC", "ZEC"). Always upper case. */
@Serializable
@JvmInline
value class CurrencyCode(val code: String) {
    init {
        require(code.isNotEmpty() && code == code.uppercase()) { "Currency code must be upper case: $code" }
    }

    override fun toString(): String = code

    companion object {
        fun of(raw: String): CurrencyCode = CurrencyCode(raw.trim().uppercase())
        val USD = CurrencyCode("USD")
        val EUR = CurrencyCode("EUR")
        val GEL = CurrencyCode("GEL")
        val BTC = CurrencyCode("BTC")
    }
}

data class Currency(
    val code: CurrencyCode,
    val name: String,
    /** Symbol used when displaying amounts. May be the code itself when there is no common symbol. */
    val symbol: String,
    /** Digits shown after the decimal point (ISO minor units for fiat). */
    val decimals: Int,
    val isCrypto: Boolean = false,
    /** CoinGecko coin id, for crypto only. */
    val coinGeckoId: String? = null,
)

object Currencies {
    private val fiat = """
        AED|UAE Dirham|د.إ|2
        AFN|Afghani|؋|2
        ALL|Albanian Lek|L|2
        AMD|Armenian Dram|֏|2
        ANG|Netherlands Antillean Guilder|ƒ|2
        AOA|Kwanza|Kz|2
        ARS|Argentine Peso|AR$|2
        AUD|Australian Dollar|A$|2
        AWG|Aruban Florin|ƒ|2
        AZN|Azerbaijani Manat|₼|2
        BAM|Convertible Mark|KM|2
        BBD|Barbados Dollar|Bds$|2
        BDT|Taka|৳|2
        BGN|Bulgarian Lev|лв|2
        BHD|Bahraini Dinar|BD|3
        BIF|Burundi Franc|FBu|0
        BMD|Bermudian Dollar|BD$|2
        BND|Brunei Dollar|B$|2
        BOB|Boliviano|Bs|2
        BRL|Brazilian Real|R$|2
        BSD|Bahamian Dollar|B$|2
        BTN|Ngultrum|Nu.|2
        BWP|Pula|P|2
        BYN|Belarusian Ruble|Br|2
        BZD|Belize Dollar|BZ$|2
        CAD|Canadian Dollar|CA$|2
        CDF|Congolese Franc|FC|2
        CHF|Swiss Franc|CHF|2
        CLP|Chilean Peso|CLP$|0
        CNY|Yuan Renminbi|CN¥|2
        COP|Colombian Peso|COL$|2
        CRC|Costa Rican Colón|₡|2
        CUP|Cuban Peso|CU$|2
        CVE|Cabo Verde Escudo|Esc|2
        CZK|Czech Koruna|Kč|2
        DJF|Djibouti Franc|Fdj|0
        DKK|Danish Krone|kr|2
        DOP|Dominican Peso|RD$|2
        DZD|Algerian Dinar|DA|2
        EGP|Egyptian Pound|E£|2
        ERN|Nakfa|Nfk|2
        ETB|Ethiopian Birr|Br|2
        EUR|Euro|€|2
        FJD|Fiji Dollar|FJ$|2
        FKP|Falkland Islands Pound|FK£|2
        GBP|Pound Sterling|£|2
        GEL|Georgian Lari|₾|2
        GHS|Ghana Cedi|GH₵|2
        GIP|Gibraltar Pound|GI£|2
        GMD|Dalasi|D|2
        GNF|Guinean Franc|FG|0
        GTQ|Quetzal|Q|2
        GYD|Guyana Dollar|G$|2
        HKD|Hong Kong Dollar|HK$|2
        HNL|Lempira|L|2
        HTG|Gourde|G|2
        HUF|Forint|Ft|2
        IDR|Rupiah|Rp|2
        ILS|New Israeli Shekel|₪|2
        INR|Indian Rupee|₹|2
        IQD|Iraqi Dinar|IQD|3
        IRR|Iranian Rial|IRR|2
        ISK|Iceland Króna|kr|0
        JMD|Jamaican Dollar|J$|2
        JOD|Jordanian Dinar|JD|3
        JPY|Yen|¥|0
        KES|Kenyan Shilling|KSh|2
        KGS|Som|сом|2
        KHR|Riel|៛|2
        KMF|Comorian Franc|CF|0
        KPW|North Korean Won|KP₩|2
        KRW|Won|₩|0
        KWD|Kuwaiti Dinar|KD|3
        KYD|Cayman Islands Dollar|CI$|2
        KZT|Tenge|₸|2
        LAK|Lao Kip|₭|2
        LBP|Lebanese Pound|L£|2
        LKR|Sri Lanka Rupee|Rs|2
        LRD|Liberian Dollar|L$|2
        LSL|Loti|L|2
        LYD|Libyan Dinar|LD|3
        MAD|Moroccan Dirham|DH|2
        MDL|Moldovan Leu|L|2
        MGA|Malagasy Ariary|Ar|2
        MKD|Denar|ден|2
        MMK|Kyat|K|2
        MNT|Tugrik|₮|2
        MOP|Pataca|MOP$|2
        MRU|Ouguiya|UM|2
        MUR|Mauritius Rupee|₨|2
        MVR|Rufiyaa|Rf|2
        MWK|Malawi Kwacha|MK|2
        MXN|Mexican Peso|MX$|2
        MYR|Malaysian Ringgit|RM|2
        MZN|Mozambique Metical|MT|2
        NAD|Namibia Dollar|N$|2
        NGN|Naira|₦|2
        NIO|Córdoba Oro|C$|2
        NOK|Norwegian Krone|kr|2
        NPR|Nepalese Rupee|Rs|2
        NZD|New Zealand Dollar|NZ$|2
        OMR|Omani Rial|OMR|3
        PAB|Balboa|B/.|2
        PEN|Sol|S/|2
        PGK|Kina|K|2
        PHP|Philippine Peso|₱|2
        PKR|Pakistan Rupee|Rs|2
        PLN|Złoty|zł|2
        PYG|Guaraní|₲|0
        QAR|Qatari Riyal|QR|2
        RON|Romanian Leu|lei|2
        RSD|Serbian Dinar|дин|2
        RUB|Russian Ruble|₽|2
        RWF|Rwanda Franc|FRw|0
        SAR|Saudi Riyal|SR|2
        SBD|Solomon Islands Dollar|SI$|2
        SCR|Seychelles Rupee|SRe|2
        SDG|Sudanese Pound|SDG|2
        SEK|Swedish Krona|kr|2
        SGD|Singapore Dollar|S$|2
        SHP|Saint Helena Pound|SH£|2
        SLE|Leone|Le|2
        SOS|Somali Shilling|Sh|2
        SRD|Surinam Dollar|SR$|2
        SSP|South Sudanese Pound|SS£|2
        STN|Dobra|Db|2
        SVC|El Salvador Colón|₡|2
        SYP|Syrian Pound|S£|2
        SZL|Lilangeni|E|2
        THB|Baht|฿|2
        TJS|Somoni|SM|2
        TMT|Turkmenistan Manat|m|2
        TND|Tunisian Dinar|DT|3
        TOP|Paʻanga|T$|2
        TRY|Turkish Lira|₺|2
        TTD|Trinidad and Tobago Dollar|TT$|2
        TWD|New Taiwan Dollar|NT$|2
        TZS|Tanzanian Shilling|TSh|2
        UAH|Hryvnia|₴|2
        UGX|Uganda Shilling|USh|0
        USD|US Dollar|$|2
        UYU|Uruguayan Peso|${'$'}U|2
        UZS|Uzbekistan Sum|soʻm|2
        VES|Bolívar Soberano|Bs.S|2
        VND|Dong|₫|0
        VUV|Vatu|VT|0
        WST|Tala|WS$|2
        XAF|CFA Franc BEAC|FCFA|0
        XCD|East Caribbean Dollar|EC$|2
        XCG|Caribbean Guilder|Cg|2
        XOF|CFA Franc BCEAO|CFA|0
        XPF|CFP Franc|₣|0
        YER|Yemeni Rial|YER|2
        ZAR|Rand|R|2
        ZMW|Zambian Kwacha|ZK|2
        ZWG|Zimbabwe Gold|ZiG|2
    """.trimIndent()

    private val crypto = """
        BTC|Bitcoin|₿|8|bitcoin
        ZEC|Zcash|ZEC|8|zcash
        ETH|Ether|Ξ|8|ethereum
        XMR|Monero|XMR|8|monero
        LTC|Litecoin|Ł|8|litecoin
        BCH|Bitcoin Cash|BCH|8|bitcoin-cash
        SOL|Solana|SOL|8|solana
        DOGE|Dogecoin|Ð|8|dogecoin
        XRP|XRP|XRP|6|ripple
        ADA|Cardano|ADA|6|cardano
        DOT|Polkadot|DOT|8|polkadot
        XLM|Stellar|XLM|7|stellar
        DASH|Dash|DASH|8|dash
        TRX|Tron|TRX|6|tron
        AVAX|Avalanche|AVAX|8|avalanche-2
        LINK|Chainlink|LINK|8|chainlink
        BNB|BNB|BNB|8|binancecoin
        USDT|Tether|USDT|2|tether
        USDC|USD Coin|USDC|2|usd-coin
    """.trimIndent()

    val all: List<Currency> = buildList {
        fiat.lines().forEach { line ->
            val (code, name, symbol, decimals) = line.split('|')
            add(Currency(CurrencyCode(code), name, symbol, decimals.toInt()))
        }
        crypto.lines().forEach { line ->
            val parts = line.split('|')
            add(Currency(CurrencyCode(parts[0]), parts[1], parts[2], parts[3].toInt(), isCrypto = true, coinGeckoId = parts[4]))
        }
    }

    private val byCode: Map<CurrencyCode, Currency> = all.associateBy { it.code }

    operator fun get(code: CurrencyCode): Currency =
        byCode[code] ?: Currency(code, code.code, code.code, 2)

    fun find(code: String): Currency? = byCode[CurrencyCode.of(code)]

    fun isKnown(code: CurrencyCode): Boolean = code in byCode

    fun search(query: String): List<Currency> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { it.code.code.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .sortedBy { if (it.code.code.lowercase() == q) 0 else if (it.code.code.lowercase().startsWith(q)) 1 else 2 }
    }
}
