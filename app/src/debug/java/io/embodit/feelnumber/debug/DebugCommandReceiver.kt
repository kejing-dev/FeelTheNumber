package io.embodit.feelnumber.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.feelnumber.platform.FeelLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.feelnumber.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s emulator-5568 shell am broadcast -a io.embodit.feelnumber.DEBUG -p io.embodit.feelnumber --es cmd guess --ef size 0.65
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        FeelLog.i("CMD recv '$cmd' extras=${intent.extras?.keySet()?.joinToString(",")}")
        DebugCommands.run(context.applicationContext, cmd, intent)
    }
}
