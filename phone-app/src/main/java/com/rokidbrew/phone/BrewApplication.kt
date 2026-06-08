package com.rokidbrew.phone

import android.app.Application

class BrewApplication : Application() {
    lateinit var cxrL: CxrLHiRokidSession
        private set

    fun setCxrL(session: CxrLHiRokidSession) {
        cxrL = session
    }
}
