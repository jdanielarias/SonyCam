package com.sonycam.monitor.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sonycam.monitor.usb.SONY_VID
import com.sonycam.monitor.usb.UsbTransport

private const val ACTION_USB_PERMISSION = "com.sonycam.monitor.USB_PERMISSION"

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        viewModel.connect(usbManager, device)
                    } else {
                        // permission denied — nothing to do but wait for retry
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> maybeConnect()
                // Only OUR camera leaving matters. Every USB detach on the
                // phone lands here — a hub, an SSD, a keyboard — and tearing the
                // session down for one of those would look like the camera
                // dropping out.
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    intent.usbDevice()?.let { viewModel.onDeviceDetached(it.deviceName) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Field-monitor mode: draw edge-to-edge and hide the system bars
        // (swipe from an edge shows them transiently).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Draw under the front-camera cutout too. In landscape it sits on a
        // short edge, and without this Android kept a ~170 px black strip down
        // that whole side of the monitor. Overlays inset themselves from it;
        // only the live view goes underneath.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent { MonitorApp(viewModel) { maybeConnect() } }

        maybeConnect()
    }

    /**
     * With `singleTask` the USB_DEVICE_ATTACHED launch reuses this instance and
     * arrives here instead of building a second activity (and a second
     * MainViewModel racing this one for the camera).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbReceiver) }
    }

    /**
     * Find a Sony **camera** and connect, or do nothing.
     *
     * The still-image interface class is required, not merely preferred. Sony's
     * vendor id covers drives, controllers and audio hardware, and this app
     * claims an interface by force and can go as far as re-applying the USB
     * configuration to revive a wedged body — none of which belongs anywhere
     * near a device that is not a camera.
     */
    private fun maybeConnect() {
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == SONY_VID && UsbTransport.pickCameraInterface(it) != null
        } ?: return

        if (usbManager.hasPermission(device)) {
            viewModel.connect(usbManager, device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
