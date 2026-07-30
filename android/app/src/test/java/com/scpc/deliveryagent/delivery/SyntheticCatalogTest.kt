package com.scpc.deliveryagent.delivery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the synthetic catalog that actually ships.
 *
 * The test reads the same `assets/synthetic/catalog.json` bytes the APK carries,
 * so the invariants are asserted on the shipped data, not on a fixture written
 * for the test.
 */
class SyntheticCatalogTest {

    private val assetFile = File("src/main/assets/synthetic/catalog.json")

    private fun catalog(): SyntheticCatalog =
        SyntheticCatalog.parse(assetFile.readText(Charsets.UTF_8))

    @Test
    fun `the shipped catalog parses and passes its own invariants`() {
        assertTrue("catalog asset is missing at ${assetFile.absolutePath}", assetFile.isFile)
        val catalog = catalog()
        assertEquals("KRW_SYNTHETIC", catalog.currency)
        assertTrue(catalog.restaurants.size >= 3)
        assertTrue(catalog.events.isNotEmpty())
        assertEquals(64, catalog.snapshotDigest.length)
    }

    @Test
    fun `the same bytes always give the same snapshot digest`() {
        assertEquals(catalog().snapshotDigest, catalog().snapshotDigest)
    }

    @Test
    fun `no visible label looks like personal data`() {
        val catalog = catalog()
        val labels = catalog.restaurants.map { it.name } +
            catalog.restaurants.flatMap { it.menu }.map { it.label } +
            catalog.slots.flatMap { it.values }.map { it.label } +
            catalog.slots.map { it.label } +
            catalog.deliveryAlias
        val phoneLike = Regex("""\d{2,4}-\d{3,4}-\d{4}""")
        labels.forEach { label ->
            assertFalse("'$label' contains an address or account marker", label.contains("@"))
            assertFalse("'$label' looks like a phone number", phoneLike.containsMatchIn(label))
        }
    }

    @Test
    fun `every restaurant is marked as synthetic and offers a priced menu`() {
        catalog().restaurants.forEach { restaurant ->
            assertTrue(
                "${restaurant.name} must be recognisable as synthetic",
                restaurant.name.contains("실험"),
            )
            assertTrue(restaurant.menu.isNotEmpty())
            restaurant.menu.forEach { item ->
                assertTrue("${item.token} needs a positive synthetic price", item.priceDelta > 0)
                assertNotNull("${item.token} needs a synthetic estimate", item.etaMinutes)
            }
        }
    }

    @Test
    fun `every declared catalog change points at a real restaurant slot and value`() {
        val catalog = catalog()
        catalog.events.forEach { change ->
            val restaurant = catalog.restaurant(change.entityToken)
            assertNotNull("${change.eventToken} restaurant", restaurant)
            assertTrue(
                "${change.eventToken} targets a slot the restaurant does not offer",
                restaurant!!.slotTokens.contains(change.scopeToken),
            )
            assertNotNull(
                "${change.eventToken} value is not in the catalog",
                catalog.value(change.valueToken),
            )
        }
    }

    @Test
    fun `the declared out of stock value is actually out of stock`() {
        val catalog = catalog()
        val soldOut = catalog.value("side.dumpling.soldout")
        assertNotNull(soldOut)
        assertFalse(soldOut!!.inStock)
        assertTrue(catalog.valueLabel("side.dumpling.soldout").contains("품절"))
        assertTrue(catalog.value("side.dumpling")!!.inStock)
    }

    @Test
    fun `a malformed catalog is rejected rather than partly loaded`() {
        val cases = mapOf(
            "wrong kind" to """{"schema_version":1,"artifact_kind":"nope"}""",
            "phone-like label" to badLabel("연락 02-1234-5678"),
            "non synthetic restaurant name" to badRestaurantName("우리동네 국밥"),
        )
        cases.forEach { (name, text) ->
            val failed = try {
                SyntheticCatalog.parse(text)
                false
            } catch (expected: Exception) {
                true
            }
            assertTrue("$name should have been rejected", failed)
        }
    }

    private fun badLabel(label: String): String = assetFile.readText(Charsets.UTF_8)
        .replace("\"label\": \"순한맛\"", "\"label\": \"$label\"")

    private fun badRestaurantName(name: String): String = assetFile.readText(Charsets.UTF_8)
        .replace("\"name\": \"다온국밥 실험점\"", "\"name\": \"$name\"")
}
