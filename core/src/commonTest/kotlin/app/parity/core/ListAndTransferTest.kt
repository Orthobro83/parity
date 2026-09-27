package app.parity.core

import app.parity.core.csv.Csv
import app.parity.core.list.Category
import app.parity.core.list.CategoryClassifier
import app.parity.core.list.ListMatcher
import app.parity.core.list.ShoppingListText
import app.parity.core.transfer.Base45
import app.parity.core.transfer.Crc32
import app.parity.core.transfer.PassphraseBox
import app.parity.core.transfer.QrReassembler
import app.parity.core.transfer.QrTransfer
import app.parity.core.transfer.Sha256
import app.parity.core.transfer.toHex
import app.parity.core.transfer.unzip
import app.parity.core.transfer.zip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShoppingListTest {
    @Test
    fun splitsTheDesignExample() {
        assertEquals(
            listOf("chicken", "ground beef", "coffee", "creamer", "chocolate", "yogurt", "cheese"),
            ShoppingListText.parse("chicken, ground beef, coffee, creamer, chocolate, yogurt, and cheese"),
        )
        assertEquals(listOf("eggs", "milk", "bread"), ShoppingListText.parse("- eggs\n- milk\n1. bread\neggs"))
    }

    @Test
    fun classifiesTheDesignExample() {
        val expected = mapOf(
            "chicken" to Category.MEAT_FISH, "ground beef" to Category.MEAT_FISH,
            "coffee" to Category.BEVERAGES, "creamer" to Category.BEVERAGES,
            "chocolate" to Category.DESSERTS_SNACKS, "yogurt" to Category.DAIRY_EGGS, "cheese" to Category.DAIRY_EGGS,
        )
        expected.forEach { (item, category) -> assertEquals(category, CategoryClassifier.classify(item), item) }
    }

    @Test
    fun phrasesBeatSingleWords() {
        assertEquals(Category.PANTRY, CategoryClassifier.classify("peanut butter"))
        assertEquals(Category.DESSERTS_SNACKS, CategoryClassifier.classify("chocolate chip cookies"))
        assertEquals(Category.FROZEN, CategoryClassifier.classify("frozen peas"))
        assertEquals(Category.PRODUCE, CategoryClassifier.classify("Tomatoes"))
        assertEquals(Category.PANTRY, CategoryClassifier.classify("black pepper"))
        assertEquals(Category.OTHER, CategoryClassifier.classify("phone charger"))
    }

    @Test
    fun matchesPurchasesToListItems() {
        assertEquals(1.0, ListMatcher.score("coffee", "Jacobs Monarch Coffee 95g"))
        assertTrue(ListMatcher.score("yogurt", "Greek yoghurt 2%") >= ListMatcher.THRESHOLD)
        assertTrue(ListMatcher.score("cheese", "Sour cream 20%") < ListMatcher.THRESHOLD)
        assertEquals("eggs", ListMatcher.bestMatch(listOf("coffee", "eggs"), { it }, listOf("Free range eggs x10")))
    }
}

class TransferTest {
    @Test
    fun checksumsMatchKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".encodeToByteArray()).toHex())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest(ByteArray(0)).toHex())
        val long = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", Sha256.digest(long).toHex())
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
    }

    @Test
    fun base45RoundTripsAndMatchesRfc() {
        assertEquals("BB8", Base45.encode("AB".encodeToByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".encodeToByteArray()))
        val bytes = Random(7).nextBytes(1001)
        assertContentEquals(bytes, Base45.decode(Base45.encode(bytes)))
        assertNull(Base45.decode("GGW"))
        assertNull(Base45.decode("abc"))
    }

    @Test
    fun smallPayloadIsASingleOneOfOneCode() {
        val codes = QrTransfer.encode("""{"type":"list","items":["milk"]}""".encodeToByteArray())
        assertEquals(1, codes.size)
        val frame = QrTransfer.decodeFrame(codes[0])!!
        assertEquals(1, frame.index)
        assertEquals(1, frame.total)
        val event = QrReassembler().accept(codes[0])
        assertIs<QrReassembler.Event.Complete>(event)
    }

    @Test
    fun largePayloadReassemblesOutOfOrderWithRepeats() {
        val payload = Random(42).nextBytes(5_000) // incompressible → 8 frames
        val codes = QrTransfer.encode(payload)
        assertEquals(8, codes.size)
        val r = QrReassembler()
        // First loop misses frames 3 and 6; the second loop fills them.
        val order = listOf(1, 2, 4, 5, 7, 8, 1, 2, 3, 4, 5, 6)
        var last: QrReassembler.Event? = null
        for (i in order) {
            last = r.accept(codes[i - 1])
            if (last is QrReassembler.Event.Complete) break
        }
        val complete = assertIs<QrReassembler.Event.Complete>(last)
        assertContentEquals(payload, complete.payload)
    }

    @Test
    fun compressiblePayloadIsDeflated() {
        val text = "coffee, milk, cheese, bread, ".repeat(200).encodeToByteArray()
        val codes = QrTransfer.encode(text)
        assertEquals(1, codes.size)
        val done = assertIs<QrReassembler.Event.Complete>(QrReassembler().accept(codes.single()))
        assertContentEquals(text, done.payload)
    }

    @Test
    fun rejectsDamagedAndForeignCodes() {
        val code = QrTransfer.encode("hello".encodeToByteArray()).single()
        val damaged = code.replaceRange(40, 41, if (code[40] == 'A') "B" else "A")
        assertNull(QrTransfer.decodeFrame(damaged))
        assertNull(QrTransfer.decodeFrame("https://example.com"))
        val r = QrReassembler()
        val a = QrTransfer.encode(Random(1).nextBytes(2000))
        val b = QrTransfer.encode(Random(2).nextBytes(2000))
        assertIs<QrReassembler.Event.Progress>(r.accept(a[0]))
        assertIs<QrReassembler.Event.DifferentTransfer>(r.accept(b[0]))
        assertIs<QrReassembler.Event.Ignored>(r.accept(a[0]))
    }
}

class CsvTest {
    @Test
    fun roundTripsTrickyFields() {
        val rows = listOf(listOf("1", "Sour cream, 20%", "ფასი \"ახალი\"", null), listOf("2", "line\nbreak", " padded ", "x"))
        val text = Csv.write(listOf("id", "name", "note", "extra"), rows)
        val table = Csv.readTable(text)
        assertEquals(2, table.size)
        assertEquals("Sour cream, 20%", table[0]["name"])
        assertEquals("ფასი \"ახალი\"", table[0]["note"])
        assertEquals("", table[0]["extra"])
        assertEquals("line\nbreak", table[1]["name"])
        assertEquals(" padded ", table[1]["note"])
    }

    @Test
    fun zipRoundTrips() {
        val archive = zip(mapOf("a.csv" to "x,y\r\n".encodeToByteArray(), "manifest.json" to "{}".encodeToByteArray()))
        val files = unzip(archive)
        assertEquals(setOf("a.csv", "manifest.json"), files.keys)
        assertEquals("{}", files.getValue("manifest.json").decodeToString())
    }
}

class PassphraseBoxTest {
    @Test
    fun sealsAndOpensWithTheRightCodeOnly() {
        val code = PassphraseBox.newCode()
        assertTrue(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(code), code)
        val secret = "all my shopping data".encodeToByteArray()
        val sealed = PassphraseBox.seal(secret, code)
        assertContentEquals(secret, PassphraseBox.open(sealed, code.lowercase().replace("-", " ")))
        assertNull(PassphraseBox.open(sealed, "0000-0000-0000"))
    }
}
