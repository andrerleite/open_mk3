package org.openmk3.vanilla

import android.app.Application
import org.openmk3.core.Edition

class VanillaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Edition.init(this, title = "OPEN MK3 VANILLA")
    }
}
