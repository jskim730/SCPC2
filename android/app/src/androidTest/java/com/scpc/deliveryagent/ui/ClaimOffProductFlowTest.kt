package com.scpc.deliveryagent.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scpc.deliveryagent.platform.Arm
import com.scpc.deliveryagent.platform.Production
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device regression for the exact product-screen claim-off selection path. */
@RunWith(AndroidJUnit4::class)
class ClaimOffProductFlowTest {

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    @Before
    fun cleanState() {
        Production.resetAll(context)
        Production.selectArm(context, Arm.FULL)
    }

    @After
    fun restoreDefaultArm() {
        Production.resetAll(context)
        Production.selectArm(context, Arm.FULL)
    }

    @Test
    fun switchingInComparisonThenChoosingRestaurantAndMenuWorks() {
        val main = launch(MainActivity::class.java)
        val comparison = launch(ComparisonActivity::class.java)

        click(comparison, description = "claim-off 로 실행 (mechanism만 끔)")
        instrumentation.runOnMainSync { comparison.finish() }
        instrumentation.waitForIdleSync()

        assertEquals(Arm.CLAIM_OFF, Production.selectedArm(context))

        val restaurant = Production.catalog(context).restaurants.first()
        click(main, description = restaurant.name)
        instrumentation.waitForIdleSync()

        val add = find(main.window.decorView) { view ->
            view is Button && view.text.toString() == "담기" && view.isEnabled
        }
        assertNotNull("claim-off product screen must offer an in-stock menu", add)
        instrumentation.runOnMainSync { add!!.performClick() }
        instrumentation.waitForIdleSync()

        assertEquals(1, Production.surface(context, Arm.CLAIM_OFF).lines().size)
        assertTrue(
            "the full arm must remain untouched while claim-off is exercised",
            Production.surface(context, Arm.FULL).lines().isEmpty(),
        )

        instrumentation.runOnMainSync { main.finish() }
    }

    @Test
    fun claimOffCanStartFromTheTypedSoupRecommendation() {
        Production.selectArm(context, Arm.CLAIM_OFF)
        val main = launch(MainActivity::class.java)

        val input = find(main.window.decorView) { it is EditText } as EditText?
        assertNotNull("the product message field must be present", input)
        instrumentation.runOnMainSync { input!!.setText("1만5천원 이하로 따뜻한 국물") }
        click(main, description = "보내기")

        val start = find(main.window.decorView) { view ->
            view is Button && view.text.toString() == "여기서 시작" && view.isEnabled
        }
        assertNotNull("the typed request must offer an orderable restaurant-menu pair", start)
        instrumentation.runOnMainSync { start!!.performClick() }
        instrumentation.waitForIdleSync()

        assertEquals(1, Production.surface(context, Arm.CLAIM_OFF).lines().size)
        assertTrue(Production.surface(context, Arm.FULL).lines().isEmpty())

        instrumentation.runOnMainSync { main.finish() }
    }

    private fun <T : Activity> launch(type: Class<T>): T {
        @Suppress("DEPRECATION")
        return instrumentation.startActivitySync(
            Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as T
    }

    private fun click(activity: Activity, description: String) {
        val target = find(activity.window.decorView) { view ->
            view.contentDescription?.toString() == description
        }
        assertNotNull("missing control: $description", target)
        instrumentation.runOnMainSync { target!!.performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun find(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                find(root.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }
}
