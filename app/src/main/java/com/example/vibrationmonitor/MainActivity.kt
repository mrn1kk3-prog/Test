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
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private val buffer = ArrayDeque<Sample>()
    private val maxSamples = 4096
    private var lastTimestampNs = 0L
    private var state by mutableStateOf(MeasurementState())

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
                        onStart = { startMeasurement() },
                        onStop = { stopMeasurement() },
                        onExport = { exportCsv() }
                    )
                }
            }
        }
    }

    private fun startMeasurement() {
        buffer.clear()
        lastTimestampNs = 0L
        state = MeasurementState(isRunning = true, sensorName = sensor?.name ?: "Ei anturia")
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun stopMeasurement() {
        sensorManager.unregisterListener(this)
        state = state.copy(isRunning = false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!state.isRunning) return
        val s = Sample(event.timestamp, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        buffer.addLast(s)
        while (buffer.size > maxSamples) buffer.removeFirst()

        val fs = if (lastTimestampNs > 0L) 1e9 / (event.timestamp - lastTimestampNs).toDouble() else state.sampleRateHz
        lastTimestampNs = event.timestamp

        if (buffer.size >= 256 && buffer.size % 32 == 0) {
            val snapshot = buffer.toList()
            val analysis = VibrationAnalyzer.analyze(snapshot)
            state = state.copy(
                sampleRateHz = if (state.sampleRateHz == 0.0) fs else state.sampleRateHz * 0.9 + fs * 0.1,
                rms = analysis.rms,
                peak = analysis.peak,
                peakToPeak = analysis.peakToPeak,
                crestFactor = analysis.crestFactor,
                velocityRmsMmS = analysis.velocityRmsMmS,
                dominantHz = analysis.dominantHz,
                spectrum = analysis.spectrum,
                waveform = analysis.waveform
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun exportCsv() {
        if (buffer.isEmpty()) return
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "vibration_$stamp.csv")
        file.printWriter().use { out ->
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
    val lastExportPath: String = ""
)

data class AnalysisResult(
    val rms: Double,
    val peak: Double,
    val peakToPeak: Double,
    val crestFactor: Double,
    val velocityRmsMmS: Double,
    val dominantHz: List<Pair<Double, Double>>,
    val waveform: List<Float>,
    val spectrum: List<Pair<Float, Float>>
)

object VibrationAnalyzer {
    fun analyze(samples: List<Sample>): AnalysisResult {
        if (samples.size < 64) return AnalysisResult(0.0,0.0,0.0,0.0,0.0, emptyList(), emptyList(), emptyList())
        val n = highestPowerOfTwo(min(samples.size, 2048))
        val src = samples.takeLast(n)
        val t0 = src.first().timestampNs
        val duration = (src.last().timestampNs - t0) / 1e9
        val fs = if (duration > 0) (n - 1) / duration else 0.0

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

        val windowed = DoubleArray(n) { i ->
            val w = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (n - 1)))
            signal[i] * w
        }
        val fft = fftReal(windowed)
        val half = n/2
        val mags = DoubleArray(half) { i -> 2.0 * hypot(fft.first[i], fft.second[i]) / n }
        val spectrum = (1 until half).map { i ->
            ((i * fs / n).toFloat() to mags[i].toFloat())
        }.take(512)

        val peaks = (2 until half-2)
            .filter { mags[it] > mags[it-1] && mags[it] > mags[it+1] }
            .sortedByDescending { mags[it] }
            .take(5)
            .map { (it * fs / n) to mags[it] }

        val waveform = signal.takeLast(min(signal.size, 512)).map { it.toFloat() }
        return AnalysisResult(rms, peak, p2p, crest, velocityRms, peaks, waveform, spectrum)
    }

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
        while (p*2 <= x) p *= 2
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
fun MonitorScreen(state: MeasurementState, onStart: () -> Unit, onStop: () -> Unit, onExport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Vibration Monitor", style = MaterialTheme.typography.headlineMedium)
        Text("Puhelimen kiihtyvyysanturilla tehtävä kunnonvalvontamittaus")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = if (state.isRunning) onStop else onStart) { Text(if (state.isRunning) "Pysäytä" else "Aloita mittaus") }
            OutlinedButton(onClick = onExport) { Text("Vie CSV") }
        }
        Text("Anturi: ${state.sensorName.ifBlank { "ei vielä valittu" }}")
        Text("Näytteenotto: %.1f Hz | Nyquist: %.1f Hz".format(state.sampleRateHz, state.sampleRateHz/2.0))
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
        Text("Huom: puhelimen MEMS-anturi soveltuu suuntaa-antavaan kunnonvalvontaan. Ammattimaiset ISO 20816 -arvioinnit vaativat tunnetun anturin, kalibroinnin, oikean kiinnityksen ja määritellyn taajuuskaistan.", style = MaterialTheme.typography.bodySmall)
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
