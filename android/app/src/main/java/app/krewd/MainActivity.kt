package app.krewd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.krewd.ui.KrewdApp

class MainActivity : ComponentActivity() {
    private val viewModel: KrewdViewModel get() = (application as KrewdApplication).krewd

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Content scrolls behind the floating pills, so no system scrim behind 3-button navigation either.
        window.isNavigationBarContrastEnforced = false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            KrewdTheme {
                app.krewd.ui.ImageViewerHost(viewModel) { KrewdApp(viewModel) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.inForeground = true
    }

    override fun onResume() {
        super.onResume()
        viewModel.activity = java.lang.ref.WeakReference(this)
    }

    override fun onPause() {
        if (viewModel.activity?.get() === this) viewModel.activity = null
        super.onPause()
    }

    override fun onStop() {
        viewModel.inForeground = false
        super.onStop()
    }

    private fun openFrom(intent: Intent?) {
        intent?.data?.takeIf { it.scheme == "krewd" }?.let {
            viewModel.openConnectLink(it)
            return
        }
        intent?.getStringExtra(EXTRA_STOP_SESSION_ID)?.let {
            viewModel.askStop(it)
            return
        }
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let(viewModel::openSession)
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STOP_SESSION_ID = "stop_session_id"
    }
}
