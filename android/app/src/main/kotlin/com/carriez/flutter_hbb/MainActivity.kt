package com.carriez.flutter_hbb

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.RequiresApi
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

const val MEDIA_REQUEST_CODE = 42

class MainActivity : FlutterActivity() {
    companion object {
        lateinit var flutterMethodChannel: MethodChannel
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mediaProjectionResultIntent: Intent? = null
    private var mainService: MainService? = null

    @RequiresApi(Build.VERSION_CODES.M)
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(logTag, "MainActivity configureFlutterEngine,bind to main service")
        Intent(this, MainService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        checkPermissions(this)
        updateMachineInfo()
        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        ).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "init_service" -> {
                        Log.d(logTag, "event from flutter,getPer")
                        if(mainService?.isReady == false){
                            getMediaProjection()
                        }
                        result.success(true)
                    }
                    "start_capture" -> {
                        mainService?.let {
                            result.success(it.startCapture())
                        } ?: let {
                            result.success(false)
                        }
                    }
                    "stop_service" -> {
                        Log.d(logTag,"Stop service")
                        mainService?.let {
                            it.destroy()
                            result.success(true)
                        } ?: let {
                            result.success(false)
                        }
                    }
                    "check_video_permission" -> {
                        mainService?.let {
                            result.success(it.checkMediaPermission())
                        } ?: let {
                            result.success(false)
                        }
                    }
                    "check_service" -> {
                        flutterMethodChannel.invokeMethod(
                            "on_permission_changed",
                            mapOf("name" to "input", "value" to InputService.isOpen.toString())
                        )
                        flutterMethodChannel.invokeMethod(
                            "on_permission_changed",
                            mapOf("name" to "media", "value" to mainService?.isReady.toString())
                        )
                    }
                    "init_input" -> {
                        initInput()
                        result.success(true)
                    }
                    "stop_input" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            InputService.ctx?.disableSelf()
                        }
                        InputService.ctx = null
                        flutterMethodChannel.invokeMethod(
                            "on_permission_changed",
                            mapOf("name" to "input", "value" to InputService.isOpen.toString())
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    private fun getMediaProjection() {
        val mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mIntent = mMediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(mIntent, MEDIA_REQUEST_CODE)
    }

    // 在onActivityResult中成功获取到mediaProjection就开始就调用此函数，开始初始化监听服务
    private fun initService() {
        if (mediaProjectionResultIntent == null) {
            Log.w(logTag, "initService fail,mediaProjectionResultIntent is null")
            return
        }
        Log.d(logTag, "Init service")
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = INIT_SERVICE
        serviceIntent.putExtra(EXTRA_MP_DATA, mediaProjectionResultIntent)

        launchMainService(serviceIntent)
    }

    private fun launchMainService(intent: Intent) {
        // TEST api < O
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun initInput() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        Log.d(logTag, "onResume inputPer:$inputPer")
        activity.runOnUiThread {
            flutterMethodChannel.invokeMethod(
                "on_permission_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Log.d(logTag, "got mediaProjectionResultIntent ok")
            mediaProjectionResultIntent = data
            initService()
        }
    }

    private fun updateMachineInfo() {
        // 屏幕尺寸 控制最长边不超过1400 超过则减半并储存缩放比例 实际发送给手机端的尺寸为缩小后的尺寸
        // input控制时再通过缩放比例恢复原始尺寸进行path入参
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(dm)
        } else {
            windowManager.defaultDisplay.getRealMetrics(dm)
        }
        var w = dm.widthPixels
        var h = dm.heightPixels
        var scale = 1
        if (w != 0 && h != 0) {
            if (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE) {
                scale = 2
                w /= scale
                h /= scale
            }

            INFO.screenWidth = w
            INFO.screenHeight = h
            INFO.scale = scale
            INFO.username = "test"
            INFO.hostname = "hostname"
            // TODO  username hostname
            Log.d(logTag, "INIT INFO:$INFO")

        } else {
            Log.e(logTag, "Got Screen Size Fail!")
        }
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }
}
