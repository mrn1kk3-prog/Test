package com.example.vibrationmonitor.ultimate
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*
import java.util.Random
class DspTest{
 private val cfg=Config(rate=1024,fft=1024,fmax=400.0,highpass=10.0,rpm=3000.0)
 private fun raw(n:Int=4096,fn:(Double)->Double)=List(n){Raw(it*976562L,fn(it/1024.0),0.0,9.81)}
 @Test fun sine50And100(){for(f in listOf(50.0,100.0)){val a=Dsp.analyze(raw{2*sin(2*PI*f*it)},cfg);val k=a.peakSpectrum.indices.maxBy{a.peakSpectrum[it]};assertEquals(f,a.f[k],.01);assertEquals(2.0,a.peakSpectrum[k],.002);assertEquals(sqrt(2.0),a.rms,.001);assertEquals(sqrt(2.0)*1000/(2*PI*f),a.velocity,.1)}}
 @Test fun everyWindowHasCorrectToneGain(){for(w in listOf("Hann","Hamming","Blackman","Flat Top","Rectangular")){val a=Dsp.analyze(raw{sin(2*PI*50*it)},cfg.copy(window=w));assertEquals(w,1.0,a.peakSpectrum[50],.002)}}
 @Test fun twoTonesAndPsd(){val a=Dsp.analyze(raw{sin(2*PI*50*it)+.5*sin(2*PI*100*it)},cfg);assertEquals(sqrt(.625),a.rms,.001);assertEquals(.625,a.psd.sum()*a.fs/a.n,.002);assertEquals(.5,a.peakSpectrum[100],.002)}
 @Test fun vectorDoesNotDoubleFrequency(){val r=raw{sin(2*PI*50*it)};val a=Dsp.analyze(r,cfg.copy(axis=3));assertEquals(1.0,a.peakSpectrum[50],.002);assertTrue(a.peakSpectrum[100]<.001)}
 @Test fun gapsGateDiagnosis(){val r=raw{sin(2*PI*50*it)}.filterIndexed{i,_->i%20!=0};val a=Dsp.analyze(r,cfg);assertTrue(a.gaps>0);assertEquals("Mittaus epäluotettava",a.findings.first().title)}
 @Test fun clippingGatesDiagnosis(){val a=Dsp.analyze(raw{sin(2*PI*50*it)},cfg,9.82);assertTrue(a.clipped);assertEquals("Ei arvioitavissa",a.findings.first().confidence)}
 @Test fun stationaryGravity(){val a=Dsp.analyze(raw{0.0},cfg);assertEquals(0.0,a.rms,0.0);assertFalse(a.findings.any{it.title.contains("epätasapaino")})}
 @Test fun imbalanceAndMisalignmentAreOnlyTentative(){val a=Dsp.analyze(raw{sin(2*PI*50*it)},cfg);assertTrue(a.findings.any{it.title=="Mahdollinen epätasapaino" && it.confidence=="Alustava"});val b=Dsp.analyze(raw{sin(2*PI*50*it)+.8*sin(2*PI*100*it)},cfg);assertTrue(b.findings.any{it.title=="Mahdollinen linjausvirhe"})}
 @Test fun noiseImpulseAndChirpStayFinite(){val random=Random(13);for(r in listOf(raw{random.nextGaussian()},raw{if(it<.001)10.0 else 0.0},raw{sin(2*PI*(20*it+10*it*it))},raw{(1+.4*sin(2*PI*30*it))*sin(2*PI*300*it)})){val a=Dsp.analyze(r,cfg);assertTrue(a.psd.all{it.isFinite() && it>=0});assertTrue(a.velocity.isFinite());assertTrue(a.findings.any{it.title.contains("Laakerivian")})}}
 @Test(expected=IllegalArgumentException::class) fun rejectsNonMonotonic(){Dsp.analyze(raw{it}.reversed(),cfg)}
 @Test fun nyquistIsNotDoubled(){val a=Dsp.analyze(raw{cos(PI*1024*it)},cfg.copy(window="Rectangular"));assertEquals(1.0,a.peakSpectrum.last(),.002)}
 @Test fun jitterIsDetected(){val r=raw{sin(2*PI*50*it)}.mapIndexed{i,v->v.copy(t=v.t+if(i%2==0)200000L else 0L)};assertTrue(Dsp.analyze(r,cfg).jitter>.1)}
}
