package com.strategy.booster.notif

interface DownloadControl {
    fun pause(id: Long)
    fun resume(id: Long)
    fun exit(id: Long)
}

object DownloadControlBridge {
    @Volatile var delegate: DownloadControl? = null
}
