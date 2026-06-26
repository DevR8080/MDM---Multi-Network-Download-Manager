package com.strategy.booster.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.strategy.booster.MainActivity

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadNotifConst.EXTRA_ID, -1L)
        val d = DownloadControlBridge.delegate
        if (id < 0 || d == null) {
            Toast.makeText(context, "Action unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        when (intent.action) {
            DownloadNotifConst.ACTION_PAUSE -> d.pause(id)
            DownloadNotifConst.ACTION_RESUME -> d.resume(id)
            DownloadNotifConst.ACTION_EXIT -> {
                DownloadNotifier.cancel(context, id)

                val i = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("EXIT_APP", true)
                }
                context.startActivity(i)
            }
        }

    }
}
