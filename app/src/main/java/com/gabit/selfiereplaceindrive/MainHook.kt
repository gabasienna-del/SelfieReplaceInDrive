package com.gabit.selfiereplaceindrive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "SelfieReplace"
        private const val TARGET_PKG = "sinet.startup.inDriver"

        // Папка с твоими фото (можно менять)
        private const val PHOTO_DIR = "/sdcard/"

        // Кэш подменённого YUV (загружаем один раз)
        private var fakeYPlane: ByteBuffer? = null
        private var fakeUPlane: ByteBuffer? = null
        private var fakeVPlane: ByteBuffer? = null
        private var fakeWidth: Int = 0
        private var fakeHeight: Int = 0
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        Log.d(TAG, "inDrive загружен — автоматическая подмена селфи активирована")

        // Загружаем лучшее фото один раз
        loadBestFakePhoto()

        if (fakeYPlane == null) {
            Log.e(TAG, "Не удалось найти и загрузить подходящее фото")
            return
        }

        Log.d(TAG, "Фейковое фото загружено: \( {fakeWidth}x \){fakeHeight}, готов к подмене")

        // Хук на ImageAnalysis.Analyzer.analyze(ImageProxy)
        try {
            val analyzerClass = XposedHelpers.findClass("androidx.camera.core.ImageAnalysis\$Analyzer", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                analyzerClass,
                "analyze",
                "androidx.camera.core.ImageProxy",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val imageProxy = param.args[0] as Image

                        try {
                            val origWidth = imageProxy.width
                            val origHeight = imageProxy.height

                            if (origWidth != fakeWidth || origHeight != fakeHeight) {
                                Log.w(TAG, "Размеры не совпадают: fake $fakeWidth×$fakeHeight vs orig $origWidth×$origHeight")
                                return
                            }

                            val planes = imageProxy.planes
                            planes[0].buffer.rewind().put(fakeYPlane!!)
                            planes[1].buffer.rewind().put(fakeUPlane!!)
                            planes[2].buffer.rewind().put(fakeVPlane!!)

                            Log.d(TAG, "Кадр подменён на фейковое фото $fakeWidth×$fakeHeight")
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка подмены кадра: ${e.message}")
                        }
                    }
                }
            )
            Log.d(TAG, "Хук на analyze установлен")
        } catch (e: Throwable) {
            Log.e(TAG, "Ошибка установки хука analyze: ${e.message}")
        }
    }

    private fun loadBestFakePhoto() {
        try {
            val dir = File(PHOTO_DIR)
            val candidates = dir.listFiles { _, name ->
                name.endsWith(".jpg") || name.endsWith(".jpeg")
            } ?: return

            var bestFile: File? = null
            var bestScore = -1

            for (file in candidates) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val width = bmp.width
                val height = bmp.height
                val sizeKb = file.length() / 1024

                val score = when {
                    width in 2844..3044 && height in 2108..2308 && sizeKb in 1700..1730 -> 100
                    width in 2800..3100 && height in 2100..2300 && sizeKb in 1600..1800 -> 70
                    else -> 0
                }

                if (score > bestScore) {
                    bestScore = score
                    bestFile = file
                }
            }

            if (bestFile == null) {
                Log.e(TAG, "Не найдено подходящее фото в $PHOTO_DIR (ищем 2944×2208, 1.7–1.73 МБ)")
                return
            }

            val bitmap = BitmapFactory.decodeFile(bestFile.absolutePath)!!
            fakeWidth = bitmap.width
            fakeHeight = bitmap.height

            // Конвертируем в YUV_420_888 (NV21)
            val yuvBytes = bitmap.toNV21()

            val ySize = fakeWidth * fakeHeight
            val uvSize = ySize / 4

            fakeYPlane = ByteBuffer.allocateDirect(ySize).put(yuvBytes, 0, ySize)
            fakeUPlane = ByteBuffer.allocateDirect(uvSize).put(yuvBytes, ySize, uvSize)
            fakeVPlane = ByteBuffer.allocateDirect(uvSize).put(yuvBytes, ySize + uvSize, uvSize)

            Log.d(TAG, "Выбрано и конвертировано фото: ${bestFile.name} $fakeWidth×$fakeHeight, Y: ${ySize} байт")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки фото: ${e.message}")
        }
    }

    // Bitmap → NV21 (YUV_420_888)
    private fun Bitmap.toNV21(): ByteArray {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)

        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                yuv[yIndex++] = ((66 * r + 129 * g + 25 * b + 128) shr 8).toByte() + 16

                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = ((-38 * r - 74 * g + 112 * b + 128) shr 8).toByte() + 128
                    yuv[uvIndex++] = ((112 * r - 94 * g - 18 * b + 128) shr 8).toByte() + 128
                }
            }
        }
        return yuv
    }
}
