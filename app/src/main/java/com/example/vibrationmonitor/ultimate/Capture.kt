package com.example.vibrationmonitor.ultimate
import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.HandlerThread
import java.io.*

interface SampleSource { fun start(rate:Int,onSample:(Raw)->Unit):Boolean; fun stop() }
class PhoneSource(context:Context):SampleSource,SensorEventListener{
 private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
 val sensor:Sensor?=manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
 private var thread:HandlerThread?=null
 private var callback:((Raw)->Unit)?=null
 override fun start(rate:Int,onSample:(Raw)->Unit):Boolean{val s=sensor?:return false;callback=onSample;val t=HandlerThread("vibration-acquisition");t.start();thread=t;return manager.registerListener(this,s,(1000000/rate.coerceIn(20,1000)),0,Handler(t.looper))}
 override fun stop(){manager.unregisterListener(this);thread?.quitSafely();thread=null}
 override fun onSensorChanged(e:SensorEvent){callback?.invoke(Raw(e.timestamp,e.values[0].toDouble(),e.values[1].toDouble(),e.values[2].toDouble()))}
 override fun onAccuracyChanged(s:Sensor?,accuracy:Int){}
 fun description()=sensor?.let{"${it.name} • ${it.vendor}\nAlue ±${it.maximumRange} m/s² • resoluutio ${it.resolution} m/s²\nMin/max viive ${it.minDelay}/${it.maxDelay} µs • ${it.power} mA"}?:"Kiihtyvyysanturia ei löydy"
}
class Capture(private val store:Store,private val source:SampleSource){
 private var record:Record?=null;private var output:DataOutputStream?=null;private var fileStream:FileOutputStream?=null;private val samples=mutableListOf<Raw>();private var lastUpdate=0L
 var onUpdate:((List<Raw>)->Unit)?=null;var onFinished:((Record,List<Raw>,String)->Unit)?=null
 @Synchronized fun start(r:Record){check(record==null);record=r;samples.clear();lastUpdate=0;fileStream=FileOutputStream(store.file(r.id));output=DataOutputStream(BufferedOutputStream(fileStream));try{check(source.start(r.config.rate){sample(it)}){"Anturia ei voitu käynnistää"}}catch(e:Exception){stop("Epäonnistunut");throw e}}
 @Synchronized private fun sample(s:Raw){val r=record?:return;try{val out=output?:return;out.writeLong(s.t);out.writeDouble(s.x);out.writeDouble(s.y);out.writeDouble(s.z);samples+=s
  if(s.t-lastUpdate>250000000L){out.flush();fileStream?.fd?.sync();lastUpdate=s.t;onUpdate?.invoke(samples.takeLast(512))}
  if(s.t-samples.first().t>=r.config.seconds*1000000000L)stop("Valmis")
 }catch(e:Exception){stop("Tallennusvirhe")}}
 @Synchronized fun stop(status:String="Keskeytetty"){val r=record?:return;record=null;source.stop();var finalStatus=status;try{output?.flush();fileStream?.fd?.sync();output?.close()}catch(e:Exception){finalStatus="Tallennusvirhe"};output=null;fileStream=null;val copy=samples.toList();samples.clear();onFinished?.invoke(r,copy,finalStatus)}
}
