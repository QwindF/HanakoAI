package `fun`.kirari.hanako

import android.app.Application
import `fun`.kirari.hanako.overlay.AntiScreenshotHelper
import ru.noties.jlatexmath.JLatexMathAndroid

class HanakoApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AntiScreenshotHelper.initialize()
        JLatexMathAndroid.init(this)
        container = AppContainer(applicationContext)
        instance = this
    }

    companion object {
        lateinit var instance: HanakoApplication
            private set
    }
}
