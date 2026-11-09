package app.crew

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.crew.ui.CrewApp

class MainActivity : ComponentActivity() {
    private val viewModel: CrewViewModel by viewModels()

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
        openFrom(intent)
        setContent {
            CrewTheme {
                CrewApp(viewModel)
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

    override fun onStop() {
        viewModel.inForeground = false
        super.onStop()
    }

    private fun openFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let(viewModel::openSession)
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
