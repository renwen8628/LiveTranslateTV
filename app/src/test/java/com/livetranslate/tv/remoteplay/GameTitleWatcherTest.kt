package com.livetranslate.tv.remoteplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTitleWatcherTest {

    private val idle = "HTTP/1.1 200 Ok\nhost-id:70662A0F1F57\nhost-type:PS5\nhost-name:PS5-431\n" +
        "host-request-port:997\ndevice-discovery-protocol-version:00030010\nsystem-version:14200007\n"

    @Test
    fun noGameOnHomeScreen() {
        assertNull(GameTitleWatcher.parseRunningAppName(idle))
    }

    @Test
    fun readsRunningGame() {
        val reply = idle + "running-app-titleid:PPSA01234\r\nrunning-app-name:Call of Duty®: Black Ops 6\r\n"
        assertEquals("Call of Duty®: Black Ops 6", GameTitleWatcher.parseRunningAppName(reply))
    }

    @Test
    fun blankNameIsIgnored() {
        assertNull(GameTitleWatcher.parseRunningAppName(idle + "running-app-name: \n"))
    }
}
