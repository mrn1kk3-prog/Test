package com.example.vibrationmonitor.ultimate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import java.io.DataOutputStream
import kotlin.math.*
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk=[28])
class StoreTest{
 private lateinit var store:Store
 @Before fun setup(){val c=RuntimeEnvironment.getApplication();c.deleteDatabase("ultimate-vibration.db");store=Store(c);store.rawDir.deleteRecursively();store.rawDir.mkdirs()}
 @After fun close(){store.close()}
 private fun record(name:String="Testi")=store.create(name,"Kone A","DE","Radiaalinen","Magneetti","Huomio",Config(),"Testianturi",100.0)
 private fun write(id:String,n:Int=1024){DataOutputStream(store.file(id).outputStream()).use{o->repeat(n){o.writeLong(it*5000000L);o.writeDouble(sin(2*PI*25*it/200));o.writeDouble(0.0);o.writeDouble(9.81)}}}
 @Test fun nameAndSettingsSurviveReopen(){val r=record();store.close();store=Store(RuntimeEnvironment.getApplication());val found=store.list().single();assertEquals(r.name,found.name);assertEquals(r.config,found.config);assertEquals(r.mounting,found.mounting)}
 @Test fun recoverRetainsPartialJournal(){val r=record();write(r.id,83);store.recover();assertEquals("Keskeytynyt",store.list().single().status);assertEquals(83,store.read(r.id).size)}
 @Test fun truncatedLastSampleRetainsCompleteSamples(){val r=record();write(r.id,70);store.file(r.id).appendBytes(byteArrayOf(1,2,3));assertEquals(70,store.read(r.id).size)}
 @Test fun backupRestoresRawAndAnalysisVersions(){val r=record();write(r.id);val a=Dsp.analyze(store.read(r.id),r.config);store.finish(r.id,a,"Valmis");store.finish(r.id,a,"Valmis");store.machine("Kone A","Pumppu");val backup=store.backup();assertEquals(1,store.restore(backup));val copy=store.list().first{it.id!=r.id};assertEquals(store.read(r.id),store.read(copy.id));assertEquals(r.config,copy.config);assertEquals(2,store.versions(copy.id).length());assertEquals(1,store.machines().size)}
 @Test fun deleteOnlySelectedMeasurement(){val a=record("A");val b=record("B");write(a.id);write(b.id);store.delete(a.id);assertEquals(b.id,store.list().single().id);assertTrue(store.file(b.id).exists());assertFalse(store.file(a.id).exists())}
 @Test fun corruptRestoreIsAtomic(){val r=record();write(r.id);val root=org.json.JSONObject(store.backup());root.getJSONArray("measurements").getJSONObject(0).getJSONArray("raw").getJSONArray(0).put(1,"bad");try{store.restore(root.toString());fail("Expected invalid input rejection")}catch(expected:Exception){};assertEquals(1,store.list().size);assertEquals(1,store.rawDir.listFiles()!!.size)}
 @Test fun renameDoesNotAlterSignal(){val r=record();write(r.id);val before=store.file(r.id).readBytes();store.rename(r.id,"Uusi nimi");assertArrayEquals(before,store.file(r.id).readBytes());assertEquals("Uusi nimi",store.list().single().name)}
}
