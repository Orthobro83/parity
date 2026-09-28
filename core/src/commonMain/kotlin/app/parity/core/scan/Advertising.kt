package app.parity.core.scan

/**
 * Tells a tag's advertising ("Super Discount", "¡Oferta!", "Celebra tus ahorros") from the product's
 * name and quantity (design §6.2). Stores print deal banners and slogans in big letters right where
 * a name would be, so they're recognized by their words: a line that's mostly marketing words isn't
 * a name, unless it also gives a quantity ("Super Bock 6 x 330 ml").
 */
internal object Advertising {
    private val accents = mapOf(
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
        'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o', 'ø' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ñ' to 'n', 'ç' to 'c', 'ß' to 's', 'ł' to 'l', 'ż' to 'z', 'ź' to 'z',
        'ś' to 's', 'ć' to 'c', 'ń' to 'n', 'ę' to 'e', 'ą' to 'a', 'ğ' to 'g', 'ş' to 's', 'ı' to 'i', 'ё' to 'е',
    )

    /** Lower case without accents, so "Promoción", "promocion" and "PROMOCIÓN" match. */
    private fun plain(word: String): String = word.lowercase().map { accents[it] ?: it }.joinToString("")

    /** Marketing and deal words in the languages of most shelf tags, compared without accents. */
    private val marketing: Set<String> = """
        sale sales deal deals offer offers special specials discount discounts save saves saving savings super mega hot best
        value values price prices priced low lower lowest everyday rollback clearance reduced bargain bonus extra free only
        now promo promotion promotions limited exclusive today week weekly weekend club member members buy get off half great
        amazing unbeatable wow flash bogo celebrate celebration spend pay less more big new
        oferta ofertas oferton precio precios bajo bajos baja bajas especial especiales descuento descuentos rebaja rebajas
        rebajado rebajada ahorro ahorros ahorra ahorre ahorras ahorrar ahorrate celebra celebre celebramos promocion promociones
        liquidacion llevate lleva llevas paga pagas gratis solo hoy nuevo nueva exclusivo exclusiva imperdible increible mejor
        mejores gran grandes dia dias semana semanal fin siempre socio socios compra compras mas menos ahora antes hasta
        aprovecha aprovechar temporada remate barato barata economico
        promocao desconto descontos economize economia economizar preco precos leve pague aproveite hoje imperdivel clube
        offerta offerte sconto sconti risparmio risparmia prezzo prezzi basso bassi speciale promozione oggi convenienza
        conveniente occasione sottocosto
        offre offres prix remise reduction economisez soldes exceptionnel gratuit seulement aujourd nouveau bon plan lot
        avantage immanquable
        angebot angebote aktion rabatt sparen spare preis preise gunstig tiefpreis sonderangebot knaller nur heute neu woche
        statt reduziert
        aanbieding korting actie prijs voordeel bespaar
        promocja promocje obnizka cena niska taniej oszczedzaj nowosc
        indirim kampanya firsat fiyat ucuz yeni bedava sadece bugun
        акция скидка скидки выгода выгодно суперцена низкая распродажа спецпредложение супер новинка только сегодня хит экономия экономь
        აქცია ფასდაკლება შეთავაზება სპეციალური ფასი დაზოგე ახალი სუპერ
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::plain).toSet()

    /** Little words that are neither: "tus" in "Celebra tus ahorros", "de" in "Leche de vaca". */
    private val filler: Set<String> = """
        the an of and or for to your our you we with in on at by from this that is are all every it its
        el la los las un una unos unas de del al para por con en tu tus su sus nuestro nuestros nuestra nuestras mi mis que es
        son cada todo todos todas lo le les des du et ou pour par avec sur vos votre nos notre der die das ein eine und oder fur
        mit auf ihr ihre unser unsere il gli di della per do da dos das um uma os as no na seu sua seus suas
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::plain).toSet()

    /** A quantity: "1 L", "500g", "6 x 330 ml", "12 pzas", "2 кг", "1 ლ". */
    private val quantity = Regex(
        "\\d+(?:[.,]\\d+)?\\s?(?:x\\s?\\d+(?:[.,]\\d+)?\\s?)?" +
            "(?:g|gr|grs|kg|mg|ml|cl|dl|l|lt|lts|ltr|oz|fl\\.? ?oz|lb|lbs|pz|pza|pzas|pieza|piezas|pc|pcs|uds?|unidades|und|ct|count|pack|pk|" +
            "rollos|hojas|latas|sobres|г|гр|кг|мл|л|шт|გ|გრ|კგ|მლ|ლ|ც|ცალი|克|千克|毫升|升|个|個|枚|本|개|กรัม|มล)(?![\\p{L}])" +
            "|\\d+\\s?[x×]\\s?\\d+",
        RegexOption.IGNORE_CASE,
    )

    /** Share of [text]'s words that are marketing words, 0–1. */
    fun share(text: String): Double {
        val words = Lexicon.canonicalized(text).lowercase().split(Regex("[^\\p{L}]+"))
            .map(::plain).filter { it.length >= 2 && it !in filler }
        if (words.isEmpty()) return 0.0
        return words.count { it in marketing || it in Lexicon.promoWords || it in Lexicon.boilerplate }.toDouble() / words.size
    }

    fun hasQuantity(text: String): Boolean = quantity.containsMatchIn(Digits.normalize(text))

    /** Mostly marketing and no quantity: a banner or a slogan, not a product's name. */
    fun isAdvertising(text: String): Boolean = share(text) > 0.5 && !hasQuantity(text)
}
