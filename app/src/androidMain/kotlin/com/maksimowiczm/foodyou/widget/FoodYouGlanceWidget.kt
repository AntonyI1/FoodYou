package com.maksimowiczm.foodyou.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.maksimowiczm.foodyou.R
import com.maksimowiczm.foodyou.app.infrastructure.android.MainActivity
import com.maksimowiczm.foodyou.app.ui.common.utility.EnergyFormatter
import com.maksimowiczm.foodyou.settings.domain.entity.EnergyFormat
import com.maksimowiczm.foodyou.widget.domain.MeterLabel
import com.maksimowiczm.foodyou.widget.domain.ObserveWidgetSummaryUseCase
import com.maksimowiczm.foodyou.widget.domain.WidgetSummary
import com.maksimowiczm.foodyou.widget.domain.calorieMeter
import com.maksimowiczm.foodyou.widget.domain.displayValue
import com.maksimowiczm.foodyou.widget.domain.macroFraction
import foodyou.app.generated.resources.Res
import foodyou.app.generated.resources.unit_kcal
import foodyou.app.generated.resources.unit_kilojoules
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Today's calories + macros, 4x2. Tap opens the app. */
internal class FoodYouGlanceWidget : GlanceAppWidget(), KoinComponent {

    private val observeWidgetSummaryUseCase: ObserveWidgetSummaryUseCase by inject()

    // One approved size, so the placed size never changes what we draw.
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summary = observeWidgetSummaryUseCase.get()

        // Compose Resources load asynchronously inside a composition, which would risk a blank
        // string on the single frame Glance snapshots. provideGlance is suspend, so resolve here.
        val unitSuffix =
            when ((summary as? WidgetSummary.Day)?.energyFormat) {
                EnergyFormat.Kilojoules -> getString(Res.string.unit_kilojoules)
                else -> getString(Res.string.unit_kcal)
            }

        // Always called, on every path: Glance shows the initial layout only until the first
        // provideContent, so this is what keeps a failed read off the loading layout forever.
        provideContent {
            GlanceTheme {
                Row(
                    modifier =
                        GlanceModifier.fillMaxSize()
                            .background(GlanceTheme.colors.widgetBackground)
                            .padding(12.dp)
                            .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (summary) {
                        is WidgetSummary.Day -> {
                            Apple(summary, unitSuffix)
                            Macros(summary, GlanceModifier.padding(start = 12.dp))
                        }
                        WidgetSummary.Unavailable -> Apple(day = null, unitSuffix = unitSuffix)
                    }
                }
            }
        }
    }

    @Composable
    private fun Apple(day: WidgetSummary.Day?, unitSuffix: String) {
        val context = LocalContext.current
        val meter = day?.let { calorieMeter(it.energy, it.energyGoal) }

        val trackColor = GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb()
        val progressColor =
            when (meter?.label) {
                is MeterLabel.Over -> GlanceTheme.colors.error
                else -> GlanceTheme.colors.primary
            }.getColor(context).toArgb()

        val sizePx =
            remember(context) {
                (APPLE_DP * context.resources.displayMetrics.density)
                    .toInt()
                    .coerceAtMost(AppleMeterRenderer.MAX_SIZE_PX)
            }

        // Renderer failure must never take the widget down; fall back to the number alone.
        val bitmap =
            remember(meter, sizePx, trackColor, progressColor) {
                runCatching {
                        AppleMeterRenderer.render(
                            sizePx = sizePx,
                            strokeFraction = meter?.strokeFraction ?: 0f,
                            trackColor = trackColor,
                            progressColor = progressColor,
                        )
                    }
                    .getOrNull()
            }

        Box(
            modifier = GlanceModifier.size(APPLE_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
            AppleLabel(day, meter?.label, unitSuffix)
        }
    }

    @Composable
    private fun AppleLabel(day: WidgetSummary.Day?, label: MeterLabel?, unitSuffix: String) {
        val formatter =
            when (day?.energyFormat) {
                EnergyFormat.Kilojoules -> EnergyFormatter.kilojoules
                else -> EnergyFormatter.kilocalories
            }

        val big: String
        val small: String
        when (label) {
            null -> {
                big = "—"
                small = ""
            }
            is MeterLabel.Remaining -> {
                big = label.displayValue(formatter).toString()
                small = LocalContext.current.getString(R.string.widget_left)
            }
            is MeterLabel.Over -> {
                big = label.displayValue(formatter).toString()
                small = LocalContext.current.getString(R.string.widget_over)
            }
            // No usable goal: "left" is meaningless, so show the day's total with its unit.
            is MeterLabel.Total -> {
                big = label.displayValue(formatter).toString()
                small = unitSuffix
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = big,
                style =
                    TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
            )
            if (small.isNotEmpty()) {
                Text(
                    text = small,
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }

    @Composable
    private fun Macros(day: WidgetSummary.Day, modifier: GlanceModifier) {
        Column(modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MacroRow("P", day.proteins, day.proteinsGoal)
            MacroRow("C", day.carbohydrates, day.carbohydratesGoal)
            MacroRow("F", day.fats, day.fatsGoal)
        }
    }

    @Composable
    private fun MacroRow(letter: String, value: Int, goal: Int) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = letter,
                    style =
                        TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.onSurfaceVariant,
                        ),
                    modifier = GlanceModifier.width(14.dp),
                )
                Text(
                    text = "$value / $goal g",
                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
            LinearProgressIndicator(
                progress = macroFraction(value, goal),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
        }
    }

    private companion object {
        // A 4x2 is ~250x110dp, so the apple is height-bound.
        const val APPLE_DP = 96f
    }
}
