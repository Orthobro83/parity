package app.parity.core.scan

import app.parity.core.money.CurrencyCode
import app.parity.core.money.MoneyFormat
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.core.money.divideMoney
import app.parity.core.money.parseDecimal
import app.parity.core.money.roundTo
import app.parity.core.money.toPlain
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A multi-buy deal printed on a shelf tag: "from 3, 7.99 each", "3 for 20", "1+1", "2nd at 50 % off".
 * The single price on the tag stays the regular price; the deal only changes what a qualifying
 * quantity costs.
 */
@Serializable
data class MultiBuyOffer(
    val kind: Kind,
    /** Units the deal needs (the bundle size). */
    val quantity: Int,
    /** EACH: price per unit; BUNDLE: total for [quantity] units. Plain decimal string. */
    val price: String? = null,
    /** FREE: units paid for out of [quantity] ("2+1" → quantity 3, paid 2). */
    val paidUnits: Int? = null,
    /** SECOND_UNIT: percentage off the second unit of a pair. */
    val percentOff: Int? = null,
    /**
     * False when the tag shows only the deal ("buy 3 for ₾11.20"): the single price is unknown and
     * the deal's unit price stands in for it ([unitStandIn]).
     */
    val singlePriceShown: Boolean = true,
) {
    enum class Kind { EACH, BUNDLE, FREE, SECOND_UNIT }

    private val priceValue: BigDecimal? get() = price?.let(::parseDecimal)

    /** What [units] cost with the deal applied; units outside a full bundle pay the regular price. */
    fun total(units: Int, regular: BigDecimal): BigDecimal {
        val q = units.coerceAtLeast(0)
        val bundles = q / quantity
        val rest = q % quantity
        val restCost = regular.multiply(BigDecimal.fromInt(rest), MoneyMath)
        return when (kind) {
            Kind.EACH -> if (q >= quantity) priceValue!!.multiply(BigDecimal.fromInt(q), MoneyMath) else regular.multiply(BigDecimal.fromInt(q), MoneyMath)
            Kind.BUNDLE -> priceValue!!.multiply(BigDecimal.fromInt(bundles), MoneyMath) + restCost
            Kind.FREE -> regular.multiply(BigDecimal.fromInt(bundles * (paidUnits ?: quantity) + rest), MoneyMath)
            Kind.SECOND_UNIT -> {
                val pairCost = regular.multiply(decimal(200 - (percentOff ?: 0)), MoneyMath).divideMoney(decimal(100))
                pairCost.multiply(BigDecimal.fromInt(bundles), MoneyMath) + restCost
            }
        }
    }

    /** Effective price per unit when buying exactly the deal quantity. */
    fun dealUnitPrice(regular: BigDecimal): BigDecimal = total(quantity, regular).divideMoney(BigDecimal.fromInt(quantity))

    /** True when [units] get at least one discounted bundle. */
    fun appliesTo(units: Int): Boolean = units >= quantity

    /** The deal's price per unit, standing in for the single price on a deal-only tag. */
    fun unitStandIn(decimals: Int): BigDecimal = when (kind) {
        Kind.BUNDLE -> priceValue!!.divideMoney(BigDecimal.fromInt(quantity)).roundTo(decimals)
        else -> priceValue ?: BigDecimal.ZERO
    }

    /** Units the quantity stepper moves by when the deal is chosen. */
    val step: Int get() = if (kind == Kind.EACH) 1 else quantity

    fun describe(currency: CurrencyCode): String = when (kind) {
        Kind.EACH -> "${quantity}+ at ${MoneyFormat.format(priceValue!!, currency)} each"
        Kind.BUNDLE -> "$quantity for ${MoneyFormat.format(priceValue!!, currency)}"
        Kind.FREE -> {
            val paid = paidUnits ?: quantity
            if (paid == quantity - 1) "Buy $paid, get 1 free" else "$quantity for the price of $paid"
        }
        Kind.SECOND_UNIT -> if (percentOff == 100) "2nd one free" else "2nd at ${percentOff ?: 0}% off"
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): MultiBuyOffer? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        fun each(quantity: Int, unitPrice: BigDecimal) = MultiBuyOffer(Kind.EACH, quantity, price = unitPrice.toPlain())

        fun bundle(quantity: Int, total: BigDecimal) = MultiBuyOffer(Kind.BUNDLE, quantity, price = total.toPlain())

        /** A tag that shows only the deal: [amount] is the bundle total, or the unit price with [each]. */
        fun dealOnly(quantity: Int, amount: BigDecimal, each: Boolean) =
            (if (each) each(quantity, amount) else bundle(quantity, amount)).copy(singlePriceShown = false)

        /**
         * "N for X" or "from N, X": X below the regular price is a per-unit price, X between the
         * regular price and N × regular is a bundle total. Anything else isn't a deal.
         */
        fun resolve(quantity: Int, amount: BigDecimal, regular: BigDecimal, hint: Hint = Hint.NONE): MultiBuyOffer? {
            if (quantity < 2 || amount.signum() <= 0 || regular.signum() <= 0) return null
            val full = regular.multiply(BigDecimal.fromInt(quantity), MoneyMath)
            // "3 for 7.99" next to a 9.99 regular price is read as 7.99 each: a 3-for-less-than-one
            // bundle would be an implausible discount. The user can correct it on the card.
            return when {
                amount < regular -> each(quantity, amount)
                amount > regular && amount < full && hint != Hint.EACH -> bundle(quantity, amount)
                else -> null
            }
        }
    }

    enum class Hint { NONE, EACH, TOTAL }
}

