package `fun`.kirari.hanako

import android.app.Application
import ru.noties.jlatexmath.JLatexMathAndroid

class HanakoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JLatexMathAndroid.init(this)
    }
}
