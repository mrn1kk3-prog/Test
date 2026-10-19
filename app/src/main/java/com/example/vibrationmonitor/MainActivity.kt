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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

enum class WindowType(val label: String) {
    HANN("Hanning (Hann)"),
    RECTANGULAR("Rectangular")
}

data class MeasurementSettings(
    val durationSeconds: Double = 5.0,
    val maxFrequencyHz: Double = 100.0,
    val spectrumLines: Int = 512,
    val windowType: WindowType = WindowType.HANN
)

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private val buffer = ArrayDeque<Sample>()
    private val maxSamples = 32768
    private var firstTimestampNs = 0L
    private var lastTimestampNs = 0L
    private var settings = MeasurementSettings()
    private var state by mutableStateOf(MeasurementState(settings = settings))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MonitorScreen(
                        state = state,
                        onSettingsChanged = { if (!state.isRunning) settings = it },
                        onStart = { startMeasurement(settings) },
                        onStop = { stopMeasurement(manual = true) },
                        onExport = { exportCsv() }
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
        lastTimestampNs = 0L
        state = MeasurementState(
            isRunning = true,
            sensorName = sensor?.name ?: "Ei anturia",
            settings = settings,
            statusText = "Mittaus käynnissä"
        )
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun stopMeasurement(manual: Boolean) {
        sensorManager.unregisterListener(this)
        val snapshot = buffer.toList()
        val analysis = if (snapshot.size >= 64) VibrationAnalyzer.analyze(snapshot, settings) else null
        val fs = actualSampleRate(snapshot)
        state = state.copy(
            isRunning = false,
            sampleRateHz = fs,
            progress = if (manual) state.progress else 1f,
            statusText = if (manual) "Mittaus pysäytetty" else "Mittaus valmis",
            rms = analysis?.rms ?: state.rms,
            peak = analysis?.peak ?: state.peak,
            peakToPeak = analysis?.peakToPeak ?: state.peakToPeak,
            crestFactor = analysis?.crestFactor ?: state.crestFactor,
            velocityRmsMmS = analysis?.velocityRmsMmS ?: state.velocityRmsMmS,
            dominantHz = analysis?.dominantHz ?: state.dominantHz,
            spectrum = analysis?.spectrum ?: state.spectrum,
            waveform = analysis?.waveform ?: state.waveform,
            actualFftSize = analysis?.fftSize ?: 0,
            actualLines = analysis?.actualLines ?: 0,
            frequencyResolutionHz = analysis?.frequencyResolutionHz ?: 0.0,
            effectiveMaxFrequencyHz = analysis?.effectiveMaxFrequencyHz ?: 0.0
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!state.isRunning) return
        if (firstTimestampNs == 0L) firstTimestampNs = event.timestamp

        val s = Sample(
            event.timestamp,
            event.values[0].toDouble(),
            event.values[1].toDouble(),
            event.values[2].toDouble()
        )
        buffer.addLast(s)
        while (buffer.size > maxSamples) buffer.removeFirst()
        lastTimestampNs = event.timestamp

        val elapsed = (event.timestamp - firstTimestampNs) / 1e9
        val progress = (elapsed / settings.durationSeconds).coerceIn(0.0, 1.0).toFloat()
        val fs = actualSampleRate(buffer.toList())

        if (buffer.size % 32 == 0) {
            state = state.copy(
                sampleRateHz = fs,
                progress = progress,
                elapsedSeconds = elapsed,
                waveform = currentWaveform(buffer.toList())
            )
        }

        if (elapsed >= settings.durationSeconds) {
            stopMeasurement(manual = false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun actualSampleRate(samples: List<Sample>): Double {
        if (samples.size < 2) return 0.0
        val duration = (samples.last().timestampNs - samples.first().timestampNs) / 1e9
        return if (duration > 0.0) (samples.size - 1) / duration else 0.0
    }

    private fun currentWaveform(samples: List<Sample>): List<Float> {
        if (samples.isEmpty()) return emptyList()
        val src = samples.takeLast(min(samples.size, 512))
        val raw = src.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
        val mean = raw.average()
        return raw.map { (it - mean).toFloat() }
    }

    private fun exportCsv() {
        if (buffer.isEmpty()) return
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "vibration_$stamp.csv")
        file.printWriter().use { out ->
            out.println("# duration_s=${settings.durationSeconds}")
            out.println("# requested_max_frequency_hz=${settings.maxFrequencyHz}")
            out.println("# requested_spectrum_lines=${settings.spectrumLines}")
            out.println("# window=${settings.windowType.label}")
            out.println("timestamp_ns,x_m_s2,y_m_s2,z_m_s2,resultant_m_s2")
            buffer.forEach { s ->
                val r = sqrt(s.x*s.x + s.y*s.y + s.z*s.z)
                out.println("${s.timestampNs},${s.x},${s.y},${s.z},$r")
            }
        }
        state = state.copy(lastExportPath = file.absolutePath)
    }
}

data class Sample(val timestampNs: Long, val x: Double, val y: Double, val z: Double)

data class MeasurementState(
    val isRunning: Boolean = false,
    val sensorName: String = "",
    val sampleRateHz: Double = 0.0,
    val rms: Double = 0.0,
    val peak: Double = 0.0,
    val peakToPeak: Double = 0.0,
    val crestFactor: Double = 0.0,
    val velocityRmsMmS: Double = 0.0,
    val dominantHz: List<Pair<Double, Double>> = emptyList(),
    val waveform: List<Float> = emptyList(),
    val spectrum: List<Pair<Float, Float>> = emptyList(),
    val lastExportPath: String = "",
    val settings: MeasurementSettings = MeasurementSettings(),
    val progress: Float = 0f,
    val elapsedSeconds: Double = 0.0,
    val statusText: String = "Valmis mittaukseen",
    val actualFftSize: Int = 0,
    val actualLines: Int = 0,
    val frequencyResolutionHz: Double = 0.0,
    val effectiveMaxFrequencyHz: Double = 0.0
)

data class AnalysisResult(
    val rms: Double,
    val peak: Double,
    val peakToPeak: Double,
    val crestFactor: Double,
    val velocityRmsMmS: Double,
    val dominantHz: List<Pair<Double, Double>>,
    val waveform: List<Float>,
    val spectrum: List<Pair<Float, Float>>,
    val fftSize: Int,
    val actualLines: Int,
    val frequencyResolutionHz: Double,
    val effectiveMaxFrequencyHz: Double
)

object VibrationAnalyzer {
    fun analyze(samples: List<Sample>, settings: MeasurementSettings): AnalysisResult {
        if (samples.size < 64) return emptyResult()

        val requestedFftSize = settings.spectrumLines * 2
        val n = highestPowerOfTwo(min(samples.size, requestedFftSize)).coerceAtLeast(64)
        val src = samples.takeLast(n)
        val duration = (src.last().timestampNs - src.first().timestampNs) / 1e9
        val fs = if (duration > 0) (n - 1) / duration else 0.0
        if (fs <= 0.0) return emptyResult()

        val raw = DoubleArray(n) { i ->
            val s = src[i]
            sqrt(s.x*s.x + s.y*s.y + s.z*s.z)
        }
        val mean = raw.average()
        val signal = DoubleArray(n) { raw[it] - mean }
        val rms = sqrt(signal.map { it*it }.average())
        val peak = signal.maxOf { abs(it) }
        val p2p = (signal.maxOrNull() ?: 0.0) - (signal.minOrNull() ?: 0.0)
        val crest = if (rms > 0) peak/rms else 0.0

        val velocity = integrateHighPassed(signal, fs)
        val velocityRms = sqrt(velocity.map { it*it }.average()) * 1000.0

        val coherentGain = when (settings.windowType) {
            WindowType.HANN -> 0.5
            WindowType.RECTANGULAR -> 1.0
        }
        val windowed = DoubleArray(n) { i ->
            val w = when (settings.windowType) {
                WindowType.HANN -> 0.5 * (1.0 - cos(2.0 * Math.PI * i / (n - 1)))
                WindowType.RECTANGULAR -> 1.0
            }
            signal[i] * w
        }
        val fft = fftReal(windowed)
        val half = n / 2
        val df = fs / n
        val nyquist = fs / 2.0
        val effectiveMaxHz = min(settings.maxFrequencyHz, nyquist)
        val maxBin = min(half - 1, floor(effectiveMaxHz / df).toInt()).coerceAtLeast(1)
        val mags = DoubleArray(half) { i ->
            2.0 * hypot(fft.first[i], fft.second[i]) / (n * coherentGain)
        }
        val spectrum = (1..maxBin).map { i ->
            ((i * df).toFloat() to mags[i].toFloat())
        }

        val peaks = (2 until maxBin.coerceAtLeast(3))
            .filter { it + 1 < mags.size && mags[it] > mags[it-1] && mags[it] > mags[it+1] }
            .sortedByDescending { mags[it] }
            .take(5)
            .map { (it * df) to mags[it] }

        val waveform = signal.takeLast(min(signal.size, 512)).map { it.toFloat() }
        return AnalysisResult(
            rms = rms,
            peak = peak,
            peakToPeak = p2p,
            crestFactor = crest,
            velocityRmsMmS = velocityRms,
            dominantHz = peaks,
            waveform = waveform,
            spectrum = spectrum,
            fftSize = n,
            actualLines = maxBin,
            frequencyResolutionHz = df,
            effectiveMaxFrequencyHz = effectiveMaxHz
        )
    }

    private fun emptyResult() = AnalysisResult(
        0.0, 0.0, 0.0, 0.0, 0.0,
        emptyList(), emptyList(), emptyList(),
        0, 0, 0.0, 0.0
    )

    private fun integrateHighPassed(a: DoubleArray, fs: Double): DoubleArray {
        if (fs <= 0) return DoubleArray(a.size)
        val dt = 1.0/fs
        val v = DoubleArray(a.size)
        for (i in 1 until a.size) v[i] = v[i-1] + 0.5*(a[i] + a[i-1])*dt
        val mean = v.average()
        for (i in v.indices) v[i] -= mean
        return v
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
                val tr = re[i]; re[i] = re[j]; re[j] = tr
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenCos = cos(ang)
            val wlenSin = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len/2) {
                    val uR = re[i+k]; val uI = im[i+k]
                    val vR = re[i+k+len/2]*wr - im[i+k+len/2]*wi
                    val vI = re[i+k+len/2]*wi + im[i+k+len/2]*wr
                    re[i+k] = uR+vR; im[i+k] = uI+vI
                    re[i+k+len/2] = uR-vR; im[i+k+len/2] = uI-vI
                    val nwr = wr*wlenCos - wi*wlenSin
                    wi = wr*wlenSin + wi*wlenCos
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
fun MonitorScreen(
    state: MeasurementState,
    onSettingsChanged: (MeasurementSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit
) {
    var durationText by remember { mutableStateOf(state.settings.durationSeconds.toString()) }
    var maxFrequencyText by remember { mutableStateOf(state.settings.maxFrequencyHz.toString()) }
    var selectedLines by remember { mutableStateOf(state.settings.spectrumLines) }
    var selectedWindow by remember { mutableStateOf(state.settings.windowType) }

    fun pushSettings() {
        onSettingsChanged(
            MeasurementSettings(
                durationSeconds = durationText.toDoubleOrNull() ?: 5.0,
                maxFrequencyHz = maxFrequencyText.toDoubleOrNull() ?: 100.0,
                spectrumLines = selectedLines,
                windowType = selectedWindow
            )
        )
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Vibration Monitor", style = MaterialTheme.typography.headlineMedium)
        Text("Puhelimen kiihtyvyysanturilla tehtävä kunnonvalvontamittaus")

        Text("Mittausasetukset", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it; pushSettings() },
                enabled = !state.isRunning,
                label = { Text("Mittausaika (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = maxFrequencyText,
                onValueChange = { maxFrequencyText = it; pushSettings() },
                enabled = !state.isRunning,
                label = { Text("Max taajuus (Hz)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Text("Spektriviivoja")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(128, 256, 512, 1024).forEach { lines ->
                FilterChip(
                    selected = selectedLines == lines,
                    enabled = !state.isRunning,
                    onClick = { selectedLines = lines; pushSettings() },
                    label = { Text(lines.toString()) }
                )
            }
        }

        Text("Ikkunointi")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WindowType.entries.forEach { window ->
                FilterChip(
                    selected = selectedWindow == window,
                    enabled = !state.isRunning,
                    onClick = { selectedWindow = window; pushSettings() },
                    label = { Text(window.label) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = if (state.isRunning) onStop else onStart) {
                Text(if (state.isRunning) "Pysäytä" else "Suorita mittaus")
            }
            OutlinedButton(onClick = onExport, enabled = !state.isRunning && state.waveform.isNotEmpty()) {
                Text("Vie CSV")
            }
        }

        Text(state.statusText)
        if (state.isRunning) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("%.1f / %.1f s".format(state.elapsedSeconds, state.settings.durationSeconds))
        }

        Text("Anturi: ${state.sensorName.ifBlank { "ei vielä valittu" }}")
        Text("Todellinen näytteenotto: %.1f Hz | Nyquist: %.1f Hz".format(state.sampleRateHz, state.sampleRateHz/2.0))

        if (!state.isRunning && state.actualFftSize > 0) {
            Text("FFT: ${state.actualFftSize} pistettä | käytössä ${state.actualLines} viivaa | Δf %.3f Hz | Fmax %.1f Hz".format(
                state.frequencyResolutionHz,
                state.effectiveMaxFrequencyHz
            ))
            if (state.settings.maxFrequencyHz > state.sampleRateHz / 2.0 && state.sampleRateHz > 0.0) {
                Text(
                    "Huom: pyydetty taajuusalue ylittää anturin Nyquist-rajan. Spektri on rajattu %.1f Hz:iin.".format(state.sampleRateHz/2.0),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        MetricGrid(state)
        Text("Aikataso", style = MaterialTheme.typography.titleMedium)
        SignalChart(state.waveform)
        Text("FFT-spektri", style = MaterialTheme.typography.titleMedium)
        SpectrumChart(state.spectrum)
        Text("Hallitsevat taajuudet", style = MaterialTheme.typography.titleMedium)
        if (state.dominantHz.isEmpty()) Text("Ei vielä dataa") else state.dominantHz.forEachIndexed { i, p ->
            Text("${i+1}. %.2f Hz   %.4f m/s²".format(p.first, p.second))
        }
        if (state.lastExportPath.isNotBlank()) Text("Tallennettu: ${state.lastExportPath}")
        Text(
            "Spektriviivojen todellinen määrä riippuu siitä, kuinka monta näytettä puhelimen anturi ehtii tuottaa mittausaikana. Hanning-ikkunointi vähentää spektrivuotoa. Puhelimen MEMS-anturi soveltuu suuntaa-antavaan kunnonvalvontaan, ei kalibroidun ammattimittalaitteen korvaajaksi.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MetricGrid(s: MeasurementState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Acceleration RMS: %.4f m/s²".format(s.rms))
        Text("Peak: %.4f m/s²".format(s.peak))
        Text("Peak-to-peak: %.4f m/s²".format(s.peakToPeak))
        Text("Crest factor: %.2f".format(s.crestFactor))
        Text("Velocity RMS (arvio): %.3f mm/s".format(s.velocityRmsMmS))
    }
}

@Composable
private fun SignalChart(values: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (values.size < 2) return@Canvas
        val maxAbs = values.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val dx = size.width/(values.size-1)
        val mid = size.height/2f
        for (i in 1 until values.size) {
            val y1 = mid - values[i-1]/maxAbs*mid*0.9f
            val y2 = mid - values[i]/maxAbs*mid*0.9f
            drawLine(Color(0xFF1565C0), Offset((i-1)*dx,y1), Offset(i*dx,y2), strokeWidth = 2f)
        }
    }
}

@Composable
private fun SpectrumChart(values: List<Pair<Float, Float>>) {
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (values.size < 2) return@Canvas
        val maxY = values.maxOf { it.second }.coerceAtLeast(1e-6f)
        val maxX = values.maxOf { it.first }.coerceAtLeast(1f)
        for (i in 1 until values.size) {
            val a = values[i-1]; val b = values[i]
            drawLine(
                Color(0xFFD84315),
                Offset(a.first/maxX*size.width, size.height-a.second/maxY*size.height*0.95f),
                Offset(b.first/maxX*size.width, size.height-b.second/maxY*size.height*0.95f),
                strokeWidth = 2f
            )
        }
    }
}
