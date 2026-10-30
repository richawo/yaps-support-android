package ai.yaps.support.sample

import ai.yaps.support.LogLevel
import ai.yaps.support.SupportPaywall
import ai.yaps.support.SupportPaywallProduct
import ai.yaps.support.YapsSupport
import android.app.Application
import kotlin.concurrent.thread

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        YapsSupport.logLevel = LogLevel.DEBUG

        // Real backend: YapsSupport.configure(this, appId = "app_…")
        // The sample runs against an in-process fake so it works offline with no account.
        val backend = FakeBackend()
        thread(name = "fake-backend") { backend.start() }.join()
        YapsSupport.configure(this, appId = "app_demo", baseUrl = backend.baseUrl)

        YapsSupport.paywalls = listOf(
            SupportPaywall(
                id = "yearly_offer",
                title = "Yearly 40% off",
                products = listOf(SupportPaywallProduct(id = "pro_yearly", price = "$29.99", period = "P1Y", trial = "P1W")),
            ),
        )
        YapsSupport.setMetadata(mapOf("plan" to "free", "build" to "sample"))
    }
}
