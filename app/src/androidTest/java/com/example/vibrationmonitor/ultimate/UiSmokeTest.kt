package com.example.vibrationmonitor.ultimate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
 @get:Rule val ui = createAndroidComposeRule<UltimateActivity>()
 @Test fun navigateAndAnalyzeSimulatedMeasurement(){
  ui.onNodeWithText("Aloita uusi mittaus").assertExists()
  screenshot("home")
  ui.onNodeWithText("Ohje",useUnmergedTree=true).performClick()
  ui.onNodeWithText("1X-esimerkki").performScrollTo().performClick()
  ui.waitUntil(timeoutMillis=30000){ui.onAllNodesWithText("SIMULOITU • 1X-esimerkki").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithText("FFT-spektri").performScrollTo().assertExists()
  screenshot("spectrum")
  ui.onNodeWithText("Mahdollinen epätasapaino").performScrollTo().assertExists()
  screenshot("diagnosis")
  ui.onNodeWithText("Historia",useUnmergedTree=true).performClick()
  ui.onNodeWithText("SIMULOITU • 1X-esimerkki").assertExists()
  screenshot("history")
 }
 private fun screenshot(name:String){ui.onRoot().captureToImage().asAndroidBitmap().let{image->File(ui.activity.getExternalFilesDir(null),"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}}
}
