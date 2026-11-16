package com.example.vibrationmonitor.ultimate

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import org.json.JSONObject

class UltimateActivity:ComponentActivity(){
 private lateinit var store:Store;private lateinit var source:PhoneSource;private lateinit var capture:Capture
 private var records by mutableStateOf(emptyList<Record>());private var machines by mutableStateOf(emptyList<Pair<String,String>>())
 private var running by mutableStateOf(false);private var busy by mutableStateOf(false);private var status by mutableStateOf("")
 private var firstSample = 0L
 private var elapsed by mutableStateOf(0.0)
 private var live by mutableStateOf(emptyList<Raw>());private var selected by mutableStateOf<Record?>(null);private var result by mutableStateOf<Result?>(null);private var overlay by mutableStateOf<Result?>(null)
 private var canSaveAnalysis by mutableStateOf(false)
 private var tab by mutableStateOf(0);private var dark by mutableStateOf(true)
 private val errors = CoroutineExceptionHandler { _, error -> runOnUiThread { busy=false; status="Toiminto epäonnistui: ${error.message}" } }
 private var pendingExport:((java.io.OutputStream)->Unit)?=null
 private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null){val writer=pendingExport;lifecycleScope.launch(Dispatchers.IO){runCatching{contentResolver.openOutputStream(uri)!!.use{writer?.invoke(it)}}.onSuccess{withContext(Dispatchers.Main){status="Vienti valmis"}}.onFailure{withContext(Dispatchers.Main){status="Vienti epäonnistui: ${it.message}"}}}}}
 private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)lifecycleScope.launch(errors){busy=true;try{val count=withContext(Dispatchers.IO){val text=contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()};store.restore(text)};refresh();status="Palautettu $count mittausta uusilla tunnisteilla"}catch(e:Exception){status="Palautus epäonnistui: ${e.message}"}finally{busy=false}}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);dark=getSharedPreferences("uva-ui",MODE_PRIVATE).getBoolean("dark",true);store=Store(applicationContext);source=PhoneSource(applicationContext);capture=Capture(store,source)
  capture.onUpdate={values->runOnUiThread{if(firstSample==0L)firstSample=values.first().t;elapsed=(values.last().t-firstSample)/1e9;live=values}}
  capture.onFinished={r,raw,s->lifecycleScope.launch(errors){running=false;busy=true;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);try{val a=withContext(Dispatchers.Default){runCatching{Dsp.analyze(raw,r.config,r.range,r.direction)}.getOrNull()};withContext(Dispatchers.IO){store.finish(r.id,a,if(a==null && s=="Valmis")"Liian vähän näytteitä" else s)};refresh();selected=records.find{it.id==r.id};result=a;tab=2;status="${selected?.name}: $s • ${raw.size} näytettä tallessa"}catch(e:Exception){status="Analyysi/tallennus epäonnistui: ${e.message}. Raakatiedosto säilytetty."}finally{busy=false}}}
  lifecycleScope.launch(errors){withContext(Dispatchers.IO){store.recover()};refresh()}
  setContent{MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFF55DAC8),secondary=Color(0xFF9ABEFF),background=Color(0xFF0B111C),surface=Color(0xFF131E2E),surfaceContainer=Color(0xFF111D2C),surfaceContainerHighest=Color(0xFF182538),onSurface=Color(0xFFECF3F8))else lightColorScheme()){Surface(Modifier.fillMaxSize()){App()}}}
 }
 override fun onStop(){super.onStop();if(running)capture.stop("Keskeytynyt: sovellus taustalla")}
 private suspend fun refresh(){withContext(Dispatchers.IO){val r=store.list();val m=store.machines();withContext(Dispatchers.Main){records=r;machines=m}}}
 private fun open(r:Record,compare:Boolean=false,reanalyze:Boolean=false){lifecycleScope.launch(errors){busy=true;try{val a=withContext(Dispatchers.Default){val fresh=Dsp.analyze(store.read(r.id),r.config,r.range,r.direction);if(r.analysis.isNotBlank() && !reanalyze)savedResult(r.analysis,fresh.signal)else fresh};if(compare){overlay=a;status=if(selected?.point==r.point && selected?.machine==r.machine && selected?.mounting==r.mounting && selected?.direction==r.direction && selected?.config==r.config && selected?.sensor==r.sensor)"Vertailu: ${r.name}" else "Vertailun asetukset, kierrosluku, anturi tai mittauspiste eroavat: tulokset eivät ole suoraan vertailukelpoisia."}else{selected=r;result=a;overlay=null;canSaveAnalysis=reanalyze};tab=2}catch(e:Exception){status="Mittauksen avaaminen epäonnistui: ${e.message}"}finally{busy=false}}}
 private fun saveFile(name:String,writer:(java.io.OutputStream)->Unit){pendingExport=writer;export.launch(name)}
 @Composable private fun App(){
  val scroll = rememberScrollState()
  LaunchedEffect(tab) { scroll.scrollTo(0) }
  Scaffold(bottomBar={NavigationBar{listOf("Koti","Mittaa","FFT","Data","Kone","Ohje").forEachIndexed{i,title->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("◈","◎","∿","▤","⚙","?")[i])},label={Text(title,style=MaterialTheme.typography.labelSmall,maxLines=1,softWrap=false)})}}}){pad->Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   Text("ULTIMATE",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text("Vibration Analyzer",style=MaterialTheme.typography.headlineMedium)
   if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
   if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
   when(tab){0->Home();1->Measurement();2->Analysis();3->History();4->Machines();else->Help()}
  }}
 }
 @Composable private fun Panel(title:String,body:@Composable ColumnScope.()->Unit){Card(shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium);body()}}}
 @Composable private fun Home(){
  Button(onClick={tab=1},modifier=Modifier.fillMaxWidth(),enabled=!busy){Text(if(running)"Palaa mittaukseen" else "Aloita uusi mittaus")}
  Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Card(onClick={tab=3},modifier=Modifier.weight(1f)){Column(Modifier.padding(20.dp)){Text("${records.size}",style=MaterialTheme.typography.headlineLarge);Text("Mittausta")}};Card(onClick={tab=4},modifier=Modifier.weight(1f)){Column(Modifier.padding(20.dp)){Text("${machines.size}",style=MaterialTheme.typography.headlineLarge);Text("Konetta")}}}
  records.firstOrNull()?.let{r->Panel("Viimeisin mittaus"){Text(r.name);Text("${r.machine} / ${r.point}\n${date(r.date)}");Button(onClick={open(r)},enabled=!busy){Text("Avaa tulos ja FFT")}}}
  Panel("Kunnon trendi • kiihtyvyys RMS"){val last=records.firstOrNull();val trend=records.filter{it.machine==last?.machine && it.point==last?.point && it.config==last?.config && it.mounting==last?.mounting && it.direction==last?.direction && it.analysis.isNotBlank()}.reversed();if(last==null)Text("Ensimmäisen mittauksen jälkeen näet tässä saman pisteen kunnon kehityksen.")else Text("${last.machine} / ${last.point} • samat asetukset");if(last!=null)Plot(trend.mapIndexed{i,r->i.toDouble() to JSONObject(r.analysis).optDouble("rms",0.0)},emptyList(),"mittausjärjestys","m/s² RMS");Text("${trend.size} vertailukelpoista mittausta");TextButton(onClick={tab=3}){Text("Avaa historia")}}
  Panel("Mittausperiaate"){Text("Kiinnitä puhelin jäykästi. Mittaukset tallentuvat automaattisesti myös ilman verkkoyhteyttä. Puhelin ei ole kalibroitu teollisuusanturi.")}
 }
 @Composable private fun Measurement(){
  val prefs=remember{getSharedPreferences("uva-draft",MODE_PRIVATE)}
  var name by remember{mutableStateOf(prefs.getString("name","")!!)};var machine by remember{mutableStateOf(prefs.getString("machine","")!!)};var point by remember{mutableStateOf(prefs.getString("point","")!!)};var mounting by remember{mutableStateOf(prefs.getString("mounting","Jäykkä kiinnitys")!!)};var direction by remember{mutableStateOf(prefs.getString("direction","Radiaalinen")!!)};var notes by remember{mutableStateOf(prefs.getString("notes","")!!)}
  var cfg by remember{mutableStateOf(runCatching{config(JSONObject(prefs.getString("config","{}")!!))}.getOrDefault(Config()))}
  fun persist(){prefs.edit().putString("name",name).putString("machine",machine).putString("point",point).putString("mounting",mounting).putString("direction",direction).putString("notes",notes).putString("config",cfg.json().toString()).apply()}
  Panel("Mittauskohde"){
   if(machines.isNotEmpty())Choices("Tallennetut koneet",machines.map{it.first},machine,!running){machine=it;persist()}
   Entry("Mittauksen nimi",name,!running){name=it;persist()};Entry("Kone",machine,!running){machine=it;persist()};Entry("Mittauspiste",point,!running){point=it;persist()};Entry("Suunta",direction,!running){direction=it;persist()};Entry("Kiinnitystapa",mounting,!running){mounting=it;persist()};Entry("Muistiinpanot",notes,!running){notes=it;persist()}
  }
  Panel("Asetukset"){
   NumberField("Kesto 1–120 s",cfg.seconds.toString(),!running){v->v.toIntOrNull()?.takeIf{it in 1..120}?.let{cfg=cfg.copy(seconds=it);persist()}}
   NumberField("Pyydetty näytetaajuus 20–1000 Hz",cfg.rate.toString(),!running){v->v.toIntOrNull()?.takeIf{it in 20..1000}?.let{cfg=cfg.copy(rate=it);persist()}}
   NumberField("Fmax Hz",cfg.fmax.toString(),!running){v->v.toDoubleOrNull()?.takeIf{it>cfg.highpass && it.isFinite()}?.let{cfg=cfg.copy(fmax=it);persist()}}
   NumberField("Integroinnin alaraja Hz",cfg.highpass.toString(),!running){v->v.toDoubleOrNull()?.takeIf{it>0 && it<cfg.fmax}?.let{cfg=cfg.copy(highpass=it);persist()}}
   NumberField("Kierrosluku RPM (0 = tuntematon)",cfg.rpm.toString(),!running){v->v.toDoubleOrNull()?.takeIf{it>=0 && it.isFinite()}?.let{cfg=cfg.copy(rpm=it);persist()}}
   Choices("FFT-pisteet",listOf("256","512","1024","2048","4096","8192"),cfg.fft.toString(),!running){cfg=cfg.copy(fft=it.toInt());persist()}
   Choices("Akseli",listOf("X","Y","Z","XYZ"),listOf("X","Y","Z","XYZ")[cfg.axis],!running){cfg=cfg.copy(axis=listOf("X","Y","Z","XYZ").indexOf(it));persist()}
   Choices("Ikkuna",listOf("Hann","Hamming","Flat Top","Blackman","Rectangular"),cfg.window,!running){cfg=cfg.copy(window=it);persist()}
   Choices("Keskiarvoistus",listOf("RMS","Linear","Exponential"),cfg.method,!running){cfg=cfg.copy(method=it);persist()}
   Choices("Keskiarvojen enimmäismäärä",listOf("1","4","8","16","32"),cfg.averages.toString(),!running){cfg=cfg.copy(averages=it.toInt());persist()}
   Text("Δf ≈ %.3f Hz • lohko %.2f s • %d positiivista viivaa. Todellinen resoluutio lasketaan mitatusta näytetaajuudesta.".format(cfg.rate.toDouble()/cfg.fft,cfg.fft.toDouble()/cfg.rate,cfg.fft/2))
   OutlinedButton(onClick={prefs.edit().putString("profile",cfg.json().toString()).apply();status="Asetusprofiili tallennettu"},enabled=!running){Text("Tallenna profiili")}
   TextButton(onClick={cfg=config(JSONObject(prefs.getString("profile","{}")!!));persist()},enabled=!running){Text("Lataa profiili")}
  }
  Panel("Anturi"){Text(source.description());Text("Raaka kiihtyvyys; painovoiman DC poistetaan akselikohtaisesti. Analoginen kaistanleveys ei ole Android-metatiedoista varmennettavissa.")}
  if(running){Panel("Mittaus käynnissä"){LinearProgressIndicator(progress={ (elapsed/cfg.seconds).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth());Text("Kulunut %.1f s • jäljellä %.1f s".format(elapsed,(cfg.seconds-elapsed).coerceAtLeast(0.0)));val mean=live.map{it.x}.average().takeIf{it.isFinite()}?:0.0;val rms=sqrt(live.map{(it.x-mean).pow(2)}.average()).takeIf{it.isFinite()}?:0.0;Text("X RMS %.4f • Peak %.4f m/s² (viimeiset näytteet, DC poistettu)".format(rms,live.maxOfOrNull{abs(it.x-mean)}?:0.0));Text("X %.3f • Y %.3f • Z %.3f m/s²".format(live.lastOrNull()?.x?:0.0,live.lastOrNull()?.y?:0.0,live.lastOrNull()?.z?:0.0));val fs=if(live.size>1)(live.size-1)*1e9/(live.last().t-live.first().t)else 0.0;Text("Toteutunut näytetaajuus %.1f Hz".format(fs));Plot(live.mapIndexed{i,r->i.toDouble() to r.x},emptyList(),"näyte","X m/s²");Text("Näytön sulkeminen tai sovelluksen siirtäminen taustalle päättää mittauksen ja säilyttää datan.")}}
  Button(onClick={if(running)capture.stop()else lifecycleScope.launch(errors){busy=true;try{val r=withContext(Dispatchers.IO){store.create(name,machine,point,direction,mounting,notes,cfg,source.description(),source.sensor?.maximumRange?.toDouble()?:0.0)};live=emptyList();firstSample=0L;elapsed=0.0;running=true;window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);capture.start(r);status="Mittaus käynnissä • automaattinen tallennus"}catch(e:Exception){running=false;status="Mittaus epäonnistui: ${e.message}"}finally{busy=false}}},enabled=!busy && (running || (name.isNotBlank() && machine.isNotBlank() && point.isNotBlank())),modifier=Modifier.fillMaxWidth()){Text(if(running)"Lopeta ja säilytä mittaus" else "Aloita mittaus")}
 }
 @Composable private fun Analysis(){val r=selected;val a=result;if(r==null || a==null){Text("Valitse mittaus historiasta tai tee uusi mittaus.");return}
  var unit by remember{mutableStateOf("m/s²")};var log by remember{mutableStateOf(false)};var rmsMode by remember{mutableStateOf(false)};var cpm by remember{mutableStateOf(false)};var rename by remember(r.id){mutableStateOf(r.name)}
  Panel(r.name){Text("${r.machine} / ${r.point} • ${date(r.date)}\n${r.direction} • ${r.mounting} • ${r.status}");Entry("Nimi",rename,true){rename=it};TextButton(onClick={lifecycleScope.launch(errors){withContext(Dispatchers.IO){store.rename(r.id,rename)};refresh();selected=records.find{it.id==r.id}}},enabled=rename.isNotBlank()){Text("Nimeä uudelleen")};TextButton(onClick={getSharedPreferences("uva-draft",MODE_PRIVATE).edit().putString("name",r.name).putString("machine",r.machine).putString("point",r.point).putString("direction",r.direction).putString("mounting",r.mounting).putString("notes",r.notes).putString("config",r.config.json().toString()).apply();tab=1},enabled=!running){Text("Mittaa sama piste uudelleen")};Text("Tallennettu analyysiversio: ${runCatching{JSONObject(r.analysis).getString("version")}.getOrDefault("ei tulosta")} • nykyinen moottori ${Dsp.VERSION}");TextButton(onClick={open(r,reanalyze=true)},enabled=!busy){Text("Analysoi raakadata uudelleen")};TextButton(onClick={lifecycleScope.launch(errors){withContext(Dispatchers.IO){store.finish(r.id,a,r.status)};canSaveAnalysis=false;refresh();selected=records.find{it.id==r.id};status="Uusi analyysiversio tallennettu; aiemmat säilytetty"}},enabled=canSaveAnalysis && !busy){Text("Tallenna uudelleenanalyysi")}}
  Panel("Tunnusluvut"){Text("%.4f m/s² RMS\n%.4f m/s² Peak\n%.4f m/s² Peak-to-Peak\n%.3f mm/s RMS (%.1f–%.1f Hz)".format(a.rms,a.peak,a.p2p,a.velocity,r.config.highpass,min(r.config.fmax,a.fs*.4)),style=MaterialTheme.typography.titleLarge);Text("Crest %.2f • kurtosis %.2f • skewness %.2f".format(if(a.rms>0)a.peak/a.rms else 0.0,a.kurtosis,a.skewness));Text("XYZ-tilassa RMS ja spektri ovat akselien neliösumma; aikasignaali, P-P ja momentit näyttävät X-akselin.")}
  Panel("FFT-spektri"){
   Choices("Suure",listOf("m/s²","g","mm/s","µm","PSD"),unit,true){unit=it};Row{TextButton(onClick={log=!log}){Text(if(log)"Log" else "Linear")};TextButton(onClick={rmsMode=!rmsMode}){Text(if(rmsMode)"RMS" else "Peak")};TextButton(onClick={cpm=!cpm}){Text(if(cpm)"CPM" else "Hz")}}
   fun points(v:Result):List<Pair<Double,Double>> = v.f.indices.filter{v.f[it]<=min(r.config.fmax,v.fs*.4) && (unit !in listOf("mm/s","µm") || v.f[it]>=r.config.highpass)}.map{i->val f=v.f[i];val amplitude=when(unit){"g"->v.peakSpectrum[i]/9.80665;"mm/s"->v.peakSpectrum[i]*1000/(2*PI*f);"µm"->v.peakSpectrum[i]*1e6/(2*PI*f).pow(2);"PSD"->v.psd[i];else->v.peakSpectrum[i]};val display=amplitude/(if(rmsMode && unit!="PSD" && i>0 && i<v.n/2)sqrt(2.0)else 1.0);(f*if(cpm)60 else 1) to (if(log)log10(display.coerceAtLeast(1e-12)) else display)}
   Plot(points(a),overlay?.let{points(it)}?:emptyList(),if(cpm)"CPM" else "Hz",if(log)"log10 $unit (${if(rmsMode)"RMS"else "Peak"})"else "$unit ${if(unit=="PSD")"(m/s²)²/Hz"else if(rmsMode)"RMS"else "Peak"}",r.config.rpm/60*(if(cpm)60 else 1))
   Text("Pyyhkäise ja nipistä kuvaajaa. Δf %.3f Hz • FFT %d • %d keskiarvoa • Fs %.2f Hz".format(a.fs/a.n,a.n,a.segments,a.fs))
   val peaks=(1 until a.f.size-1).filter{a.f[it]<=min(r.config.fmax,a.fs*.4) && a.peakSpectrum[it]>a.peakSpectrum[it-1] && a.peakSpectrum[it]>a.peakSpectrum[it+1]}.sortedByDescending{a.peakSpectrum[it]}.take(8)
   peaks.forEach{Text("%.2f Hz  •  %.5f m/s² Peak  •  %.2fX".format(a.f[it],a.peakSpectrum[it],if(r.config.rpm>0)a.f[it]/(r.config.rpm/60)else 0.0))}
  }
  Panel("Aikasignaali • DC poistettu"){val step=max(1,a.signal.size/1000);Plot(a.signal.indices.step(step).map{it/a.fs to a.signal[it]},emptyList(),"s","m/s²")}
  Panel("Mittauksen laatu"){Text("Ajoitusvaihtelu %.1f %% • katkoksia %d".format(a.jitter*100,a.gaps));a.warnings.forEach{Text("• $it")}}
  a.findings.forEach{f->Panel(f.title){Text("Luotettavuus: ${f.confidence}",color=MaterialTheme.colorScheme.primary);Text(f.evidence);Text(f.recommendation);Text("Vakavuutta ei luokitella ilman validoituja kohdekohtaisia rajoja.",style=MaterialTheme.typography.bodySmall)}}
  Panel("Vienti"){
   Button(onClick={saveFile("${r.id}-signal.csv"){out->out.bufferedWriter().use{w->w.write("timestamp_ns,x_m_s2,y_m_s2,z_m_s2\n");store.read(r.id).forEach{w.write("${it.t},${it.x},${it.y},${it.z}\n")}}}}){Text("Raakasignaali CSV")}
   Button(onClick={saveFile("${r.id}-spectrum.csv"){out->out.bufferedWriter().use{w->w.write("frequency_hz,acceleration_peak_m_s2,psd_m2_s4_hz\n");a.f.indices.forEach{w.write("${a.f[it]},${a.peakSpectrum[it]},${a.psd[it]}\n")}}}}){Text("FFT CSV")}
   Button(onClick={saveFile("${r.id}-analysis.json"){it.write(a.json().put("name",r.name).put("config",r.config.json()).toString(2).toByteArray())}}){Text("Analyysi JSON")}
   Button(onClick={saveFile("${r.id}-report.pdf"){Reports.pdf(it,r,a)}}){Text("PDF-raportti")}
   Button(onClick={saveFile("${r.id}-spectrum.png"){Reports.png(it,r,a)}}){Text("FFT PNG")}
  }
 }
 @Composable private fun History(){var query by remember{mutableStateOf("")};var deletion by remember{mutableStateOf<Record?>(null)}
  Entry("Hae nimi, kone, piste, päivämäärä tai tila",query,true){query=it}
  Button(onClick={saveFile("vibration-backup.json"){it.write(store.backup().toByteArray())}},enabled=!running){Text("Varmuuskopioi kaikki")};OutlinedButton(onClick={restore.launch(arrayOf("application/json","application/octet-stream"))},enabled=!running && !busy){Text("Palauta varmuuskopio (lisää kopiot)")}
  records.filter{"${it.name} ${it.machine} ${it.point} ${date(it.date)} ${it.status}".contains(query,true)}.forEach{r->Panel(r.name){Text("${r.machine} / ${r.point}\n${date(r.date)} • ${r.status}");Row{TextButton(onClick={open(r)},enabled=!busy){Text("Avaa")};TextButton(onClick={open(r,true)},enabled=selected!=null && !busy){Text("Vertaa")};TextButton(onClick={deletion=r},enabled=!running){Text("Poista")}}}}
  deletion?.let{r->AlertDialog(onDismissRequest={deletion=null},title={Text("Poista ${r.name}?")},text={Text("Vain tämä mittaus ja sen analyysiversiot poistetaan. Toimintoa ei voi perua.")},confirmButton={TextButton(onClick={lifecycleScope.launch(errors){withContext(Dispatchers.IO){store.delete(r.id)};if(selected?.id==r.id){selected=null;result=null};refresh();deletion=null}}){Text("Poista")}},dismissButton={TextButton(onClick={deletion=null}){Text("Peruuta")}})}
 }
 @Composable private fun Machines(){var name by remember{mutableStateOf("")};var details by remember{mutableStateOf("")};Panel("Koneen tiedot"){Entry("Nimi / tunniste",name,true){name=it};Entry("Tehdas, linja, tyyppi, valmistaja, malli, teho, laakerit ja käyttöolosuhteet",details,true){details=it};Button(onClick={lifecycleScope.launch(errors){withContext(Dispatchers.IO){store.machine(name,details)};refresh();name="";details=""}},enabled=name.isNotBlank()){Text("Tallenna kone")}}
  machines.forEach{m->Panel(m.first){Text(m.second);Text("Mittauspisteet: "+records.filter{it.machine==m.first}.map{it.point}.distinct().joinToString());TextButton(onClick={getSharedPreferences("uva-draft",MODE_PRIVATE).edit().putString("machine",m.first).apply();tab=1}){Text("Mittaa kone")};TextButton(onClick={name=m.first;details=m.second}){Text("Muokkaa")}}}
 }
 @Composable private fun Help(){TextButton(onClick={dark=!dark;getSharedPreferences("uva-ui",MODE_PRIVATE).edit().putBoolean("dark",dark).apply()}){Text("Vaihda tumma / vaalea teema")};Panel("Mittausopas"){Text("1. Valitse jäykkä kohta läheltä laakeripesää. Kiinnitä puhelin turvallisesti koneen pysäytettynä; älä pidä puhelinta kädessä pyörivien osien lähellä.\n2. Säilytä suunta, kuorma, RPM ja kiinnitys samoina trendimittauksissa.\n3. FFT erottaa signaalin taajuuskomponenteiksi. Δf = Fs/N.\n4. RMS kuvaa tehollisarvoa. Siniaallon Peak = √2 × RMS; P-P = 2 × Peak.\n5. 1X = RPM/60; 2X on sen toinen harmoninen. 1X voi liittyä epätasapainoon, resonanssiin tai taipumaan. 2X ei yksin osoita linjausvirhettä.\n6. Laakeritaajuudet riippuvat vierintäelinten määrästä, mitoista, kosketuskulmasta ja kierrosluvusta. Verhokäyrä erottaa korkeataajuisen resonanssin amplitudimodulaatiota. Puhelimen anturikaista ei yleensä riitä varhaisten vikojen varmentamiseen.\n7. Standardin ISO 20816 rajoja ei sovelleta automaattisesti. Tämä sovellus ei anna standardinmukaista kuntoluokitusta.\n8. Nopeus ja siirtymä ovat taajuustason arvioita; pienet taajuudet rajataan pois.\n9. Taustalle siirtyminen keskeyttää mittauksen ja säilyttää kerätyn datan. Prosessin kaatumisessa viimeinen puskurin osa voi kadota; aiemmat näytteet palautuvat keskeytyneenä.\n10. Varmuuskopioi ennen päivitystä. APK:n allekirjoituksen tulee vastata asennettua versiota; sovelluksen poisto poistaa paikalliset tiedot.")}
  Panel("Simuloidut opetussignaalit"){listOf("1X-esimerkki","1X + 2X -esimerkki","Harmoniset").forEachIndexed{index,label->Button(onClick={lifecycleScope.launch(errors){busy=true;try{val cfg=Config(rate=512,fft=2048,fmax=180.0,rpm=1500.0);val raw=List(4096){i->val t=i/512.0;Raw(i*1953125L,sin(2*PI*25*t)+(if(index>=1).7*sin(2*PI*50*t)else 0.0)+(if(index==2)(3..6).sumOf{.4*sin(2*PI*25*it*t)}else 0.0),0.0,9.81)};val r=withContext(Dispatchers.IO){store.create("SIMULOITU • $label","SIMULAATIO","Opetus","Radiaalinen","Simuloitu","Ei koneen mittaus",cfg,"Synteettinen generaattori",100.0)};withContext(Dispatchers.IO){java.io.DataOutputStream(store.file(r.id).outputStream()).use{o->raw.forEach{o.writeLong(it.t);o.writeDouble(it.x);o.writeDouble(it.y);o.writeDouble(it.z)}};store.finish(r.id,Dsp.analyze(raw,cfg),"SIMULOITU")};refresh();open(records.first{it.id==r.id})}finally{busy=false}}},enabled=!busy && !running){Text(label)}}}
 }
}
@Composable private fun Entry(label:String,value:String,enabled:Boolean,onChange:(String)->Unit){OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},enabled=enabled,modifier=Modifier.fillMaxWidth())}
@Composable private fun NumberField(label:String,value:String,enabled:Boolean,onChange:(String)->Unit){var text by remember(value){mutableStateOf(value)};Entry(label,text,enabled){text=it;onChange(it)}}
@Composable private fun Choices(label:String,options:List<String>,value:String,enabled:Boolean,onChange:(String)->Unit){Text(label,style=MaterialTheme.typography.labelLarge);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){options.forEach{FilterChip(selected=it==value,onClick={onChange(it)},enabled=enabled,label={Text(it)})}}}
private fun date(t:Long)=SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(Date(t))
@Composable private fun Plot(points:List<Pair<Double,Double>>,other:List<Pair<Double,Double>>,xLabel:String,yLabel:String,order:Double=0.0){
 var zoom by remember(points){mutableStateOf(1f)};var pan by remember(points){mutableStateOf(0f)};var cursorA by remember(points){mutableStateOf<Double?>(null)};var cursorB by remember(points){mutableStateOf<Double?>(null)}
 val all=points+other;val maxX=(all.maxOfOrNull{it.first}?:1.0).coerceAtLeast(1e-9);val minY=min(0.0,all.minOfOrNull{it.second}?:0.0);val maxY=(all.maxOfOrNull{it.second}?:1.0).coerceAtLeast(minY+1e-9)
 Text("$yLabel • %.4g … %.4g".format(minY,maxY),style=MaterialTheme.typography.labelSmall)
 Canvas(Modifier.fillMaxWidth().height(210.dp).background(Color(0xFF0A1220),RoundedCornerShape(12.dp)).pointerInput(points,zoom,pan){detectTapGestures{pos->val x=((pos.x/size.width-pan)/zoom*maxX).coerceIn(0.0,maxX);if(cursorA==null || cursorB!=null){cursorA=x;cursorB=null}else cursorB=x}}.pointerInput(points){detectTransformGestures{_,p,z,_->zoom=(zoom*z).coerceIn(1f,20f);pan=(pan+p.x/size.width).coerceIn(1f-zoom,0f)}}){
  for(i in 0..4){val y=size.height*i/4;drawLine(Color(0xFF253347),Offset(0f,y),Offset(size.width,y))}
  fun position(p:Pair<Double,Double>)=Offset(((p.first/maxX*zoom+pan)*size.width).toFloat(),(size.height*(1-(p.second-minY)/(maxY-minY)*.92)).toFloat())
  fun curve(data:List<Pair<Double,Double>>,color:Color){for(i in 1 until data.size){val a=position(data[i-1]);val b=position(data[i]);if(a.x>=0 && b.x<=size.width)drawLine(color,a,b,2f)}}
  if(order>0)for(i in 1..6){val x=((order*i/maxX*zoom+pan)*size.width).toFloat();if(x in 0f..size.width)drawLine(Color(0xFF596A96),Offset(x,0f),Offset(x,size.height),1f)}
  curve(points,Color(0xFF55DAC8));curve(other,Color(0xFFFFB56B));listOfNotNull(cursorA,cursorB).forEach{v->val x=((v/maxX*zoom+pan)*size.width).toFloat();if(x in 0f..size.width)drawLine(Color.White,Offset(x,0f),Offset(x,size.height),2f)}
 }
 Text("%.2f — %.2f $xLabel".format(-pan/zoom*maxX,(1-pan)/zoom*maxX),style=MaterialTheme.typography.labelSmall)
 if(cursorA!=null){val nearest=points.minByOrNull{abs(it.first-cursorA!!)};Text("A %.3f $xLabel • %.5g $yLabel".format(cursorA,nearest?.second?:0.0),style=MaterialTheme.typography.labelSmall)}
 if(cursorB!=null)Text("B %.3f • Δ %.3f $xLabel".format(cursorB,abs(cursorB!!-cursorA!!)),style=MaterialTheme.typography.labelSmall)
 if(order>0)Text("Pystyviivat: 1X–6X • 1X = %.2f $xLabel".format(order),style=MaterialTheme.typography.labelSmall)
}
