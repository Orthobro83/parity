package app.parity.core.scan

import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode

/** Words and symbols the parser recognizes on price tags, in several languages. */
internal object Lexicon {
    /** Markers of one currency. */
    private val currencyMarkers: Map<String, String> = mapOf(
        "₾" to "GEL", "gel" to "GEL", "ლარი" to "GEL", "ლარ" to "GEL", "lari" to "GEL",
        "€" to "EUR", "eur" to "EUR", "euro" to "EUR", "£" to "GBP", "gbp" to "GBP",
        "us$" to "USD", "usd" to "USD", "ca$" to "CAD", "a$" to "AUD",
        "r$" to "BRL", "hk$" to "HKD", "nt$" to "TWD", "s$" to "SGD", "mx$" to "MXN", "nz$" to "NZD", "b$" to "BND",
        "円" to "JPY", "元" to "CNY", "人民币" to "CNY", "rmb" to "CNY", "₽" to "RUB",
        "₺" to "TRY", "tl" to "TRY", "₴" to "UAH", "грн" to "UAH", "₹" to "INR", "₩" to "KRW", "원" to "KRW",
        "₪" to "ILS", "ש״ח" to "ILS", "ש\"ח" to "ILS", "שח" to "ILS",
        "฿" to "THB", "บาท" to "THB", "₫" to "VND", "đ" to "VND", "₱" to "PHP",
        "zł" to "PLN", "zl" to "PLN", "kč" to "CZK", "kc" to "CZK", "ft" to "HUF",
        "лв" to "BGN", "лв." to "BGN", "₸" to "KZT", "тг" to "KZT", "֏" to "AMD", "դր" to "AMD", "դր." to "AMD",
        "₼" to "AZN", "ман" to "AZN", "ман." to "AZN", "chf" to "CHF", "fr." to "CHF",
        "rm" to "MYR", "rp" to "IDR", "₦" to "NGN", "₵" to "GHS", "₲" to "PYG", "₡" to "CRC", "s/" to "PEN",
        "ksh" to "KES", "ush" to "UGX", "tsh" to "TZS",
        "сом" to "KGS", "сомонӣ" to "TJS", "сўм" to "UZS", "soʻm" to "UZS", "so'm" to "UZS",
        "дин" to "RSD", "дин." to "RSD", "ден" to "MKD", "ден." to "MKD",
        "৳" to "BDT", "₮" to "MNT", "៛" to "KHR", "₭" to "LAK", "ကျပ်" to "MMK", "ብር" to "ETB", "ރ." to "MVR", "rf" to "MVR",
        "؋" to "AFN", "افغانی" to "AFN", "රු" to "LKR",
        "ر.س" to "SAR", "د.إ" to "AED", "د.م." to "MAD", "ج.م" to "EGP", "د.ك" to "KWD", "د.ب" to "BHD", "د.أ" to "JOD",
        "د.ا" to "JOD", "ر.ق" to "QAR", "ر.ع" to "OMR", "ر.ي" to "YER", "د.ع" to "IQD", "د.ل" to "LYD", "د.ت" to "TND",
        "د.ج" to "DZD", "ل.ل" to "LBP", "ل.س" to "SYP",
    )

    /** Markers several currencies share: the local currency if it's one of them, otherwise the first. */
    private val ambiguous: Map<String, List<String>> = mapOf(
        "$" to listOf(
            "USD", "CAD", "AUD", "NZD", "SGD", "HKD", "TWD", "MXN", "ARS", "CLP", "COP", "UYU", "CUP", "DOP", "JMD",
            "BSD", "BBD", "BZD", "BMD", "KYD", "TTD", "XCD", "FJD", "LRD", "NAD", "SBD", "SRD", "GYD", "BND",
        ),
        "c$" to listOf("CAD", "NIO"),
        "¥" to listOf("JPY", "CNY"),
        "kr" to listOf("SEK", "NOK", "DKK", "ISK"), "kr." to listOf("SEK", "NOK", "DKK", "ISK"),
        "rs" to listOf("INR", "PKR", "LKR", "NPR", "MUR", "SCR"), "rs." to listOf("INR", "PKR", "LKR", "NPR", "MUR", "SCR"),
        "₨" to listOf("INR", "PKR", "LKR", "NPR", "MUR", "SCR"),
        "руб" to listOf("RUB", "BYN"), "руб." to listOf("RUB", "BYN"), "р." to listOf("RUB", "BYN"),
        "lei" to listOf("RON", "MDL"),
        "ريال" to listOf("SAR", "QAR", "OMR", "YER", "IRR"), "ریال" to listOf("IRR", "SAR", "QAR", "OMR", "YER"), "درهم" to listOf("AED", "MAD"), "جنيه" to listOf("EGP", "SDG", "SSP"),
        "रु" to listOf("NPR", "INR"), "रू" to listOf("NPR", "INR"),
    )