/** A deal found on a tag, before it is checked against the regular price. */
internal data class MultiBuyMatch(
    val quantity: Int,
    /** Price printed with the deal (EACH/BUNDLE), or null for FREE / SECOND_UNIT deals. */
    val candidate: PriceCandidate?,
    val hint: MultiBuyOffer.Hint,
    /** A complete offer that needs no price (FREE, SECOND_UNIT). */
    val fixed: MultiBuyOffer?,
    val lines: Set<Int>,
    /**
     * Worded as a purchase ("buy 3", "3 for", "შეიძინეთ 3 ცალი"), not just "3+", which can also be
     * an age rating; only then can a tag without a single price be read as deal-only.
     */
    val explicit: Boolean = false,
)

/** Finds multi-buy wording on a tag, in English, Georgian and Russian (plus common EU forms). */
internal object MultiBuyDetector {
    private const val N = "(\\d{1,2})"

    // Deals that need no price: "1+1", "2+1", "buy 2 get 1 free", "3 for 2", "2 по цене 1", "3x2".
    private val plusFree = Regex("(?<![\\d.,])([1-5])\\s?\\+\\s?([1-3])(?![\\d.,%])")
    private val buyGetFree = Regex("buy\\s$N\\s?,?\\s?get\\s$N\\s?free")
    // "ซื้อ 2 แถม 1" (Thai), "اشتر 1 واحصل على 1" (Arabic): buy N, get M free.
    private val buyGetFreeLocal = Regex("(?:ซื้อ|اشتر)\\s?$N\\s?(?:แถม|و?\\s?احصل\\s?على)\\s?$N")

    /** Chinese deals written in words: 买一送一 (buy one get one), 第二件半价 (second at half price). */
    private val chineseDeals = mapOf(
        "买一送一" to MultiBuyOffer(MultiBuyOffer.Kind.FREE, 2, paidUnits = 1),
        "買一送一" to MultiBuyOffer(MultiBuyOffer.Kind.FREE, 2, paidUnits = 1),
        "买二送一" to MultiBuyOffer(MultiBuyOffer.Kind.FREE, 3, paidUnits = 2),
        "買二送一" to MultiBuyOffer(MultiBuyOffer.Kind.FREE, 3, paidUnits = 2),
        "第二件半价" to MultiBuyOffer(MultiBuyOffer.Kind.SECOND_UNIT, 2, percentOff = 50),
        "第二件半價" to MultiBuyOffer(MultiBuyOffer.Kind.SECOND_UNIT, 2, percentOff = 50),
    )
    private val forThePriceOf = Regex("$N\\s?(?:for|pour|für|по цене|за цену|по ціні)\\s?$N(?![\\d.,%])")
    private val takePay = Regex("(?<![\\d.,])([2-6])\\s?[x×]\\s?([1-5])(?![\\d.,%])")

