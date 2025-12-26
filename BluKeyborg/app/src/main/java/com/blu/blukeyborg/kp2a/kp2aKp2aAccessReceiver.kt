package com.blu.blukeyborg.kp2a

import keepass2android.pluginsdk.PluginAccessBroadcastReceiver
import keepass2android.pluginsdk.Strings

import android.content.Intent
import android.util.Log

class Kp2aAccessReceiver : PluginAccessBroadcastReceiver() {

    override fun getScopes(): ArrayList<String> {
        android.util.Log.d(
            "KP2A-BluKeyborg",
            "AccessReceiver.getScopes() called"
        )
        return arrayListOf(keepass2android.pluginsdk.Strings.SCOPE_CURRENT_ENTRY)
    }

}