    /** Markers too generic to trust on their own ("R", "DH"): only when they fit the local currency. */
    private val localOnly: Map<String, List<String>> = mapOf(
        "r" to listOf("ZAR"), "br" to listOf("BYN", "ETB"), "km" to listOf("BAM"), "dh" to listOf("MAD", "AED"),
        "dhs" to listOf("AED", "MAD"), "din" to listOf("RSD", "DZD", "TND"), "дин" to listOf("RSD"),
        "دينار" to listOf("KWD", "BHD", "JOD", "IQD", "LYD", "TND", "DZD"), "ليرة" to listOf("LBP", "SYP", "TRY"),
        "манат" to listOf("AZN", "TMT"), "cfa" to listOf("XOF", "XAF"), "fcfa" to listOf("XOF", "XAF"),
        "tk" to listOf("BDT"), "k" to listOf("MMK", "PGK", "ZMW"),
    )

    fun currencyFor(marker: String, local: CurrencyCode?): CurrencyCode? {
        val key = marker.lowercase()
        if (key.length == 3 && key.all { it.isLetter() }) {
            val code = CurrencyCode(key.uppercase())
            if (Currencies.isKnown(code) && marker == marker.uppercase()) return code
        }
        currencyMarkers[key]?.let { return CurrencyCode(it) }
        ambiguous[key]?.let { codes -> return if (local != null && local.code in codes) local else CurrencyCode(codes.first()) }
        localOnly[key]?.let { codes -> return local?.takeIf { it.code in codes } }
        return null
    }

    /** Unit suffixes that mean the number is a weight, volume, count or percentage, not a price. */
    val unitSuffixes = setOf(
        "g", "gr", "grs", "kg", "mg", "ml", "l", "lt", "ltr", "cl", "dl", "oz", "lb", "lbs", "pcs", "pc", "pk",
        "шт", "шт.", "г", "гр", "кг", "мл", "л", "გ", "გრ", "კგ", "მლ", "ც", "ცალი", "%", "x", "cm", "mm",
        "см", "мм", "kcal", "ккал", "კკალ", "kj", "st", "stk", "gb", "mb", "w", "v", "mah",
        "克", "千克", "公斤", "毫升", "升", "斤", "个", "個", "枚", "本", "袋", "入", "개", "그램", "봉",
        "กรัม", "มล.", "กก.", "ชิ้น", "جم", "غ", "غرام", "كجم", "كغ", "مل", "لتر", "گرم", "کیلو",
        "גרם", "מ\"ל", "ק\"ג", "ליטר", "γρ", "γρ.", "κιλό", "λίτρο", "գ", "կգ", "մլ", "լ",
    )

    /** Text that marks a line as a unit/comparison price ("per kg", "за 1 кг", "1 კგ-ის ფასი"). */
    val perUnitMarkers = listOf(
        "/kg", "/ kg", "per kg", "/100", "per 100", "/l", "/ l", "per l", "/lb", "per lb", "/ea",
        "за 1", "за кг", "за 100", "/кг", "за л", "/л", "1 kg", "1kg", "1 кг", "1кг", "100 g", "100g",
        "100 г", "100г", "1 კგ", "1კგ", "/კგ", "100 გ", "100გ", "1 ლ ", "1ლ ", "/ლ", "კგ-ის", "ლ-ის", "ლიტრის", "კილოგრამის",
        "unit price", "ერთეულის", "цена за", "prix au kg", "prix au l", "preis je", "precio por", "prezzo al",
        "€/kg", "$/kg", "₾/კგ", "₾/kg",
        "100gあたり", "100g当たり", "1kgあたり", "100mlあたり", "100ml当たり", "元/斤", "/斤", "元/kg", "每100g",
        "100g당", "1kg당", "100ml당", "لكل كيلو", "للكيلو", "/كغ", "ל-100 גרם", "ต่อกิโล", "/กก.", "τιμή κιλού", "/κιλό",
    )

    /** Prices shown alongside the one paid: in Japan, the pre-tax price beside the tax-inclusive (税込) one. */
    val secondaryPriceMarkers = listOf("税抜", "本体価格", "本体", "税別")

