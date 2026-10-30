package ai.yaps.support

import android.content.Context
import android.content.ContextWrapper

/** A Context that must never be touched: android.jar stubs return defaults, so any use is a bug in the code under test. */
object FakeContext {
    val none: Context = ContextWrapper(null)
}
