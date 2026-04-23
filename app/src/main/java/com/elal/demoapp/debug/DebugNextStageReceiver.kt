// TODO: DEBUG - Delete this entire file before release
package com.elal.demoapp.debug

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.sample.SampleFlightData

/**
 * TODO: DEBUG - Remove before release.
 *
 * Manifest-registered receiver that advances the notification to the next journey stage
 * when the "→ Next Stage" action button is tapped, without opening the app.
 *
 * To remove:
 *  1. Delete this file
 *  2. Remove <receiver> block from AndroidManifest.xml
 *  3. Remove DebugNextStageReceiver calls from JourneyViewModel
 *  4. Remove LiveUpdateManager.debugNextStageIntent field from LiveUpdateManager
 */
class DebugNextStageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sequence = SampleFlightData.journeySimulationSequence
        currentIndex = (currentIndex + 1).coerceAtMost(sequence.size - 1)
        LiveUpdateManager.updateJourney(context.applicationContext, sequence[currentIndex])
    }

    companion object {
        private const val ACTION = "com.elal.demoapp.debug.NEXT_STAGE"

        var currentIndex = 0

        fun setup(context: Context) {
            currentIndex = 0
            LiveUpdateManager.debugNextStageIntent = createPendingIntent(context) // TODO: for DEBUG only
        }

        fun syncIndex(index: Int) {
            currentIndex = index
        }

        fun teardown() {
            LiveUpdateManager.debugNextStageIntent = null // TODO: for DEBUG only
        }

        private fun createPendingIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION).setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
