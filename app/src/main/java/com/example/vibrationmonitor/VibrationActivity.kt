package com.example.vibrationmonitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.*

class VibrationActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private val buffer = ArrayDeque<Sample>()
    private val maxSamples = 32768
    private var firstTimestampNs = 0L
    private var settings = MeasurementSettings()
    private var state by mutableStateOf(V3State(settings = settings))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    V3Screen(
                        state = state,
                        onSettingsChanged = { if (!state.isRunning) settings = it },
                        onStart = { startMeasurement(settings) },
                        onStop = { finishMeasurement(true) }
                    )
                }
            }
        }
    }

    private fun startMeasurement(newSettings: MeasurementSettings) {
        settings = newSettings.copy(
            durationSeconds = newSettings.durationSeconds.coerceIn(0.5, 120.0),
            maxFrequencyHz = newSettings.maxFrequencyHz.coerceAtLeast(1.0),
            spectrumLines = newSettings.spectrumLines.coerceIn(64, 8192)
        )
        buffer.clear()
        firstTimestampNs = 0L
        state = V3State(
            isRunning = true,
            sensorName = sensor?.name ?: "Ei anturia",
            settings = settings,
            statusText = "Mittaus käynnissä"
        )
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun finishMeasurement(manual: Boolean) {
        sensorManager.unregisterListener(this)
        val samples = buffer.toList()
        val result = if (samples.size >= 64) SignedAxisAnalyzer.analyze(samples, settings) else null
        state = state.copy(
            isRunning = false,
            progress = if (manual) state.progress else 1f,
            statusText = if (manual) "Mittaus pysäytetty" else "Mittaus valmis",
            sampleRateHz = result?.sampleRateHz ?: state.sampleRateHz,
            selectedAxis = result?.axis ?: state.selectedAxis,
            rms = result?.rms ?: state.rms,
            peak = result?.peak ?: state.peak,
            peakToPeak = result?.peakToPeak ?: state.peakToPeak,
            crestFactor = result?.crestFactor ?: state.crestFactor,
            waveform = result?.waveform ?: state.waveform,
            spectrum = result?.spectrum ?: state.spectrum,
            dominantHz = result?.dominantHz ?: state.dominantHz,
            fftSize = result?.fftSize ?: 0,
            actualLines = result?.actualLines ?: 0,
            dfHz = result?.dfHz ?: 0.0,
            effectiveMaxHz = result?.effectiveMaxHz ?: 0.0
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!state.isRunning) return
        if (firstTimestampNs == 0L) firstTimestampNs = event.timestamp
        buffer.addLast(Sample(event.timestamp, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
        while (buffer.size > maxSamples) buffer.removeFirst()

        val elapsed = (event.timestamp - firstTimestampNs) / 1e9
        if (buffer.size % 32 == 0) {
            val samples = buffer.toList()
            val axis = SignedAxisAnalyzer.strongestAxis(samples)
            state = state.copy(
                elapsedSeconds = elapsed,
                progress = (elapsed / settings.durationSeconds).coerceIn(0.0, 1.0).toFloat(),
                sampleRateHz = sampleRate(samples),
                selectedAxis = axis,
                waveform = SignedAxisAnalyzer.preview(samples, axis)
            )
        }
        if (elapsed >= settings.durationSeconds) finishMeasurement(false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun sampleRate(samples: List<Sample>): Double {
        if (samples.size < 2) return 0.0
        val dt = (samples.last().timestampNs - samples.first().timestampNs) / 1e9
        return if (dt > 0.0) (samples.size - 1) / dt else 0.0
    }
}

data class V3State(
    val isRunning: Boolean = false,
    val sensorName: String = "",
    val sampleRateHz: Double = 0.0,
    val selectedAxis: String = "-",
    val rms: Double = 0.0,
    val peak: Double = 0.0,
    val peakToPeak: Double = 0.0,
    val crestFactor: Double = 0.0,
    val waveform: List<Float> = emptyList(),
    val spectrum: List<Pair<Float, Float>> = emptyList(),
    val dominantHz: List<Pair<Double, Double>> = emptyList(),
    val settings: MeasurementSettings = MeasurementSettings(),
    val progress: Float = 0f,
    val elapsedSeconds: Double = 0.0,
    val statusText: String = "Valmis mittaukseen",
    val fftSize: Int = 0,
    val actualLines: Int = 0,
    val dfHz: Double = 0.0,
    val effectiveMaxHz: Double = 0.0
)

data class SignedAxisResult(
    val axis: String,
    val sampleRateHz: Double,
    val rms: Double,
    val peak: Double,
    val peakToPeak: Double,
    val crestFactor: Double,
    val waveform: List<Float>,
    val spectrum: List<Pair<Float, Float>>,
    val dominantHz: List<Pair<Double, Double>>,
    val fftSize: Int,
    val actualLines: Int,
    val dfHz: Double,
    val effectiveMaxHz: Double
)

object SignedAxisAnalyzer {
    fun strongestAxis(samples: List<Sample>): String {
        if (samples.isEmpty()) return "X"
        val rx = axisRms(samples.map { it.x })
        val ry = axisRms(samples.map { it.y })
        val rz = axisRms(samples.map { it.z })
        return when (max(rx, max(ry, rz))) {
            rx -> "X"
            ry -> "Y"
            else -> "Z"
        }
    }

    fun preview(samples: List<Sample>, axis: String): List<Float> {
        val src = samples.takeLast(min(samples.size, 512))
        val raw = src.map { value(it, axis) }
        val mean = raw.average()
        return raw.map { (it - mean).toFloat() }
    }

    fun analyze(samples: List<Sample>, settings: MeasurementSettings): SignedAxisResult {
        val requestedN = settings.spectrumLines * 2
        val n = highestPowerOfTwo(min(samples.size, requestedN)).coerceAtLeast(64)
        val src = samples.takeLast(n)
        val axis = strongestAxis(src)
        val duration = (src.last().timestampNs - src.first().timestampNs) / 1e9
        val fs = if (duration > 0.0) (n - 1) / duration else 0.0

        val raw = DoubleArray(n) { value(src[it], axis) }
        val mean = raw.average()
        val signal = DoubleArray(n) { raw[it] - mean }
        val rms = sqrt(signal.map { it * it }.average())
        val peak = signal.maxOf { abs(it) }
        val p2p = (signal.maxOrNull() ?: 0.0) - (signal.minOrNull() ?: 0.0)
        val crest = if (rms > 0.0) peak / rms else 0.0

        val gain = if (settings.windowType == WindowType.HANN) 0.5 else 1.0
        val windowed = DoubleArray(n) { i ->
            val w = if (settings.windowType == WindowType.HANN)
                0.5 * (1.0 - cos(2.0 * Math.PI * i / (n - 1))) else 1.0
            signal[i] * w
        }
        val fft = fftReal(windowed)
        val half = n / 2
        val df = fs / n
        val effectiveMax = min(settings.maxFrequencyHz, fs / 2.0)
        val maxBin = min(half - 1, floor(effectiveMax / df).toInt()).coerceAtLeast(1)
        val mags = DoubleArray(half) { i -> 2.0 * hypot(fft.first[i], fft.second[i]) / (n * gain) }
        val spectrum = (1..maxBin).map { i -> (i * df).toFloat() to mags[i].toFloat() }
        val peaks = (2 until maxBin.coerceAtLeast(3))
            .filter { it + 1 < mags.size && mags[it] > mags[it - 1] && mags[it] > mags[it + 1] }
            .sortedByDescending { mags[it] }
            .take(5)
            .map { (it * df) to mags[it] }

        return SignedAxisResult(
            axis, fs, rms, peak, p2p, crest,
            signal.takeLast(min(512, signal.size)).map { it.toFloat() },
            spectrum, peaks, n, maxBin, df, effectiveMax
        )
    }

    private fun axisRms(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    private fun value(s: Sample, axis: String) = when (axis) {
        "Y" -> s.y
        "Z" -> s.z
        else -> s.x
    }

    private fun highestPowerOfTwo(x: Int): Int {
        var p = 1
        while (p * 2 <= x) p *= 2
        return p
    }

    private fun fftReal(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = input.size
        val re = input.copyOf()
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tmp = re[i]; re[i] = re[j]; re[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wc = cos(ang); val ws = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = wr * wc - wi * ws
                    wi = wr * ws + wi * wc
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        return re to im
    }
}

@Composable
private fun V3Screen(
    state: V3State,
    onSettingsChanged: (MeasurementSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var durationText by remember { mutableStateOf(state.settings.durationSeconds.toString()) }
    var maxFreqText by remember { mutableStateOf(state.settings.maxFrequencyHz.toString()) }
    var lines by remember { mutableStateOf(state.settings.spectrumLines) }
    var window by remember { mutableStateOf(state.settings.windowType) }

    fun push() = onSettingsChanged(MeasurementSettings(
        durationText.toDoubleOrNull() ?: 5.0,
        maxFreqText.toDoubleOrNull() ?: 100.0,
        lines,
        window
    ))

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Vibration Monitor v0.3", style = MaterialTheme.typography.headlineMedium)
        Text("Aikataso ja FFT: allekirjoitettu X/Y/Z-akselisignaali")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(durationText, { durationText = it; push() }, Modifier.weight(1f), enabled = !state.isRunning, label = { Text("Mittausaika (s)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(maxFreqText, { maxFreqText = it; push() }, Modifier.weight(1f), enabled = !state.isRunning, label = { Text("Max taajuus (Hz)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        Text("Spektriviivoja")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(128, 256, 512, 1024).forEach { n -> FilterChip(lines == n, { lines = n; push() }, label = { Text("$n") }, enabled = !state.isRunning) }
        }
        Text("Ikkunointi")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WindowType.entries.forEach { w -> FilterChip(window == w, { window = w; push() }, label = { Text(w.label) }, enabled = !state.isRunning) }
        }
        Button(onClick = if (state.isRunning) onStop else onStart) { Text(if (state.isRunning) "Pysäytä" else "Suorita mittaus") }
        Text(state.statusText)
        if (state.isRunning) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("%.1f / %.1f s".format(state.elapsedSeconds, state.settings.durationSeconds))
        }
        Text("Anturi: ${state.sensorName.ifBlank { "ei vielä valittu" }}")
        Text("Näytteenotto: %.1f Hz | Nyquist: %.1f Hz | käytetty akseli: %s".format(state.sampleRateHz, state.sampleRateHz / 2.0, state.selectedAxis))
        if (!state.isRunning && state.fftSize > 0) Text("FFT: ${state.fftSize} pistettä | ${state.actualLines} viivaa | Δf %.3f Hz | Fmax %.1f Hz".format(state.dfHz, state.effectiveMaxHz))
        Text("Acceleration RMS: %.4f m/s²".format(state.rms))
        Text("Peak: %.4f m/s² | Peak-to-peak: %.4f m/s² | Crest: %.2f".format(state.peak, state.peakToPeak, state.crestFactor))
        Text("Aikataso", style = MaterialTheme.typography.titleMedium)
        SignedChart(state.waveform)
        Text("FFT-spektri", style = MaterialTheme.typography.titleMedium)
        V3Spectrum(state.spectrum)
        Text("Hallitsevat taajuudet", style = MaterialTheme.typography.titleMedium)
        if (state.dominantHz.isEmpty()) Text("Ei vielä dataa") else state.dominantHz.forEachIndexed { i, p -> Text("${i + 1}. %.2f Hz   %.4f m/s²".format(p.first, p.second)) }
        Text("Aikatasoa ei muodosteta resultantin itseisarvosta. Signaali säilyttää positiivisen ja negatiivisen suunnan nollatason ympärillä.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SignedChart(values: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (values.size < 2) return@Canvas
        val maxAbs = values.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val dx = size.width / (values.size - 1)
        val mid = size.height / 2f
        drawLine(Color.Gray, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        for (i in 1 until values.size) {
            val y1 = mid - values[i - 1] / maxAbs * mid * 0.9f
            val y2 = mid - values[i] / maxAbs * mid * 0.9f
            drawLine(Color(0xFF1565C0), Offset((i - 1) * dx, y1), Offset(i * dx, y2), strokeWidth = 2f)
        }
    }
}

@Composable
private fun V3Spectrum(values: List<Pair<Float, Float>>) {
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (values.size < 2) return@Canvas
        val maxY = values.maxOf { it.second }.coerceAtLeast(1e-6f)
        val maxX = values.maxOf { it.first }.coerceAtLeast(1f)
        for (i in 1 until values.size) {
            val a = values[i - 1]; val b = values[i]
            drawLine(Color(0xFFD84315), Offset(a.first / maxX * size.width, size.height - a.second / maxY * size.height * 0.95f), Offset(b.first / maxX * size.width, size.height - b.second / maxY * size.height * 0.95f), strokeWidth = 2f)
        }
    }
}
