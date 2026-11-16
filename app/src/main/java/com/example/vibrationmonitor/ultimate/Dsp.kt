package com.example.vibrationmonitor.ultimate

import kotlin.math.*

data class Raw(val t: Long, val x: Double, val y: Double, val z: Double)
data class Config(val seconds: Int = 10, val rate: Int = 200, val fft: Int = 1024, val fmax: Double = 80.0, val highpass: Double = 5.0, val axis: Int = 0, val window: String = "Hann", val averages: Int = 8, val method: String = "RMS", val rpm: Double = 1500.0)
data class Finding(val title: String, val evidence: String, val confidence: String, val recommendation: String)
data class Result(val fs: Double, val jitter: Double, val gaps: Int, val clipped: Boolean, val n: Int, val segments: Int, val f: DoubleArray, val peakSpectrum: DoubleArray, val psd: DoubleArray, val signal: DoubleArray, val rms: Double, val peak: Double, val p2p: Double, val kurtosis: Double, val skewness: Double, val velocity: Double, val warnings: List<String>, val findings: List<Finding>)
object Dsp {
 const val VERSION = "uva-1.0"
 fun fft(a: DoubleArray): Pair<DoubleArray, DoubleArray> {
  val n=a.size; require(n>=2 && n and (n-1)==0)
  val r=a.copyOf(); val im=DoubleArray(n); var j=0
  for(i in 1 until n) { var b=n shr 1; while(j and b != 0){j=j xor b;b=b shr 1};j=j xor b;if(i<j){val t=r[i];r[i]=r[j];r[j]=t} }
  var len=2
  while(len<=n){val angle=-2*PI/len;for(start in 0 until n step len){for(k in 0 until len/2){val c=cos(angle*k);val s=sin(angle*k);val p=start+k;val q=p+len/2;val vr=r[q]*c-im[q]*s;val vi=r[q]*s+im[q]*c;r[q]=r[p]-vr;im[q]=im[p]-vi;r[p]+=vr;im[p]+=vi}};len*=2}
  return r to im
 }
 fun window(n:Int, type:String)=DoubleArray(n){i-> val a=2*PI*i/n;when(type){"Hamming"->.54-.46*cos(a);"Blackman"->.42-.5*cos(a)+.08*cos(2*a);"Flat Top"->.21557895-.41663158*cos(a)+.277263158*cos(2*a)-.083578947*cos(3*a)+.006947368*cos(4*a);"Rectangular"->1.0;else->.5-.5*cos(a)}}
 fun analyze(raw:List<Raw>, cfg:Config, range:Double=Double.MAX_VALUE, direction:String="Radiaalinen"):Result {
  require(raw.size>=64){"Vähintään 64 näytettä tarvitaan"}
  require(raw.zipWithNext().all{it.second.t>it.first.t}){"Aikaleimat eivät ole kasvavia"}
  val dt=raw.zipWithNext().map{(a,b)->(b.t-a.t)/1e9};val median=dt.sorted()[dt.size/2];val fs=(raw.size-1)*1e9/(raw.last().t-raw.first().t)
  val jitter=sqrt(dt.map{(it-median).pow(2)}.average())/median
  val gaps=dt.count{it>median*1.8};val clipped=raw.any{maxOf(abs(it.x),abs(it.y),abs(it.z))>=range*.98}
  // Resample each SIGNED axis. Never FFT the nonlinear vector magnitude.
  val axes=Array(3){DoubleArray(raw.size)};var j=0
  for(i in raw.indices){val t=raw.first().t+i*1e9/fs;while(j<raw.size-2 && raw[j+1].t<t)j++;val a=raw[j];val b=raw[j+1];val k=((t-a.t)/(b.t-a.t)).coerceIn(0.0,1.0);axes[0][i]=a.x+(b.x-a.x)*k;axes[1][i]=a.y+(b.y-a.y)*k;axes[2][i]=a.z+(b.z-a.z)*k}
  axes.forEach{a->val mean=a.average();for(i in a.indices)a[i]-=mean}
  val selected=if(cfg.axis==3)axes.toList() else listOf(axes[cfg.axis.coerceIn(0,2)])
  val signal=selected.first();val rms=sqrt(selected.sumOf{a->a.sumOf{it*it}/a.size});val peak=if(cfg.axis==3) sqrt(axes[0].indices.maxOf{i->axes.sumOf{it[i]*it[i]}}) else signal.maxOf{abs(it)}
  val variance=signal.sumOf{it*it}/signal.size
  val kurt=if(variance>0)signal.sumOf{it.pow(4)}/signal.size/variance.pow(2) else 0.0
  val skew=if(variance>0)signal.sumOf{it.pow(3)}/signal.size/variance.pow(1.5) else 0.0
  var n=64;while(n*2<=min(cfg.fft,raw.size))n*=2
  val w=window(n,cfg.window);val gain=w.sum();val energy=w.sumOf{it*it};val bins=n/2+1
  val amps=DoubleArray(bins);val psd=DoubleArray(bins)
  val starts=(0..raw.size-n step n/2).toList().takeLast(cfg.averages.coerceAtLeast(1))
  selected.forEach{axis->val ap=DoubleArray(bins);val pp=DoubleArray(bins)
   starts.forEachIndexed{s,start->val mean=(start until start+n).sumOf{axis[it]}/n;val (re,im)=fft(DoubleArray(n){(axis[start+it]-mean)*w[it]})
    for(k in 0 until bins){val power=re[k]*re[k]+im[k]*im[k];val factor=if(k==0 || k==n/2)1.0 else 2.0;val a=sqrt(power)*factor/gain;val p=power*factor/(fs*energy)
     when(cfg.method){"Linear"->ap[k]+=a/starts.size;"Exponential"->{ap[k]=if(s==0)a else .3*a+.7*ap[k]};else->ap[k]+=a*a/starts.size};pp[k]+=p/starts.size}
   }
   for(k in 0 until bins){val a=if(cfg.method=="RMS")sqrt(ap[k]) else ap[k];amps[k]+=a*a;psd[k]+=pp[k]}
  }
  for(k in amps.indices)amps[k]=sqrt(amps[k])
  val f=DoubleArray(bins){it*fs/n};val upper=min(cfg.fmax,fs*.4)
  val velocity=sqrt(f.indices.filter{f[it]>=cfg.highpass && f[it]<=upper}.sumOf{psd[it]*fs/n/(2*PI*f[it]).pow(2)})*1000
  val warnings=mutableListOf("Anturin analoginen kaista ja aliasoinnin esto eivät ole varmennettuja; ei kalibroitu teollisuusmittaus.")
  if(jitter>.1)warnings+="Näyteajoituksen vaihtelu yli 10 %; interpolointi ei korjaa menetettyä tietoa."
  if(gaps>0)warnings+="Näytekatkoksia: $gaps"
  if(clipped)warnings+="Anturin mahdollinen yliohjautuminen"
  if(cfg.fmax>fs*.4)warnings+="Fmax rajattu 0,4 × toteutunut näytteenottotaajuus."
  if(cfg.fft>raw.size)warnings+="Mittaus liian lyhyt pyydetylle FFT-koolle; käytetään $n pistettä."
  val findings=diagnose(f,amps,cfg.rpm,upper,jitter<=.1 && gaps==0 && !clipped,direction)
  return Result(fs,jitter,gaps,clipped,n,starts.size,f,amps,psd,signal,rms,peak,signal.max()-signal.min(),kurt,skew,velocity,warnings,findings)
 }
 fun diagnose(f:DoubleArray,a:DoubleArray,rpm:Double,upper:Double,quality:Boolean,direction:String):List<Finding>{
  if(!quality)return listOf(Finding("Mittaus epäluotettava","Katkokset, ajoitus tai yliohjaus estävät diagnoosin.","Ei arvioitavissa","Uusi mittaus jäykällä kiinnityksellä."))
  val x=rpm/60;val df=f[1];if(x<3*df || x*3>upper)return listOf(Finding("Kierroslukuanalyysi ei riitä","1X–3X ei erotu käytettävissä olevalla kaistalla tai RPM puuttuu.","Ei arvioitavissa","Tarkista RPM ja pidennä mittausta."))
  fun amp(order:Double)=a.indices.filter{abs(f[it]-x*order)<=df*1.5}.maxOfOrNull{a[it]}?:0.0
  val one=amp(1.0);val two=amp(2.0);val max=a.indices.filter{f[it]>=df && f[it]<=upper}.maxOf{a[it]}
  val floor=a.indices.filter{f[it]>=df && f[it]<=upper}.map{a[it]}.sorted().let{it[it.size/2]}.coerceAtLeast(1e-7)
  val out=mutableListOf<Finding>()
  if(one>floor*8 && one>=max*.7 && two<one*.35)out+=Finding("Mahdollinen epätasapaino","1X %.2f Hz hallitsee; 2X/1X %.2f. Suunta: %s. Myös resonanssi tai taipunut akseli mahdollinen.".format(x,two/one,direction),"Alustava","Tarkista roottorin puhtaus. Vahvista vaihe- ja radiaalimittauksilla; älä tasapainota pelkän havainnon perusteella.")
  if(two>floor*8 && two>one*.4)out+=Finding("Mahdollinen linjausvirhe","1X %.2f ja 2X %.2f Hz; 2X korostuu. Myös väljyys voi selittää oireen.".format(x,x*2),"Alustava","Vertaa aksiaali- ja radiaalisuuntia sekä tarkista kytkin ja linjaus.")
  val orders=(2..6).filter{x*it<=upper && amp(it.toDouble())>max*.15 && amp(it.toDouble())>floor*8}
  if(orders.size>=3)out+=Finding("Mahdollinen mekaaninen väljyys","Useita harmonisia: $orders × %.2f Hz.".format(x),"Alustava","Tarkista kiinnitykset ja jalusta. Poissulje hankaus ja muu epälineaarisuus.")
  if(out.isEmpty())out+=Finding("Ei selkeää vikakuviota","Säännöt eivät löytäneet riittävää spektrikuviota. Tämä ei osoita konetta terveeksi.","Ei johtopäätöstä","Vertaa saman pisteen aiempaan mittaukseen.")
  out+=Finding("Laakerivian varhaistunnistus ei käytössä","Puhelimen analogista kaistaa ei ole varmennettu; verhokäyrädiagnoosia ei tehdä.","Ei arvioitavissa","Käytä varmennettua laajakaistaista anturia ja laakerigeometriaa.")
  return out
 }
}