    /** Promotion words matched as whole words. */
    val promoWords = setOf(
        "sale", "promo", "special", "offer", "discount", "save", "deal", "clearance", "reduced", "bogo",
        "ფასდაკლება", "ფასდაკლებით", "აქცია", "აქციით", "შეთავაზება", "სპეციალური",
        "скидка", "скидки", "акция", "распродажа", "выгода", "спецпредложение", "суперцена",
        "angebot", "aktion", "reduziert", "soldes", "réduction", "oferta", "rebaja", "descuento",
        "offerta", "sconto", "indirim", "kampanya", "promoção", "desconto", "promocja", "rabat",
        "ofertas", "ofertón", "rebajas", "rebajado", "descuentos", "promoción", "promocion", "liquidación", "liquidacion",
        "rollback", "descontos", "promoções", "sconti", "offerte", "promos", "deals", "discounts",
        "تخفيض", "تخفيضات", "خصم", "عرض", "عروض", "تنزيلات", "تخفیف", "حراج", "מבצע", "מבצעים", "הנחה",
        "զեղչ", "ակցիա", "προσφορά", "έκπτωση", "εκπτώσεις", "छूट", "ऑफर", "सेल", "промоция", "намаление",
        "акција", "попуст", "akcija", "popust", "знижка", "акція", "жеңілдік", "хямдрал", "endirim", "aksiya",
        "chegirma", "diskon", "punguzo", "ቅናሽ",
    )

    /**
     * Promotion words in scripts written without spaces between words (Chinese, Japanese, Thai…),
     * matched anywhere in a line.
     */
    val promoFragments = listOf(
        "セール", "特売", "特価", "値引", "割引", "半額", "お買い得", "特价", "促销", "打折", "优惠", "折扣", "特價",
        "促銷", "優惠", "할인", "특가", "세일", "행사", "ลดราคา", "โปรโมชั่น", "ราคาพิเศษ", "ຫຼຸດລາຄາ", "ລາຄາພິເສດ",
        "បញ្ចុះតម្លៃ", "ប្រូម៉ូសិន", "လျှော့ဈေး", "ပရိုမိုးရှင်း",
    )

    /** Labels for the pre-sale price. */
    val oldPriceWords = listOf(
        "was", "old price", "regular", "reg.", "before", "ძველი ფასი", "ძველი", "старая цена", "было",
        "statt", "au lieu de", "antes", "prima", "通常価格", "定価", "原价", "原價", "정가", "السعر السابق",
        "السعر القديم", "מחיר קודם", "παλιά τιμή", "ราคาปกติ", "पुरानी कीमत",
    )

    val promoPhrases = listOf("% off", "ახალი ფასი", "новая цена", "now only", "giảm giá", "khuyến mãi", "potongan harga")

    /** Words right after an integer that make it a deal quantity ("3 for", "3 pcs"), not a price. */
    val quantitySuffixes = listOf(
        "+", "for", "pcs", "pieces", "pc", "шт", "за", "pour", "für", "por", "or", "и", "ცალ", "/",
        "点", "個", "个", "件", "개", "ชิ้น", "حبة", "حبات", "יח", "ב-",
    )

    /** Words right before an integer that make it a deal quantity ("buy 3", "from 3"). */
    val quantityPrefixes = setOf("buy", "from", "ab", "от", "desde", "покупке", "+", "შეიძინეთ", "ซื้อ", "اشتر", "よりどり")

    /** Deal, sale and unit words on Georgian tags, which script OCR often gets a letter wrong in. */
    private val georgianKeywords = listOf(
        "შეიძინეთ", "შეიძინე", "იყიდეთ", "ყიდვისას", "შეძენისას", "ცალის", "ცალი", "ფასდაკლება",
        "ფასდაკლებით", "აქცია", "აქციით", "შეთავაზება", "ძველი", "ახალი", "ფასი", "ლარი", "ლარად",
        "კილოგრამის", "ლიტრის", "ერთეულის",
    )

    /**
     * [text] with near-misses of those words ("შეიძინეტ", "ფასდაკლებს") spelled right, so deal and
     * sale wording is still recognized. Only used for matching keywords, never for names.
     */
    fun canonicalized(text: String): String = Regex("\\p{L}+").replace(text) { m ->
        val word = m.value
        if (word.length < 4 || word.none { it in 'Ⴀ'..'ჿ' } || word in georgianKeywords) return@replace word
        georgianKeywords.firstOrNull { k ->
            kotlin.math.abs(k.length - word.length) <= 1 && editDistance(k, word) <= (if (k.length >= 7) 2 else 1)
        } ?: word
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Lines made only of these words are never product names. */
    val boilerplate = setOf(
        "price", "prix", "preis", "precio", "prezzo", "цена", "ფასი", "ფასი:", "barcode", "code", "код", "კოდი",
        "art", "art.", "sku", "plu", "gel", "usd", "eur", "ლარი", "total", "sum",
    )
}
