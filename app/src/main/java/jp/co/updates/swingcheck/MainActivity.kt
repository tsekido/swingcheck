package jp.co.updates.swingcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import jp.co.updates.swingcheck.ui.SwingcheckNavHost
import jp.co.updates.swingcheck.ui.theme.SwingcheckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SwingcheckApp).container
        setContent {
            SwingcheckTheme {
                SwingcheckNavHost(container)
            }
        }
    }
}
