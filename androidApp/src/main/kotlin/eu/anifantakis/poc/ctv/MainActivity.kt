package eu.anifantakis.poc.ctv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.anifantakis.poc.ctv.config.AndroidAppContext
import eu.anifantakis.poc.ctv.config.ConfigGate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidAppContext.init(applicationContext)

        setContent {
            ConfigGate { App() }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
