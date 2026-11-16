package com.example.vibrationmonitor.ultimate
import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import kotlin.math.*
object Reports{
 private fun chart(c:Canvas,r:Record,a:Result,w:Int,h:Int){c.drawColor(Color.rgb(12,20,34));val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=Color.WHITE;p.textSize=24f;c.drawText("${r.name.take(55)} • FFT Peak m/s²",30f,40f,p);val maxHz=min(r.config.fmax,a.fs*.4);val indices=a.f.indices.filter{a.f[it]<=maxHz};val top=indices.maxOfOrNull{a.peakSpectrum[it]}?.coerceAtLeast(1e-9)?:1.0;p.color=Color.rgb(85,218,200);p.strokeWidth=2f
 for(i in 1 until indices.size){val k=indices[i];val prev=indices[i-1];c.drawLine((30+a.f[prev]/maxHz*(w-60)).toFloat(),(h-50-a.peakSpectrum[prev]/top*(h-120)).toFloat(),(30+a.f[k]/maxHz*(w-60)).toFloat(),(h-50-a.peakSpectrum[k]/top*(h-120)).toFloat(),p)};p.color=Color.WHITE;p.textSize=18f;c.drawText("0 – %.2f Hz | max %.5f m/s² | Δf %.3f Hz".format(maxHz,top,a.fs/a.n),30f,h-15f,p)}
 fun png(out:OutputStream,r:Record,a:Result){val b=Bitmap.createBitmap(1400,700,Bitmap.Config.ARGB_8888);chart(Canvas(b),r,a,1400,700);b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle()}
 fun pdf(out:OutputStream,r:Record,a:Result){val doc=PdfDocument();var number=1;var page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,number).create());var y=42f;val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=11f;color=Color.rgb(20,35,50)}
 fun line(text:String){for(chunk in text.chunked(88)){if(y>795){doc.finishPage(page);number++;page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,number).create());y=42f};page.canvas.drawText(chunk,32f,y,p);y+=17}}
 line("ULTIMATE VIBRATION ANALYZER • MITTAUSRAPORTTI");line(r.name);line("${r.machine} / ${r.point} • ${java.util.Date(r.date)}");line("${r.direction} • ${r.mounting} • ${r.status}");line(r.notes);line("Fs %.3f Hz | FFT %d | Δf %.3f Hz | %s".format(a.fs,a.n,a.fs/a.n,r.config.window));line("RPM ${r.config.rpm} | akseli ${r.config.axis} | ${r.config.method} | ${a.segments} keskiarvoa");line("RMS %.5f m/s² | Peak %.5f m/s² | P-P %.5f m/s²".format(a.rms,a.peak,a.p2p));line("Nopeus %.4f mm/s RMS (%.1f–%.1f Hz)".format(a.velocity,r.config.highpass,min(r.config.fmax,a.fs*.4)));line("Kurtosis %.3f | skewness %.3f | algoritmi ${Dsp.VERSION}".format(a.kurtosis,a.skewness))
 val b=Bitmap.createBitmap(1060,440,Bitmap.Config.ARGB_8888);chart(Canvas(b),r,a,1060,440);page.canvas.drawBitmap(b,null,RectF(32f,y,562f,y+220),p);b.recycle();y+=240
 line("Aikasignaali (DC poistettu), ensimmäiset ${min(a.signal.size,1000)} näytettä")
 val count=min(a.signal.size,1000);val peak=a.signal.take(count).maxOfOrNull{abs(it)}?.coerceAtLeast(1e-9)?:1.0;p.color=Color.rgb(0,120,120);for(i in 1 until count)page.canvas.drawLine(32f+(i-1)*530f/count,(y+45-a.signal[i-1]/peak*40).toFloat(),32f+i*530f/count,(y+45-a.signal[i]/peak*40).toFloat(),p);y+=105;p.color=Color.DKGRAY
 a.warnings.forEach{line(it)};a.findings.forEach{line(it.title+" • "+it.confidence);line(it.evidence);line(it.recommendation)};line("Anturin kaistaa tai kalibrointia ei ole varmennettu. Ei ISO-kuntoluokitus.");doc.finishPage(page);doc.writeTo(out);doc.close()}
}
