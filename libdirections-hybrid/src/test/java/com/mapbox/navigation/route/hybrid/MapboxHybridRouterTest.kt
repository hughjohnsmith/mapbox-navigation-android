package com.mapbox.navigation.route.hybrid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultParams
import com.mapbox.navigation.base.extensions.coordinates
import com.mapbox.navigation.base.route.Router
import com.mapbox.navigation.route.offboard.MapboxOffboardRouter
import com.mapbox.navigation.route.onboard.MapboxOnboardRouter
import com.mapbox.navigation.testing.MainCoroutineRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@InternalCoroutinesApi
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class MapboxHybridRouterTest {

    @get:Rule
    var rule = MainCoroutineRule()

    private lateinit var hybridRouter: MapboxHybridRouter
    private val onboardRouter: MapboxOnboardRouter = mockk(relaxUnitFun = true)
    private val offboardRouter: MapboxOffboardRouter = mockk(relaxUnitFun = true)
    private val context: Context = mockk(relaxUnitFun = true)
    private val connectivityManager: ConnectivityManager = mockk(relaxUnitFun = true)
    private val intent: Intent = mockk(relaxUnitFun = true)
    private val networkInfo: NetworkInfo = mockk(relaxUnitFun = true)
    private val routerCallback: Router.Callback = mockk(relaxUnitFun = true)
    private val routerOptions: RouteOptions = provideDefaultRouteOptions()
    private val receiver = slot<BroadcastReceiver>()
    private val internalCallback = slot<Router.Callback>()

    @Before
    fun setUp() {
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetworkInfo } returns networkInfo
        every { context.registerReceiver(capture(receiver), any()) } returns intent
        every { onboardRouter.getRoute(routerOptions, capture(internalCallback)) } answers {}
        every { offboardRouter.getRoute(routerOptions, capture(internalCallback)) } answers {}
        hybridRouter = MapboxHybridRouter(onboardRouter, offboardRouter, context)
    }

    @Test
    fun whenNetworkConnectedOffboardRouterUsed() = rule.runBlockingTest {
        enableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        verify(exactly = 1) { offboardRouter.getRoute(routerOptions, any()) }
    }

    @Test
    fun whenNoNetworkConnectionOnboardRouterUsed() = rule.runBlockingTest {
        disableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        verify(exactly = 1) { onboardRouter.getRoute(routerOptions, any()) }
    }

    @Test
    fun whenOffboardRouterFailsOnboardRouterIsCalled() = rule.runBlockingTest {
        enableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        internalCallback.captured.onFailure(Throwable())
        internalCallback.captured.onResponse(emptyList())

        verify(exactly = 1) { offboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { onboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { routerCallback.onResponse(any()) }
    }

    @Test
    fun whenOnboardRouterFailsOffboardRouterIsCalled() = rule.runBlockingTest {
        disableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        internalCallback.captured.onFailure(Throwable())
        internalCallback.captured.onResponse(emptyList())

        verify(exactly = 1) { onboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { offboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { routerCallback.onResponse(any()) }
    }

    @Test
    fun whenOffboardRouterFailsOnboardRouterIsCalledAndOffboardUsedAgain() = rule.runBlockingTest {
        enableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        internalCallback.captured.onFailure(Throwable())
        internalCallback.captured.onResponse(emptyList())

        hybridRouter.getRoute(routerOptions, routerCallback)

        internalCallback.captured.onResponse(emptyList())

        verify(exactly = 2) { offboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { onboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 2) { routerCallback.onResponse(any()) }
    }

    @Test
    fun whenOnboardRouterFailsOffboardRouterIsCalledAndOnboardUsedAgain() = rule.runBlockingTest {
        disableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        internalCallback.captured.onFailure(Throwable())
        internalCallback.captured.onResponse(emptyList())

        hybridRouter.getRoute(routerOptions, routerCallback)

        internalCallback.captured.onResponse(emptyList())

        verify(exactly = 2) { onboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { offboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 2) { routerCallback.onResponse(any()) }
    }

    @Test
    fun whenConnectionAppearedRoutersSwitched() = rule.runBlockingTest {
        disableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        enableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        verify(exactly = 1) { onboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { offboardRouter.getRoute(routerOptions, capture(internalCallback)) }
    }

    @Test
    fun whenConnectionDisappearedRoutersSwitched() = rule.runBlockingTest {
        enableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        disableNetworkConnection()

        hybridRouter.getRoute(routerOptions, routerCallback)

        verify(exactly = 1) { offboardRouter.getRoute(routerOptions, capture(internalCallback)) }
        verify(exactly = 1) { onboardRouter.getRoute(routerOptions, capture(internalCallback)) }
    }

    private fun enableNetworkConnection() = networkConnected(true)

    private fun disableNetworkConnection() = networkConnected(false)

    private fun networkConnected(networkConnected: Boolean) {
        every { networkInfo.isConnectedOrConnecting } returns networkConnected

        receiver.captured.onReceive(context, Intent())

        Thread.sleep(1000)
    }

    private fun provideDefaultRouteOptions(): RouteOptions {
        return RouteOptions.builder()
            .applyDefaultParams()
            .apply {
                accessToken("")
                coordinates(Point.fromLngLat(.0, .0), null, Point.fromLngLat(.0, .0))
            }.build()
    }
}
