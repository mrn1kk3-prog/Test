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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.*

enum class MachineType(val label: String) {
    GENERIC("Yleinen"), MOTOR("Moottori"), FAN("Puhallin"), PUMP("Pumppu")
}

data class DiagnosticFinding(
    val title: String,
    val confidence: Int,
    val severity: Int,
    val evidence: String,
    val recommendation: String
)

data class DiagnosticSettings(
    val machineType: MachineType = MachineType.MOTOR,
    val rpm: Double = 1500.0,
    val bladeCount: Int = 6
)

class VibrationActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private val buffer = ArrayDeque<Sample>()
    private val maxSamples = 32768
    private var firstTimestampNs = 0L
    private var settings = MeasurementSettings()
    private var diagnosticSettings = DiagnosticSettings()
    private var state by mutableStateOf(V3State(settings = settings, diagnosticSettings = diagnosticSettings))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            VibrationTheme {
                Surface(Modifier.fillMaxSize()) {
                    V4Screen(
                        state = state,
                        onSettingsChanged = { if (!state.isRunning) settings = it },
                        onDiagnosticSettingsChanged = { if (!state.isRunning) diagnosticSettings = it },
                        onStart = { startMeasurement(settings, diagnosticSettings) },
                        onStop = { finishMeasurement(true) }
                    )
                }
            }
        }
    }

    private fun startMeasurement(newSettings: MeasurementSettings, newDiagnosticSettings: DiagnosticSettings) {
        settings = newSettings.copy(
            durationSeconds = newSettings.durationSeconds.coerceIn(0.5, 120.0),
            maxFrequencyHz = newSettings.maxFrequencyHz.coerceAtLeast(1.0),
            spectrumLines = newSettings.spectrumLines.coerceIn(64, 8192)
        )
        diagnosticSettings = newDiagnosticSettings.copy(
            rpm = newDiagnosticSettings.rpm.coerceAtLeast(0.0),
            bladeCount = newDiagnosticSettings.bladeCount.coerceIn(1, 64)
        )
        buffer.clear()
        firstTimestampNs = 0L
        state = V3State(
            isRunning = true,
            sensorName = sensor?.name ?: "Ei anturia",
            settings = settings,
            diagnosticSettings = diagnosticSettings,
            statusText = "Mittaus käynnissä"
        )
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun finishMeasurement(manual: Boolean) {
        sensorManager.unregisterListener(this)
        val samples = buffer.toList()
        val result = if (samples.size >= 64) SignedAxisAnalyzer.analyze(samples, settings) else null
        val findings = if (result != null) FaultDiagnostics.analyze(result, diagnosticSettings) else emptyList()
        state = state.copy(
            isRunning = false,
            progress = if (manual) state.progress else 1f,
            statusText = if (manual) "Mittaus pysäytetty" else "Mittaus valmis – automaattinen analyysi valmis",
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
            effectiveMaxHz = result?.effectiveMaxHz ?: 0.0,
            findings = findings
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
    val diagnosticSettings: DiagnosticSettings = DiagnosticSettings(),
    val progress: Float = 0f,
    val elapsedSeconds: Double = 0.0,
    val statusText: String = "Valmis mittaukseen",
    val fftSize: Int = 0,
    val actualLines: Int = 0,
    val dfHz: Double = 0.0,
    val effectiveMaxHz: Double = 0.0,
    val findings: List<DiagnosticFinding> = emptyList()
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

object FaultDiagnostics {
    fun analyze(result: SignedAxisResult, cfg: DiagnosticSettings): List<DiagnosticFinding> {
        if (result.spectrum.isEmpty()) return emptyList()
        val runningHz = if (cfg.rpm > 0.0) cfg.rpm / 60.0 else result.dominantHz.firstOrNull()?.first ?: 0.0
        if (runningHz <= 0.0) return emptyList()

        val noise = result.spectrum.map { it.second.toDouble() }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val a1 = ampAt(result, runningHz)
        val a2 = ampAt(result, runningHz * 2.0)
        val a3 = ampAt(result, runningHz * 3.0)
        val a4 = ampAt(result, runningHz * 4.0)
        val a5 = ampAt(result, runningHz * 5.0)
        val maxAmp = result.spectrum.maxOfOrNull { it.second.toDouble() } ?: 0.0
        val findings = mutableListOf<DiagnosticFinding>()

        if (a1 > noise * 4.0 && a1 >= maxAmp * 0.55 && a2 < a1 * 0.45) {
            findings += DiagnosticFinding(
                "Epätasapaino mahdollinen", score(a1 / max(noise, 1e-9), 55, 96), 2,
                "1× pyörimistaajuus (${fmt(runningHz)} Hz) hallitsee spektriä.",
                "Tarkista roottorin/puhallinpyörän lika, massajakauma ja tasapainotus."
            )
        }

        if (a2 > max(noise * 3.0, a1 * 0.30) && (a3 > noise * 2.0 || a2 > a1 * 0.65)) {
            findings += DiagnosticFinding(
                "Linjausvirhe mahdollinen", score((a2 + a3) / max(a1 + noise, 1e-9), 50, 94), 2,
                "1×/2×/3× komponentit ovat korostuneet (${fmt(a1)}, ${fmt(a2)}, ${fmt(a3)} m/s²).",
                "Tarkista kytkimen kulma- ja rinnakkaislinjaus sekä soft foot."
            )
        }

        val harmonics = listOf(a2, a3, a4, a5).count { it > noise * 2.5 && it > a1 * 0.12 }
        if (harmonics >= 3) {
            findings += DiagnosticFinding(
                "Mekaaninen löysyys mahdollinen", (58 + harmonics * 8).coerceAtMost(92), 3,
                "Spektrissä näkyy useita pyörimisnopeuden harmonisia 2×–5×.",
                "Tarkista kiinnityspultit, jalat, laakeripesät, rakenteet ja mahdollinen välys."
            )
        }

        val highStart = max(runningHz * 3.0, result.effectiveMaxHz * 0.45)
        val highEnergy = bandEnergy(result, highStart, result.effectiveMaxHz)
        val totalEnergy = bandEnergy(result, 1.0, result.effectiveMaxHz)
        val highRatio = if (totalEnergy > 0.0) highEnergy / totalEnergy else 0.0
        if (result.crestFactor >= 4.0 && highRatio > 0.18) {
            findings += DiagnosticFinding(
                "Laakerivika / iskuisuus mahdollinen", ((result.crestFactor * 12 + highRatio * 80).toInt()).coerceIn(55, 95), 3,
                "Crest factor ${"%.2f".format(result.crestFactor)} ja korkeataajuista energiaa ${(highRatio * 100).toInt()} %.",
                "Tarkista vierintälaakerit, voitelu ja lämpötila. Varmista diagnoosi laajakaistaisella teollisuusanturilla."
            )
        }

        if (cfg.machineType == MachineType.FAN || cfg.machineType == MachineType.PUMP) {
            val bladeHz = runningHz * cfg.bladeCount
            if (bladeHz <= result.effectiveMaxHz) {
                val bladeAmp = ampAt(result, bladeHz)
                if (bladeAmp > noise * 3.0 && bladeAmp > a1 * 0.20) {
                    findings += DiagnosticFinding(
                        if (cfg.machineType == MachineType.FAN) "Lapataajuus korostunut" else "Siipitaajuus korostunut",
                        score(bladeAmp / max(noise, 1e-9), 52, 90), 2,
                        "${cfg.bladeCount} × 1× = ${fmt(bladeHz)} Hz on selvästi näkyvissä.",
                        if (cfg.machineType == MachineType.FAN) "Tarkista lavat, lika, vauriot, virtausesteet ja puhallinpyörän keskitys."
                        else "Tarkista juoksupyörä, virtausolosuhteet, tukokset ja hydraulinen kuormitus."
                    )
                }
            }
            if (highRatio > 0.32 && result.crestFactor < 4.5) {
                findings += DiagnosticFinding(
                    if (cfg.machineType == MachineType.PUMP) "Kavitaatio / turbulenssi mahdollinen" else "Turbulenssi / virtaushäiriö mahdollinen",
                    (55 + (highRatio * 70).toInt()).coerceAtMost(90), 2,
                    "Spektrissä on laajakaistaista energiaa, ei vain kapeita harmonisia piikkejä.",
                    if (cfg.machineType == MachineType.PUMP) "Tarkista imupaine, NPSH, venttiilit, tukokset ja prosessivirtaus."
                    else "Tarkista kanavisto, pellit, suodattimet, virtausesteet ja pyörteenmuodostus."
                )
            }
        }

        if (cfg.machineType == MachineType.MOTOR && result.effectiveMaxHz >= 100.0) {
            val a100 = ampAt(result, 100.0)
            if (a100 > noise * 4.0 && a100 > a1 * 0.18) {
                findings += DiagnosticFinding(
                    "Sähkömagneettinen värähtely mahdollinen", score(a100 / max(noise, 1e-9), 50, 86), 1,
                    "100 Hz komponentti (2 × 50 Hz verkkotaajuus) on korostunut.",
                    "Tarkista moottorin sähkösyöttö, ilmaväli, kiinnitys ja tarvittaessa moottorivirran spektri."
                )
            }
        }

        if (findings.isEmpty()) {
            findings += DiagnosticFinding(
                "Ei selvää yleistä vikakuviota", 60, 0,
                "Spektri ei täyttänyt tämän mittauksen automaattisia VCAT-tyyppisiä tunnistussääntöjä.",
                "Tee vertailumittaus samasta pisteestä samalla kuormalla ja seuraa trendiä."
            )
        }
        return findings.sortedByDescending { it.confidence }.take(4)
    }

    private fun ampAt(r: SignedAxisResult, hz: Double): Double {
        if (r.spectrum.isEmpty() || hz <= 0.0) return 0.0
        val tolerance = max(r.dfHz * 1.5, hz * 0.025)
        return r.spectrum.filter { abs(it.first - hz) <= tolerance }.maxOfOrNull { it.second.toDouble() } ?: 0.0
    }

    private fun bandEnergy(r: SignedAxisResult, low: Double, high: Double): Double =
        r.spectrum.asSequence().filter { it.first >= low && it.first <= high }.sumOf { it.second.toDouble().pow(2) }

    private fun score(ratio: Double, minScore: Int, maxScore: Int): Int =
        (minScore + ln(1.0 + ratio) * 12.0).roundToInt().coerceIn(minScore, maxScore)

    private fun fmt(v: Double) = "%.1f".format(v)
}

object SignedAxisAnalyzer {
    fun strongestAxis(samples: List<Sample>): String {
        if (samples.isEmpty()) return "X"
        val rx = axisRms(samples.map { it.x }); val ry = axisRms(samples.map { it.y }); val rz = axisRms(samples.map { it.z })
        return when (max(rx, max(ry, rz))) { rx -> "X"; ry -> "Y"; else -> "Z" }
    }

    fun preview(samples: List<Sample>, axis: String): List<Float> {
        val src = samples.takeLast(min(samples.size, 512)); val raw = src.map { value(it, axis) }; val mean = raw.average()
        return raw.map { (it - mean).toFloat() }
    }

    fun analyze(samples: List<Sample>, settings: MeasurementSettings): SignedAxisResult {
        val requestedN = settings.spectrumLines * 2
        val n = highestPowerOfTwo(min(samples.size, requestedN)).coerceAtLeast(64)
        val src = samples.takeLast(n); val axis = strongestAxis(src)
        val duration = (src.last().timestampNs - src.first().timestampNs) / 1e9
        val fs = if (duration > 0.0) (n - 1) / duration else 0.0
        val raw = DoubleArray(n) { value(src[it], axis) }; val mean = raw.average(); val signal = DoubleArray(n) { raw[it] - mean }
        val rms = sqrt(signal.map { it * it }.average()); val peak = signal.maxOf { abs(it) }
        val p2p = (signal.maxOrNull() ?: 0.0) - (signal.minOrNull() ?: 0.0); val crest = if (rms > 0.0) peak / rms else 0.0
        val gain = if (settings.windowType == WindowType.HANN) 0.5 else 1.0
        val windowed = DoubleArray(n) { i ->
            val w = if (settings.windowType == WindowType.HANN) 0.5 * (1.0 - cos(2.0 * Math.PI * i / (n - 1))) else 1.0
            signal[i] * w
        }
        val fft = fftReal(windowed); val half = n / 2; val df = fs / n
        val effectiveMax = min(settings.maxFrequencyHz, fs / 2.0)
        val maxBin = min(half - 1, floor(effectiveMax / df).toInt()).coerceAtLeast(1)
        val mags = DoubleArray(half) { i -> 2.0 * hypot(fft.first[i], fft.second[i]) / (n * gain) }
        val spectrum = (1..maxBin).map { i -> (i * df).toFloat() to mags[i].toFloat() }
        val peaks = (2 until maxBin.coerceAtLeast(3)).filter { it + 1 < mags.size && mags[it] > mags[it - 1] && mags[it] > mags[it + 1] }
            .sortedByDescending { mags[it] }.take(8).map { (it * df) to mags[it] }
        return SignedAxisResult(axis, fs, rms, peak, p2p, crest, signal.takeLast(min(512, signal.size)).map { it.toFloat() }, spectrum, peaks, n, maxBin, df, effectiveMax)
    }

    private fun axisRms(values: List<Double>): Double { if (values.isEmpty()) return 0.0; val m = values.average(); return sqrt(values.map { (it - m).pow(2) }.average()) }
    private fun value(s: Sample, axis: String) = when (axis) { "Y" -> s.y; "Z" -> s.z; else -> s.x }
    private fun highestPowerOfTwo(x: Int): Int { var p = 1; while (p * 2 <= x) p *= 2; return p }

    private fun fftReal(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = input.size; val re = input.copyOf(); val im = DoubleArray(n); var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit; if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t } }
        var len = 2
        while (len <= n) { val ang = -2.0 * Math.PI / len; val wc = cos(ang); val ws = sin(ang); var i = 0
            while (i < n) { var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) { val ur = re[i+k]; val ui = im[i+k]; val vr = re[i+k+len/2]*wr - im[i+k+len/2]*wi; val vi = re[i+k+len/2]*wi + im[i+k+len/2]*wr; re[i+k] = ur+vr; im[i+k] = ui+vi; re[i+k+len/2] = ur-vr; im[i+k+len/2] = ui-vi; val nwr = wr*wc - wi*ws; wi = wr*ws + wi*wc; wr = nwr }; i += len }
            len = len shl 1 }
        return re to im
    }
}

