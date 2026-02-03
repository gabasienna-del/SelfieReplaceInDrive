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

        // Папка, где лежат твои фото (можно менять)
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

        Log.d(TAG, "inDrive запущен — автоматическая подмена селфи активирована")

        // Загружаем подходящее фото один раз
        loadBestFakePhoto()

        if (fakeYPlane == null) {
            Log.e(TAG, "Не удалось найти подходящее фото для подмены")
            return
        }

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

                            // Если размеры не совпадают — пропускаем (или масштабируем в будущем)
                            if (origWidth != fakeWidth || origHeight != fakeHeight) {
                                Log.w(TAG, "Размеры не совпадают: fake $fakeWidth×$fakeHeight vs orig $origWidth×$origHeight")
                                return
                            }

                            val planes = imageProxy.planes
                            planes[0].buffer.rewind().put(fakeYPlane!!)
                            planes[1].buffer.rewind().put(fakeUPlane!!)
                            planes[2].buffer.rewind().put(fakeVPlane!!)

                            Log.d(TAG, "Кадр успешно подменён на фейковое фото $fakeWidth×$fakeHeight")
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка подмены кадра: ${e.message}")
                        }
                    }
                }
            )
            Log.d(TAG, "Хук на ImageAnalysis.analyze установлен")
        } catch (e: Throwable) {
            Log.e(TAG, "Ошибка установки хука: ${e.message}")
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

                // Критерии: 2944×2208 ± 100 px, размер 1700–1730 КБ
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
                Log.e(TAG, "Не найдено подходящее фото в $PHOTO_DIR")
                return
            }

            val bitmap = BitmapFactory.decodeFile(bestFile.absolutePath)!!
            fakeWidth = bitmap.width
            fakeHeight = bitmap.height

            // Конвертируем в YUV_420_888 (NV21)
            val yuvImage = YuvImage(bitmap.toNV21(), ImageFormat.NV21, fakeWidth, fakeHeight, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, fakeWidth, fakeHeight), 100, baos)
            val jpegBytes = baos.toByteArray()
