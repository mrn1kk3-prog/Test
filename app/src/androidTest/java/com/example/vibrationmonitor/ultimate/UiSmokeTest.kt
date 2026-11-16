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
  ui.onNodeWithText("Pyyhkäise ja nipistä",substring=true).performScrollTo()
  screenshot("spectrum")
  ui.onNodeWithText("Mahdollinen epätasapaino").performScrollTo().assertExists()
  ui.onNodeWithText("Tarkista roottorin puhtaus",substring=true).performScrollTo()
  screenshot("diagnosis")
  ui.onNodeWithText("Data",useUnmergedTree=true).performClick()
  ui.onNodeWithText("SIMULOITU • 1X-esimerkki").assertExists()
  screenshot("history")
 }
 private fun screenshot(name:String){
  val values=android.content.ContentValues().apply {
   put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"$name.png")
   put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
   put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/UVA")
  }
  val resolver=ui.activity.contentResolver
  val uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
  ui.onRoot().captureToImage().asAndroidBitmap().let{image->resolver.openOutputStream(uri)!!.use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
 }
}
