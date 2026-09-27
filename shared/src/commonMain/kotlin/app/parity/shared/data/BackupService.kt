package app.parity.shared.data

import app.parity.core.csv.Csv
import app.parity.core.money.MoneyMath
import app.parity.core.money.decimal
import app.parity.core.money.toPlain
import app.parity.core.transfer.unzip
import app.parity.core.transfer.zip
import app.parity.shared.util.now
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Maps one table to and from CSV using the entity's serializer (design §15). */
private class TableCodec<T>(val file: String, val serializer: KSerializer<T>) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun toCsv(rows: List<T>): String {
        val d = serializer.descriptor
        val header = (0 until d.elementsCount).map { d.getElementName(it) }
        val data = rows.map { row ->
            val obj = json.encodeToJsonElement(serializer, row).jsonObject
            header.map { key -> obj[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content }
        }
        return Csv.write(header, data)
    }

    fun fromCsv(text: String): List<T> {
        val d = serializer.descriptor
        return Csv.readTable(text).map { row ->
            val fields = mutableMapOf<String, JsonElement>()
            for (i in 0 until d.elementsCount) {
                val name = d.getElementName(i)
                val element = d.getElementDescriptor(i)
                val raw = row[name]
                fields[name] = when {
                    raw == null || (raw.isEmpty() && element.isNullable) -> if (element.isNullable) JsonNull else continue
                    else -> when (element.kind) {
                        PrimitiveKind.BOOLEAN -> JsonPrimitive(raw.toBooleanStrict())
                        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> JsonPrimitive(raw.toLong())
                        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> JsonPrimitive(raw.toDouble())
                        else -> JsonPrimitive(raw)
                    }
                }
            }
            json.decodeFromJsonElement(serializer, JsonObject(fields))
        }
    }
}

data class BackupContents(
    val settings: List<SettingsEntity>,
    val stores: List<StoreEntity>,
    val products: List<ProductEntity>,
    val observations: List<PriceObservationEntity>,
    val sessions: List<ShoppingSessionEntity>,
    val purchaseLines: List<PurchaseLineEntity>,
    val lists: List<ShoppingListEntity>,
    val listItems: List<ShoppingListItemEntity>,
    val overrides: List<CategoryOverrideEntity>,
) {
    val summary: String
        get() = "${observations.size} price observations, ${sessions.size} shopping sessions, " +
            "${products.size} products, ${lists.size} lists"
}

enum class RestoreMode { REPLACE_ALL, MERGE }

class BackupException(message: String) : Exception(message)

/** ZIP-of-CSVs export and restore (design §15), also used for QR backups (design §8.5). */
class BackupService(private val db: ParityDatabase, private val appVersion: String) {
    private val settingsCodec = TableCodec("settings.csv", SettingsEntity.serializer())
    private val storeCodec = TableCodec("stores.csv", StoreEntity.serializer())
    private val productCodec = TableCodec("products.csv", ProductEntity.serializer())
    private val observationCodec = TableCodec("price_observations.csv", PriceObservationEntity.serializer())
    private val sessionCodec = TableCodec("shopping_sessions.csv", ShoppingSessionEntity.serializer())
    private val lineCodec = TableCodec("purchase_lines.csv", PurchaseLineEntity.serializer())
    private val listCodec = TableCodec("shopping_lists.csv", ShoppingListEntity.serializer())
    private val itemCodec = TableCodec("shopping_list_items.csv", ShoppingListItemEntity.serializer())
    private val overrideCodec = TableCodec("category_overrides.csv", CategoryOverrideEntity.serializer())

    /** All tables as CSV text keyed by file name, plus a manifest and the flat purchases.csv. */
    suspend fun exportFiles(): Map<String, String> {
        val settings = db.settings().get()?.copy(rateApiKey = null) // never export secrets
        val stores = db.stores().all()
        val products = db.products().all()
        val sessions = db.sessions().all()
        val lines = db.sessions().allLines()
        val manifest = buildJsonObject {
            put("app", "Parity")
            put("schemaVersion", 1)
            put("appVersion", appVersion)
            put("exportedAt", Instant.fromEpochMilliseconds(now()).toString())
        }
        return linkedMapOf(
            "manifest.json" to manifest.toString(),
            settingsCodec.file to settingsCodec.toCsv(listOfNotNull(settings)),
            storeCodec.file to storeCodec.toCsv(stores),
            productCodec.file to productCodec.toCsv(products),
            observationCodec.file to observationCodec.toCsv(db.observations().all()),
            sessionCodec.file to sessionCodec.toCsv(sessions),
            lineCodec.file to lineCodec.toCsv(lines),
            listCodec.file to listCodec.toCsv(db.lists().allLists()),
            itemCodec.file to itemCodec.toCsv(db.lists().allItems()),
            overrideCodec.file to overrideCodec.toCsv(db.overrides().all()),
            "purchases.csv" to flatPurchases(sessions, lines, stores, products),
        )
    }

