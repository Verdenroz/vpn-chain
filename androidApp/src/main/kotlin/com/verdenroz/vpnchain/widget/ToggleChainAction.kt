package com.verdenroz.vpnchain.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.verdenroz.vpnchain.control.ChainQuickControl

/**
 * Background half of the widget toggle. Widgets pre-route consent/no-profile
 * taps through `actionStartActivity` (PendingIntent, exempt from
 * background-activity-launch limits), so the OpenApp branch here is only a
 * best-effort fallback for races between composition and tap.
 */
class ToggleChainAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (val outcome = ChainQuickControl.toggle(context)) {
            is ChainQuickControl.Outcome.Handled -> Unit
            is ChainQuickControl.Outcome.OpenApp -> context.startActivity(outcome.intent)
        }
    }
}
