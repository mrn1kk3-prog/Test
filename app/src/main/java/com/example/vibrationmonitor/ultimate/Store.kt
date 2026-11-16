package com.example.vibrationmonitor.ultimate

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.util.UUID

data class Record(val id:String,val name:String,val date:Long,val machine:String,val point:String,val direction:String,val mounting:String,val notes:String,val config:Config,val status:String,val sensor:String,val range:Double,val analysis:String)
fun Config.json()=JSONObject().put("seconds",seconds).put("rate",rate).put("fft",fft).put("fmax",fmax).put("highpass",highpass).put("axis",axis).put("window",window).put("averages",averages).put("method",method).put("rpm",rpm)
fun config(j:JSONObject)=Config(j.optInt("seconds",10),j.optInt("rate",200),j.optInt("fft",1024),j.optDouble("fmax",80.0),j.optDouble("highpass",5.0),j.optInt("axis",0),j.optString("window","Hann"),j.optInt("averages",8),j.optString("method","RMS"),j.optDouble("rpm",1500.0))
fun Result.json()=JSONObject().put("version",Dsp.VERSION).put("fs",fs).put("jitter",jitter).put("gaps",gaps).put("clipped",clipped).put("fft",n).put("segments",segments).put("rms",rms).put("peak",peak).put("p2p",p2p).put("kurtosis",kurtosis).put("skewness",skewness).put("velocity",velocity).put("frequency",JSONArray(f.toList())).put("peakSpectrum",JSONArray(peakSpectrum.toList())).put("psd",JSONArray(psd.toList())).put("warnings",JSONArray(warnings)).put("findings",JSONArray(findings.map{JSONObject().put("title",it.title).put("evidence",it.evidence).put("confidence",it.confidence).put("recommendation",it.recommendation)}))
class Store(val context:Context):SQLiteOpenHelper(context,"ultimate-vibration.db",null,1){
 val rawDir=File(context.filesDir,"uva-signals").apply{mkdirs()}
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE measurements(id TEXT PRIMARY KEY, name TEXT NOT NULL, date INTEGER NOT NULL, machine TEXT, point TEXT, direction TEXT, mounting TEXT, notes TEXT, config TEXT, status TEXT, sensor TEXT, range REAL, analysis TEXT)");db.execSQL("CREATE TABLE analyses(id INTEGER PRIMARY KEY AUTOINCREMENT, measurement TEXT NOT NULL, date INTEGER, result TEXT)");db.execSQL("CREATE TABLE machines(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, details TEXT)")}
 override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){error("Explicit migration required: $old → $new")}
 fun recover(){writableDatabase.execSQL("UPDATE measurements SET status='Keskeytynyt' WHERE status='Mitataan'")}
 fun file(id:String):File{require(id.matches(Regex("[a-f0-9-]{36}")));return File(rawDir,"$id.bin")}
 fun create(name:String,machine:String,point:String,direction:String,mounting:String,notes:String,cfg:Config,sensor:String,range:Double):Record{
  val r=Record(UUID.randomUUID().toString(),name.ifBlank{"Mittaus"},System.currentTimeMillis(),machine,point,direction,mounting,notes,cfg,"Mitataan",sensor,range,"")
  file(r.id).createNewFile();insert(r);return r
 }
 fun insert(r:Record){val v=ContentValues().apply{put("id",r.id);put("name",r.name);put("date",r.date);put("machine",r.machine);put("point",r.point);put("direction",r.direction);put("mounting",r.mounting);put("notes",r.notes);put("config",r.config.json().toString());put("status",r.status);put("sensor",r.sensor);put("range",r.range);put("analysis",r.analysis)};writableDatabase.insertOrThrow("measurements",null,v)}
 fun list():List<Record>{val out=mutableListOf<Record>();readableDatabase.rawQuery("SELECT * FROM measurements ORDER BY date DESC",null).use{c->while(c.moveToNext()){fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))?:"";out+=Record(s("id"),s("name"),s("date").toLong(),s("machine"),s("point"),s("direction"),s("mounting"),s("notes"),config(JSONObject(s("config"))),s("status"),s("sensor"),s("range").toDouble(),s("analysis"))}};return out}
 fun read(id:String):List<Raw>{val out=mutableListOf<Raw>();DataInputStream(BufferedInputStream(FileInputStream(file(id)))).use{input->while(true){try{out+=Raw(input.readLong(),input.readDouble(),input.readDouble(),input.readDouble())}catch(e:EOFException){break}}};return out}
 fun finish(id:String,result:Result?,status:String){val db=writableDatabase;db.beginTransaction();try{val v=ContentValues().apply{put("status",status);if(result!=null)put("analysis",result.json().toString())};db.update("measurements",v,"id=?",arrayOf(id));if(result!=null)db.insertOrThrow("analyses",null,ContentValues().apply{put("measurement",id);put("date",System.currentTimeMillis());put("result",result.json().toString())});db.setTransactionSuccessful()}finally{db.endTransaction()}}
 fun rename(id:String,name:String){require(name.isNotBlank());writableDatabase.execSQL("UPDATE measurements SET name=? WHERE id=?",arrayOf(name,id))}
 fun delete(id:String){writableDatabase.beginTransaction();try{writableDatabase.delete("analyses","measurement=?",arrayOf(id));writableDatabase.delete("measurements","id=?",arrayOf(id));writableDatabase.setTransactionSuccessful()}finally{writableDatabase.endTransaction()};file(id).delete()}
 fun machines():List<Pair<String,String>>{val out=mutableListOf<Pair<String,String>>();readableDatabase.rawQuery("SELECT name,details FROM machines ORDER BY name",null).use{while(it.moveToNext())out+=it.getString(0) to it.getString(1)};return out}
 fun machine(name:String,details:String){require(name.isNotBlank());writableDatabase.insertWithOnConflict("machines",null,ContentValues().apply{put("name",name);put("details",details)},SQLiteDatabase.CONFLICT_REPLACE)}
 fun versions(id:String):JSONArray{val out=JSONArray();readableDatabase.rawQuery("SELECT date,result FROM analyses WHERE measurement=? ORDER BY id",arrayOf(id)).use{while(it.moveToNext())out.put(JSONObject().put("date",it.getLong(0)).put("result",it.getString(1)))};return out}
 fun backup():String=JSONObject().put("schema",1).put("machines",JSONArray(machines().map{JSONObject().put("name",it.first).put("details",it.second)})).put("measurements",JSONArray(list().map{r->JSONObject().put("id",r.id).put("name",r.name).put("date",r.date).put("machine",r.machine).put("point",r.point).put("direction",r.direction).put("mounting",r.mounting).put("notes",r.notes).put("config",r.config.json()).put("status",r.status).put("sensor",r.sensor).put("range",r.range).put("analysis",r.analysis).put("versions",versions(r.id)).put("raw",JSONArray(read(r.id).map{JSONArray(listOf(it.t,it.x,it.y,it.z))}))})).toString()
 fun restore(text:String):Int{val root=JSONObject(text);require(root.getInt("schema")==1);val a=root.getJSONArray("measurements");require(a.length()<=10000);val staged=mutableListOf<Pair<Record,File>>()
  try{for(i in 0 until a.length()){val j=a.getJSONObject(i);val id=UUID.randomUUID().toString();val r=Record(id,j.getString("name"),j.getLong("date"),j.getString("machine"),j.getString("point"),j.getString("direction"),j.getString("mounting"),j.getString("notes"),config(j.getJSONObject("config")),j.getString("status"),j.getString("sensor"),j.getDouble("range"),j.getString("analysis"));val f=file(id);staged+=r to f;DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use{out->val rows=j.getJSONArray("raw");require(rows.length()<=1000000);for(k in 0 until rows.length()){val row=rows.getJSONArray(k);out.writeLong(row.getLong(0));for(axis in 1..3){val v=row.getDouble(axis);require(v.isFinite());out.writeDouble(v)}}}}
   writableDatabase.beginTransaction();try{staged.forEachIndexed{index,pair->insert(pair.first);val history=a.getJSONObject(index).optJSONArray("versions")?:JSONArray();for(v in 0 until history.length()){val item=history.getJSONObject(v);writableDatabase.insertOrThrow("analyses",null,ContentValues().apply{put("measurement",pair.first.id);put("date",item.getLong("date"));put("result",item.getString("result"))})}};val m=root.optJSONArray("machines")?:JSONArray();for(i in 0 until m.length()){val j=m.getJSONObject(i);machine(j.getString("name"),j.getString("details"))};writableDatabase.setTransactionSuccessful()}finally{writableDatabase.endTransaction()};return staged.size
  }catch(e:Exception){staged.forEach{it.second.delete()};throw e}
 }
}
