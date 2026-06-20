package `fun`.kirari.hanako

import android.app.Application
import ru.noties.jlatexmath.JLatexMathAndroid

class HanakoApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        JLatexMathAndroid.init(this)
        container = AppContainer(applicationContext)
        instance = this
    }

    companion object {
        lateinit var instance: HanakoApplication
            private set
    }
}
