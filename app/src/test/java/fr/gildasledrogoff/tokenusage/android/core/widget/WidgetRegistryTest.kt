package fr.gildasledrogoff.tokenusage.core.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class WidgetRegistryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun bind(widgetId: Int, receiver: Class<*>) {
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, receiver) }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
    }

    private fun resolved(widgetId: Int): Class<out GlanceAppWidget> =
        WidgetRegistry.resolve(context, widgetId)::class.java

    @Test
    fun `revision key has a stable name because it lives in persisted widget state`() {
        assertEquals("revision", WidgetRegistry.REVISION_KEY.name)
    }

    @Test
    fun `an unknown widget id falls back to the main quota widget`() {
        assertEquals(QuotaGlanceWidget::class.java, resolved(999))
    }

    @Test
    fun `the main receiver resolves to the main quota widget`() {
        bind(1, QuotaWidgetReceiver::class.java)

        assertEquals(QuotaGlanceWidget::class.java, resolved(1))
    }

    @Test
    fun `the compact receiver resolves to the compact widget`() {
        bind(2, QuotaCompactWidgetReceiver::class.java)

        assertEquals(QuotaCompactGlanceWidget::class.java, resolved(2))
    }

    @Test
    fun `the reset receiver resolves to the reset widget`() {
        bind(3, ResetWidgetReceiver::class.java)

        assertEquals(ResetGlanceWidget::class.java, resolved(3))
    }

    @Test
    fun `the balances receiver resolves to the balances widget`() {
        bind(4, BalancesWidgetReceiver::class.java)

        assertEquals(BalancesGlanceWidget::class.java, resolved(4))
    }

    @Test
    fun `each resolve call returns a fresh widget instance`() {
        bind(5, ResetWidgetReceiver::class.java)

        assertTrue(WidgetRegistry.resolve(context, 5) !== WidgetRegistry.resolve(context, 5))
    }

    @Test
    fun `receivers expose the matching glance widget`() {
        assertEquals(QuotaCompactGlanceWidget::class.java, QuotaCompactWidgetReceiver().glanceAppWidget::class.java)
        assertEquals(ResetGlanceWidget::class.java, ResetWidgetReceiver().glanceAppWidget::class.java)
        assertEquals(BalancesGlanceWidget::class.java, BalancesWidgetReceiver().glanceAppWidget::class.java)
    }

    @Test
    fun `all token usage widgets persist their state as preferences`() {
        listOf(
            QuotaCompactGlanceWidget(),
            ResetGlanceWidget(),
            BalancesGlanceWidget()
        ).forEach { assertSame(PreferencesGlanceStateDefinition, it.stateDefinition) }
    }
}