    // Second unit discounted: "2nd at -50%", "-50% on the second", "მე-2 -30%", "второй товар -50%".
    private val secondWords = "2nd|second|მე-?2|მეორე|второй|2-й|2ème|zweite|第二件|2つ目|2点目|두 번째|ชิ้นที่\\s?2|الثاني|השני"
    private val secondUnit = Regex("(?:$secondWords)\\D{0,24}?(\\d{1,3})\\s?%")
    private val percentThenSecond = Regex("(\\d{1,3})\\s?%\\D{0,16}(?:$secondWords)")

    // Quantity with deal wording; the price may be on the same line or an adjacent one.
    // "შეიძინე(თ)" and "იყიდე(თ)" are Georgian for "buy".
    private val explicitQuantity = listOf(
        Regex("(?<!\\p{L})(?:buy|from|ab|от|desde|à partir de|при покупке|შეიძინე(?:თ)?|იყიდე(?:თ)?|ซื้อ|اشتر|よりどり)\\s?$N"),
        // "2点で500円", "3個で": N items for; "2개 이상": N or more; "2 ב-10": 2 for 10 (Hebrew).
        Regex("$N\\s?(?:点|個|コ|本|袋|パック)\\s?で"),
        Regex("$N\\s?개\\s?이상"),
        Regex("(?<![\\d.,])$N\\s?ב-?\\s?(?=₪?\\s?\\d)"),
        Regex("$N\\s?(?:or more|и более|або більше)"),
        Regex("$N\\s?\\S{0,8}\\s?(?:ყიდვისას|შეძენისას|ყიდვის შემთხვევაში)"),
        Regex("$N\\s?(?:for|за|pour|für|por)(?=\\s?[$€£₾]?\\s?\\d)"),
        Regex("(?<![\\d.,])$N\\s?/\\s?(?=[$€£₾]\\s?\\d|\\d+[.,]\\d{2})"),
    )
    private val plusQuantity = Regex("(?<![\\d.,])$N\\s?\\+")

    // Quantity with only a unit word ("3 pcs", "3 ცალი"): a pack size unless a price is on the same line.
    private val weakQuantity = Regex("(?<![\\d.,])$N\\s?(?:pcs|pieces|pc|шт\\.?|ცალ\\p{L}*|stück|st\\.|個|个|件|개|点|ชิ้น|حبات|حبة)")

    private val eachWords = listOf("each", " ea", "je ", "/ც", "₾ ცალ", "за шт", "/шт", "per unit", "por unidad", "l'unité", "по ")
    private val totalWords = listOf(" for ", " за ", " pour ", " für ", " por ", "/")

    /** "₾11.20-ად", "11.20 ლარად": Georgian "for" on the price, so it pays for the whole quantity. */
    private val totalSuffix = Regex("\\d\\s?(?:₾\\s?)?-?\\s?(?:ად|ლარად)(?!\\p{L})")

    fun detect(lines: List<OcrLine>, candidates: List<PriceCandidate>, localDecimals: Int = 2): MultiBuyMatch? {
        for ((index, line) in lines.withIndex()) {
            val lower = " " + line.text.lowercase() + " "

            fixedOffer(lower)?.let { offer -> return MultiBuyMatch(offer.quantity, null, MultiBuyOffer.Hint.NONE, offer, setOf(index)) }

            val explicit = explicitQuantity.firstNotNullOfOrNull { it.find(lower) }
            val strong = explicit ?: plusQuantity.find(lower)
            val weak = if (strong == null) weakQuantity.find(lower) else null
            val quantity = (strong ?: weak)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (quantity !in 2..12) continue

            // A pack size ("3 ცალი") only makes a deal with a real price on its line; a bare number
            // there is more likely a misread letter than a price.
            val sameLine = candidates.filter { onLine(it, line) && it.amount.compareTo(BigDecimal.fromInt(quantity)) != 0 }
            val candidate = sameLine.firstOrNull { strong != null || looksLikePrice(it, localDecimals) }
                ?: if (strong != null) adjacentCandidate(lines, index, candidates, localDecimals) else null
            candidate ?: continue
            val candidateLine = lines.indexOfFirst { onLine(candidate, it) }.takeIf { it >= 0 } ?: index
            val priceText = " " + lines[candidateLine].text.lowercase() + " "
            val context = (lower + priceText).replace(Regex("\\s*/\\s*"), "/")
            val hint = when {
                totalSuffix.containsMatchIn(priceText) -> MultiBuyOffer.Hint.TOTAL
                eachWords.any { context.contains(it) } -> MultiBuyOffer.Hint.EACH
                totalWords.any { lower.contains(it) } -> MultiBuyOffer.Hint.TOTAL
                else -> MultiBuyOffer.Hint.NONE
            }
            return MultiBuyMatch(quantity, candidate, hint, null, setOf(index, candidateLine), explicit = explicit != null)
        }
        return null
    }

