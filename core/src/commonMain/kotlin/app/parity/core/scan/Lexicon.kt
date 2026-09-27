package app.parity.core.scan

import app.parity.core.money.Currencies
import app.parity.core.money.CurrencyCode

/** Words and symbols the parser recognizes on price tags, in several languages. */
internal object Lexicon {
    /** Currency markers; a null value means "ambiguous symbol, use the local currency if it matches". */
    private val currencyMarkers: Map<String, String?> = mapOf(
        "₾" to "GEL", "gel" to "GEL", "ლარი" to "GEL", "ლარ" to "GEL", "lari" to "GEL",
        "€" to "EUR", "eur" to "EUR", "euro" to "EUR", "£" to "GBP", "gbp" to "GBP",
        "$" to null, "us$" to "USD", "usd" to "USD", "ca$" to "CAD", "c$" to null, "a$" to "AUD",
        "r$" to "BRL", "hk$" to "HKD", "nt$" to "TWD", "s$" to "SGD", "mx$" to "MXN", "nz$" to "NZD",
        "¥" to null, "円" to "JPY", "元" to "CNY", "₽" to "RUB", "руб" to "RUB", "руб." to "RUB", "р." to "RUB",
        "₺" to "TRY", "tl" to "TRY", "₴" to "UAH", "грн" to "UAH", "₹" to "INR", "₩" to "KRW", "원" to "KRW",
        "₪" to "ILS", "฿" to "THB", "บาท" to "THB", "₫" to "VND", "đ" to "VND", "₱" to "PHP",
        "zł" to "PLN", "zl" to "PLN", "kč" to "CZK", "kc" to "CZK", "ft" to "HUF", "lei" to "RON",
        "лв" to "BGN", "лв." to "BGN", "₸" to "KZT", "тг" to "KZT", "֏" to "AMD", "դր" to "AMD", "դր." to "AMD",
        "₼" to "AZN", "manat" to "AZN", "kr" to null, "kr." to null, "chf" to "CHF", "fr." to "CHF",
        "rm" to "MYR", "rp" to "IDR", "₦" to "NGN", "₵" to "GHS", "rs" to null, "rs." to null,
        "сом" to "KGS", "сўм" to "UZS", "soʻm" to "UZS", "so'm" to "UZS",
    )

    /** Symbols shared by several currencies, resolved against the local currency's symbol. */
    private val ambiguousDefaults = mapOf("$" to "USD", "c$" to "CAD", "¥" to "JPY", "kr" to "SEK", "kr." to "SEK", "rs" to "INR", "rs." to "INR")

    fun currencyFor(marker: String, local: CurrencyCode?): CurrencyCode? {
        val key = marker.lowercase()
        if (key.length == 3 && key.all { it.isLetter() }) {
            val code = CurrencyCode(key.uppercase())
            if (Currencies.isKnown(code) && marker == marker.uppercase()) return code
        }
        if (key !in currencyMarkers) return null
        val direct = currencyMarkers[key]
        if (direct != null) return CurrencyCode(direct)
        if (local != null) {
            val localSymbol = Currencies[local].symbol.lowercase()
            if (localSymbol == key || localSymbol.endsWith(key)) return local
        }
        return ambiguousDefaults[key]?.let(::CurrencyCode)
    }

    /** Unit suffixes that mean the number is a weight, volume, count or percentage, not a price. */
    val unitSuffixes = setOf(
        "g", "gr", "grs", "kg", "mg", "ml", "l", "lt", "ltr", "cl", "dl", "oz", "lb", "lbs", "pcs", "pc", "pk",
        "шт", "шт.", "г", "гр", "кг", "мл", "л", "გ", "გრ", "კგ", "მლ", "ც", "ცალი", "%", "x", "cm", "mm",
        "см", "мм", "kcal", "ккал", "კკალ", "kj", "st", "stk", "gb", "mb", "w", "v", "mah",
    )

    /** Text that marks a line as a unit/comparison price ("per kg", "за 1 кг", "1 კგ-ის ფასი"). */
    val perUnitMarkers = listOf(
        "/kg", "/ kg", "per kg", "/100", "per 100", "/l", "/ l", "per l", "/lb", "per lb", "/ea",
        "за 1", "за кг", "за 100", "/кг", "за л", "/л", "1 kg", "1kg", "1 кг", "1кг", "100 g", "100g",
        "100 г", "100г", "1 კგ", "1კგ", "/კგ", "100 გ", "100გ", "1 ლ ", "1ლ ", "/ლ", "კგ-ის", "ლ-ის", "ლიტრის", "კილოგრამის",
        "unit price", "ერთეულის", "цена за", "prix au kg", "prix au l", "preis je", "precio por", "prezzo al",
        "€/kg", "$/kg", "₾/კგ", "₾/kg",
    )

    /** Promotion words matched as whole words. */
    val promoWords = setOf(
        "sale", "promo", "special", "offer", "discount", "save", "deal", "clearance", "reduced", "bogo",
        "ფასდაკლება", "ფასდაკლებით", "აქცია", "აქციით", "შეთავაზება", "სპეციალური",
        "скидка", "скидки", "акция", "распродажа", "выгода", "спецпредложение", "суперцена",
        "angebot", "aktion", "reduziert", "soldes", "réduction", "oferta", "rebaja", "descuento",
        "offerta", "sconto", "indirim", "kampanya", "promoção", "desconto", "promocja", "rabat",
    )

    /** Labels for the pre-sale price. */
    val oldPriceWords = listOf(
        "was", "old price", "regular", "reg.", "before", "ძველი ფასი", "ძველი", "старая цена", "было",
        "statt", "au lieu de", "antes", "prima",
    )

    val promoPhrases = listOf("% off", "1+1", "2+1", "2 for", "3 for 2", "ახალი ფასი", "новая цена", "now only")

    /** Lines made only of these words are never product names. */
    val boilerplate = setOf(
        "price", "prix", "preis", "precio", "prezzo", "цена", "ფასი", "ფასი:", "barcode", "code", "код", "კოდი",
        "art", "art.", "sku", "plu", "gel", "usd", "eur", "ლარი", "total", "sum",
    )
}