    suspend fun exportZip(): ByteArray = zip(exportFiles().mapValues { it.value.encodeToByteArray() })

    fun readZip(bytes: ByteArray): BackupContents {
        val files = runCatching { unzip(bytes) }.getOrElse { throw BackupException("This file is not a Parity backup ZIP.") }
        return read(files.mapValues { it.value.decodeToString() })
    }

    fun read(files: Map<String, String>): BackupContents {
        val manifest = files["manifest.json"] ?: throw BackupException("The backup has no manifest.json.")
        val parsed = runCatching { Json.parseToJsonElement(manifest).jsonObject }.getOrNull()
        if (parsed?.get("app")?.jsonPrimitive?.content != "Parity") throw BackupException("The manifest is not from Parity.")
        val version = parsed["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (version > 1) throw BackupException("This backup is from a newer version of Parity.")
        fun <T> table(codec: TableCodec<T>): List<T> = files[codec.file]?.let {
            runCatching { codec.fromCsv(it) }.getOrElse { e -> throw BackupException("${codec.file} could not be read: ${e.message}") }
        } ?: emptyList()
        return BackupContents(
            table(settingsCodec), table(storeCodec), table(productCodec), table(observationCodec),
            table(sessionCodec), table(lineCodec), table(listCodec), table(itemCodec), table(overrideCodec),
        )
    }

    /** Applies a backup in a single transaction; any failure rolls everything back. */
    suspend fun restore(contents: BackupContents, mode: RestoreMode) {
        db.useWriterConnectionTransaction {
            if (mode == RestoreMode.REPLACE_ALL) {
                db.cart().deleteAll()
                db.sessions().deleteAllLines()
                db.sessions().deleteAll()
                db.observations().deleteAll()
                db.products().deleteAll()
                db.stores().deleteAll()
                db.lists().deleteAllItems()
                db.lists().deleteAllLists()
                db.overrides().deleteAll()
                contents.settings.firstOrNull()?.let { restored ->
                    val key = db.settings().get()?.rateApiKey
                    db.settings().upsert(restored.copy(rateApiKey = key))
                }
            }
            db.stores().upsertAll(contents.stores)
            db.products().upsertAll(contents.products)
            db.observations().upsertAll(contents.observations)
            db.sessions().upsertAll(contents.sessions)
            db.sessions().upsertLines(contents.purchaseLines)
            db.lists().upsertLists(contents.lists)
            db.lists().upsertItems(contents.listItems)
            db.overrides().upsertAll(contents.overrides)
        }
    }

    /** One human-readable row per purchased item, for spreadsheets. */
    private fun flatPurchases(
        sessions: List<ShoppingSessionEntity>,
        lines: List<PurchaseLineEntity>,
        stores: List<StoreEntity>,
        products: List<ProductEntity>,
    ): String {
        val sessionById = sessions.associateBy { it.id }
        val storeById = stores.associateBy { it.id }
        val productById = products.associateBy { it.id }
        val tz = TimeZone.currentSystemDefault()
        val header = listOf(
            "date", "store", "product", "original_name", "quantity", "unit_price_base", "base_currency",
            "unit_price_local", "local_currency", "line_total_base", "line_total_local", "fx_rate", "sale",
        )
        val rows = lines.sortedBy { sessionById[it.sessionId]?.finalizedAt ?: 0 }.map { line ->
            val session = sessionById[line.sessionId]
            val qty = decimal(line.quantity)
            val unitLocal = decimal(line.unitPriceLocal)
            val unitBase = line.unitPriceBase?.let(::decimal)
            listOf(
                session?.let { Instant.fromEpochMilliseconds(it.finalizedAt).toLocalDateTime(tz).toString() },
                session?.storeId?.let { storeById[it]?.name },
                productById[line.productId]?.displayName ?: line.nameTranslatedAtPurchase,
                line.nameAtPurchase,
                line.quantity,
                unitBase?.toPlain(),
                line.baseCurrency,
                line.unitPriceLocal,
                line.localCurrency,
                unitBase?.multiply(qty, MoneyMath)?.toPlain(),
                unitLocal.multiply(qty, MoneyMath).toPlain(),
                line.fxRate,
                if (line.isPromo) "yes" else "no",
            )
        }
        return Csv.write(header, rows)
    }
}
