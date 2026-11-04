package org.openmk3.core.audio

import org.openmk3.core.AppLog
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads an MPC drum program (.xpm, XML): the first 16 instruments that have a sample on their
 * first layer, in order, become pads 1–16, with the layer's sample (looked for next to the
 * program, then by name), volume, pan and tuning. Everything else in the program is left out.
 */
object XpmImport {
    class Pad(val sample: File, val volume: Float?, val pan: Float?, val tune: Float?)

    private fun Element.child(name: String): Element? {
        val l = getElementsByTagName(name)
        for (i in 0 until l.length) { val n = l.item(i); if (n.parentNode === this) return n as Element }
        return null
    }
    private fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun read(f: File): List<Pad> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isExpandEntityReferences = false }
            .newDocumentBuilder().parse(f)
        val insts = doc.getElementsByTagName("Instrument")
        val out = ArrayList<Pad>()
        for (i in 0 until insts.length) {
            if (out.size >= 16) break
            val inst = insts.item(i) as Element
            val layers = inst.child("Layers") ?: continue
            val layer = layers.getElementsByTagName("Layer").let { if (it.length > 0) it.item(0) as Element else null } ?: continue
            val name = layer.text("SampleFile")?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')
                ?: layer.text("SampleName") ?: continue
            val file = File(f.parentFile, "$name.wav").takeIf { it.isFile }
                ?: f.parentFile?.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension.equals(name, true) && it.extension.equals("wav", true) }
                ?: SampleLocator.find(File(f.parentFile, "$name.wav").path)
            if (file == null) { AppLog.i("XPM: sample not found: $name"); continue }
            fun num(e: Element, n: String) = e.text(n)?.toFloatOrNull()
            val tune = (num(layer, "TuneCoarse") ?: 0f) + (num(layer, "TuneFine") ?: 0f) / 100f + (num(inst, "TuneCoarse") ?: 0f)
            out.add(Pad(file, num(layer, "Volume") ?: num(inst, "Volume"), num(layer, "Pan") ?: num(inst, "Pan"), tune))
        }
        return out
    }
}
