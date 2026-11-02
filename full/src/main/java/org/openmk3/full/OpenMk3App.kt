package org.openmk3.full

import android.app.Application
import org.openmk3.core.Edition

class OpenMk3App : Application() {
    override fun onCreate() {
        super.onCreate()
        Edition.init(this, title = "OPEN MK3")
    }
}