@Composable
private fun VibrationTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF52D6FF), secondary = Color(0xFF7CE8C7), tertiary = Color(0xFFFFC857),
        background = Color(0xFF071018), surface = Color(0xFF0D1822), surfaceVariant = Color(0xFF142431)
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@Composable
private fun V4Screen(
    state: V3State,
    onSettingsChanged: (MeasurementSettings) -> Unit,
    onDiagnosticSettingsChanged: (DiagnosticSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var durationText by remember { mutableStateOf(state.settings.durationSeconds.toString()) }
    var maxFreqText by remember { mutableStateOf(state.settings.maxFrequencyHz.toString()) }
    var lines by remember { mutableStateOf(state.settings.spectrumLines) }
    var window by remember { mutableStateOf(state.settings.windowType) }
    var machine by remember { mutableStateOf(state.diagnosticSettings.machineType) }
    var rpmText by remember { mutableStateOf(state.diagnosticSettings.rpm.toInt().toString()) }
    var bladesText by remember { mutableStateOf(state.diagnosticSettings.bladeCount.toString()) }

    fun push() {
        onSettingsChanged(MeasurementSettings(durationText.toDoubleOrNull() ?: 5.0, maxFreqText.toDoubleOrNull() ?: 100.0, lines, window))
        onDiagnosticSettingsChanged(DiagnosticSettings(machine, rpmText.toDoubleOrNull() ?: 0.0, bladesText.toIntOrNull() ?: 6))
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF061018), Color(0xFF0B1722)))).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("VIBRA", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Text("Condition Monitor • v0.4", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Surface(shape = RoundedCornerShape(50), color = if (state.isRunning) Color(0xFF173D32) else Color(0xFF162A38)) { Text(if (state.isRunning) "● LIVE" else "● READY", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = if (state.isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        }

        ModernCard("KONE & AUTOMAATTINEN DIAGNOOSI") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MachineType.entries.forEach { m -> FilterChip(machine == m, { machine = m; push() }, label = { Text(m.label) }, enabled = !state.isRunning) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(rpmText, { rpmText = it; push() }, Modifier.weight(1f), enabled = !state.isRunning, label = { Text("RPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (machine == MachineType.FAN || machine == MachineType.PUMP) OutlinedTextField(bladesText, { bladesText = it; push() }, Modifier.weight(1f), enabled = !state.isRunning, label = { Text("Lapoja / siipiä") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("RPM mahdollistaa 1×, 2×, 3×… order-tunnistuksen. Ilman oikeaa RPM-arvoa vikadiagnoosi heikkenee.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ModernCard("MITTAUS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(durationText, { durationText = it; push() }, Modifier.weight(1f), enabled = !state.isRunning, label = { Text("Aika (s)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(maxFreqText, { maxFreqText = it; push() }, Modifier.weight(1f), enabled = !state.isRunning, label = { Text("Fmax (Hz)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Spacer(Modifier.height(8.dp))
            Text("Spektriviivoja", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(128,256,512,1024).forEach { n -> FilterChip(lines == n, { lines = n; push() }, label = { Text("$n") }, enabled = !state.isRunning) } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { WindowType.entries.forEach { w -> FilterChip(window == w, { window = w; push() }, label = { Text(if (w == WindowType.HANN) "Hann" else "Rect") }, enabled = !state.isRunning) } }
        }

        Button(onClick = if (state.isRunning) onStop else onStart, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text(if (state.isRunning) "PYSÄYTÄ MITTAUS" else "ALOITA MITTAUS", fontWeight = FontWeight.Bold) }
        if (state.isRunning) { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()); Text("${"%.1f".format(state.elapsedSeconds)} / ${"%.1f".format(state.settings.durationSeconds)} s") }

        if (!state.isRunning && state.findings.isNotEmpty()) {
            Text("AUTOMAATTINEN VIANARVIO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            state.findings.forEach { DiagnosticCard(it) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("RMS", "%.3f".format(state.rms), "m/s²", Modifier.weight(1f)); MetricCard("CREST", "%.2f".format(state.crestFactor), "factor", Modifier.weight(1f)); MetricCard("AKSELI", state.selectedAxis, "strongest", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Fs", "%.0f".format(state.sampleRateHz), "Hz", Modifier.weight(1f)); MetricCard("PEAK", "%.2f".format(state.peak), "m/s²", Modifier.weight(1f)); MetricCard("Δf", "%.2f".format(state.dfHz), "Hz", Modifier.weight(1f))
        }

        ModernCard("AIKATASO") { SignalChart(state.waveform) }
        ModernCard("FFT-SPEKTRI") { SpectrumChart(state.spectrum) }
        ModernCard("HALLITSEVAT TAAJUUDET") {
            if (state.dominantHz.isEmpty()) Text("Ei vielä mittausdataa", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else state.dominantHz.take(6).forEachIndexed { i, p -> Text("${i+1}. ${"%.2f".format(p.first)} Hz   •   ${"%.4f".format(p.second)} m/s²") }
        }
        ModernCard("MITTAUKSEN LAATU") {
            Text(state.statusText, fontWeight = FontWeight.SemiBold)
            Text("Anturi: ${state.sensorName.ifBlank { "ei vielä valittu" }}")
            if (state.fftSize > 0) Text("FFT ${state.fftSize} pistettä • ${state.actualLines} viivaa • Fmax ${"%.1f".format(state.effectiveMaxHz)} Hz")
            Text("Puhelimen MEMS-anturi soveltuu parhaiten trendiin ja matalataajuisiin yleisvikoihin. Laakeri-, kavitaatio- ja sähkövikojen lopullinen varmistus vaatii usein teollisuusanturin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun ModernCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); content() }
    }
}

@Composable private fun MetricCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun DiagnosticCard(f: DiagnosticFinding) {
    val accent = when (f.severity) { 3 -> Color(0xFFFF6B6B); 2 -> Color(0xFFFFC857); 1 -> Color(0xFF52D6FF); else -> Color(0xFF7CE8C7) }
    Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(f.title, fontWeight = FontWeight.Bold, color = accent); Text("${f.confidence}%", fontWeight = FontWeight.Black, color = accent) }
            LinearProgressIndicator(progress = { f.confidence / 100f }, modifier = Modifier.fillMaxWidth(), color = accent)
            Text(f.evidence); Text("Toimenpide: ${f.recommendation}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SignalChart(values: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        if (values.size < 2) return@Canvas
        val maxAbs = values.maxOf { abs(it) }.coerceAtLeast(1e-6f); val mid = size.height / 2f
        drawLine(Color(0xFF29404F), Offset(0f, mid), Offset(size.width, mid), 1f)
        for (i in 1 until values.size) { val x1 = (i-1f)/(values.size-1f)*size.width; val x2 = i.toFloat()/(values.size-1f)*size.width; val y1 = mid - values[i-1]/maxAbs*mid*0.9f; val y2 = mid - values[i]/maxAbs*mid*0.9f; drawLine(Color(0xFF52D6FF), Offset(x1,y1), Offset(x2,y2), 2f) }
    }
}

@Composable private fun SpectrumChart(values: List<Pair<Float, Float>>) {
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        if (values.size < 2) return@Canvas
        val maxY = values.maxOf { it.second }.coerceAtLeast(1e-6f); val maxX = values.maxOf { it.first }.coerceAtLeast(1f)
        for (i in 1 until values.size) { val a = values[i-1]; val b = values[i]; val x1 = a.first/maxX*size.width; val x2 = b.first/maxX*size.width; val y1 = size.height - a.second/maxY*size.height*0.92f; val y2 = size.height - b.second/maxY*size.height*0.92f; drawLine(Color(0xFF7CE8C7), Offset(x1,y1), Offset(x2,y2), 2f) }
    }
}