    private fun looksLikePrice(c: PriceCandidate, localDecimals: Int): Boolean =
        c.currency != null || c.decimals > 0 || localDecimals == 0

    /** True when a lower-cased line has deal wording ("buy 3", "1+1"); a pack size ("10 ცალი") isn't. */
    fun mentionsDeal(lower: String): Boolean = isDealWording(lower) || plusQuantity.containsMatchIn(lower)

    /** Deal wording that can't be an age rating, as a bare "3+" can: "1+1", "2x1", "buy 3", "3 for $10". */
    fun isDealWording(lower: String): Boolean = fixedOffer(lower) != null || explicitQuantity.any { it.containsMatchIn(lower) }

    private fun fixedOffer(lower: String): MultiBuyOffer? {
        plusFree.find(lower)?.let { m ->
            val paid = m.groupValues[1].toInt()
            val free = m.groupValues[2].toInt()
            return MultiBuyOffer(MultiBuyOffer.Kind.FREE, paid + free, paidUnits = paid)
        }
        chineseDeals.entries.firstOrNull { lower.contains(it.key) }?.let { return it.value }
        (buyGetFree.find(lower) ?: buyGetFreeLocal.find(lower))?.let { m ->
            val paid = m.groupValues[1].toInt()
            val free = m.groupValues[2].toInt()
            if (paid in 1..6 && free in 1..3) return MultiBuyOffer(MultiBuyOffer.Kind.FREE, paid + free, paidUnits = paid)
        }
        (forThePriceOf.find(lower) ?: takePay.find(lower))?.let { m ->
            val take = m.groupValues[1].toInt()
            val pay = m.groupValues[2].toInt()
            if (take in 2..6 && pay in 1 until take) return MultiBuyOffer(MultiBuyOffer.Kind.FREE, take, paidUnits = pay)
        }
        (secondUnit.find(lower) ?: percentThenSecond.find(lower))?.let { m ->
            val pct = m.groupValues[1].toInt()
            if (pct in 1..100) return MultiBuyOffer(MultiBuyOffer.Kind.SECOND_UNIT, 2, percentOff = pct)
        }
        return null
    }

    private fun onLine(candidate: PriceCandidate, line: OcrLine): Boolean {
        val b = candidate.box
        val overlap = minOf(b.bottom, line.box.bottom) - maxOf(b.top, line.box.top)
        return overlap > 0.5f * minOf(b.height, line.box.height) && b.horizontalOverlap(line.box) > 0f
    }

    /** A price on the line just below or above the deal wording. */
    private fun adjacentCandidate(lines: List<OcrLine>, index: Int, candidates: List<PriceCandidate>, localDecimals: Int): PriceCandidate? {
        val line = lines[index]
        return listOfNotNull(lines.getOrNull(index + 1), lines.getOrNull(index - 1))
            .filter { other ->
                val gap = if (other.box.top >= line.box.bottom) other.box.top - line.box.bottom else line.box.top - other.box.bottom
                gap < maxOf(line.box.height, other.box.height) * 1.5f
            }
            .firstNotNullOfOrNull { other -> candidates.firstOrNull { onLine(it, other) && !it.isPerUnit && looksLikePrice(it, localDecimals) } }
    }
}
