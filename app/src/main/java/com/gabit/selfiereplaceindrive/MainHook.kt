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
