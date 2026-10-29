package com.example.autofarmbs

import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

const val REF_W = 1280  // все кадры нормализуются к этой ширине; шаблоны снимать в ней же

enum class Screen(val title: String) {
    MATCH("матч"), RESULT("итог матча"), REWARD("награда"),
    LOBBY("лобби"), POPUP("попап"), UNKNOWN("не распознан")
}
data class Hit(val screen: Screen, val at: Point?)

class Frame(val gray: Mat, val hsv: Mat, val w: Int, val h: Int, val sx: Float, val bmp: Bitmap) {
    fun release() { gray.release(); hsv.release() }
}

/** MediaProjection → Bitmap. */
class ScreenCapture(private val mp: MediaProjection, private val w: Int, private val h: Int, dpi: Int) {
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private var last: Bitmap? = null
    init {
        // Android 14+: callback обязателен до createVirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
    }
    private val vd = mp.createVirtualDisplay(
        "cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)

    fun latest(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return last   // на статичном экране новых кадров нет
        img.use {
            val p = it.planes[0]
            val pad = p.rowStride - p.pixelStride * w
            val raw = Bitmap.createBitmap(w + pad / p.pixelStride, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(p.buffer)
            last = if (pad == 0) raw else Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() }
        }
        return last
    }
    fun close() { vd.release(); reader.close() }
}

class Vision(ctx: Context) {
    private val tpl = HashMap<String, Mat>()
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ROI счётчика кубков в лобби (доли экрана x,y,w,h) — откалибруй под своё устройство
    private val trophyRoi = floatArrayOf(0.10f, 0.02f, 0.20f, 0.10f)

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV не инициализировался" }
        ctx.assets.list("templates")?.filter { it.endsWith(".png") }?.forEach { n ->
            ctx.assets.open("templates/$n").use { s ->
                val m = Mat(); Utils.bitmapToMat(BitmapFactory.decodeStream(s), m)
                Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
                tpl[n.removeSuffix(".png")] = m
            }
        }
        Bus.log("Загружено шаблонов: ${tpl.size} ${tpl.keys.sorted()}")
    }

    fun prepare(bmp: Bitmap): Frame {
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val k = bmp.width / REF_W.toDouble()
        val h = (bmp.height / k).toInt()
        val small = Mat(); Imgproc.resize(rgba, small, Size(REF_W.toDouble(), h.toDouble()))
        val gray = Mat(); Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        val rgb = Mat(); Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgba.release(); small.release(); rgb.release()
        return Frame(gray, hsv, REF_W, h, k.toFloat(), bmp)
    }

    fun find(f: Frame, name: String, thr: Double = 0.80): Point? {
        val t = tpl[name] ?: return null
        if (t.cols() > f.gray.cols() || t.rows() > f.gray.rows()) return null
        val res = Mat()
        Imgproc.matchTemplate(f.gray, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res); res.release()
        return if (mm.maxVal >= thr) Point(mm.maxLoc.x + t.cols() / 2.0, mm.maxLoc.y + t.rows() / 2.0) else null
    }

    fun classify(f: Frame): Hit {
        find(f, "hud_attack")?.let { return Hit(Screen.MATCH, it) }
        find(f, "btn_proceed")?.let { return Hit(Screen.RESULT, it) }
        find(f, "btn_claim")?.let { return Hit(Screen.REWARD, it) }
        find(f, "btn_play")?.let { return Hit(Screen.LOBBY, it) }
        (find(f, "btn_close") ?: find(f, "btn_ok"))?.let { return Hit(Screen.POPUP, it) }
        return Hit(Screen.UNKNOWN, null)
    }

    /** Эвристика: красные полоски здоровья врагов (бар шире, чем выше). */
    fun enemies(f: Frame): List<Point> {
        val m1 = Mat(); val m2 = Mat(); val m = Mat()
        Core.inRange(f.hsv, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0), m1)
        Core.inRange(f.hsv, Scalar(172.0, 150.0, 150.0), Scalar(180.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, m); m1.release(); m2.release()
        // вырезаем HUD: верх и нижние углы с кнопками
        Mat(m, Rect(0, 0, f.w, (f.h * 0.12).toInt())).setTo(Scalar(0.0))
        Mat(m, Rect(0, (f.h * 0.60).toInt(), (f.w * 0.28).toInt(), (f.h * 0.40).toInt())).setTo(Scalar(0.0))
        Mat(m, Rect((f.w * 0.62).toInt(), (f.h * 0.55).toInt(), (f.w * 0.38).toInt(), (f.h * 0.45).toInt())).setTo(Scalar(0.0))
        val cs = ArrayList<MatOfPoint>()
        Imgproc.findContours(m, cs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        m.release()
        return cs.map { Imgproc.boundingRect(it) }
            .filter { it.width >= 28 && it.height >= 4 && it.width > it.height * 2.5 }
            .map { Point(it.x + it.width / 2.0, it.y + it.height / 2.0 + f.h * 0.07) } // тело ниже бара
    }

    suspend fun readTrophies(f: Frame): Int? {
        val b = f.bmp
        val x = (b.width * trophyRoi[0]).toInt().coerceIn(0, b.width - 2)
        val y = (b.height * trophyRoi[1]).toInt().coerceIn(0, b.height - 2)
        val w = (b.width * trophyRoi[2]).toInt().coerceAtMost(b.width - x)
        val h = (b.height * trophyRoi[3]).toInt().coerceAtMost(b.height - y)
        val txt = ocr.process(InputImage.fromBitmap(Bitmap.createBitmap(b, x, y, w, h), 0)).await().text
        return Regex("\\d{1,5}").find(txt.replace(" ", ""))?.value?.toIntOrNull()
    }
}
