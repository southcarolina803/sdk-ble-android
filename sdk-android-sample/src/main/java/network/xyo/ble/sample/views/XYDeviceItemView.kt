package network.xyo.ble.sample.views

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import network.xyo.core.XYBase
import network.xyo.ble.devices.XYBluetoothDevice

import network.xyo.ble.devices.XYIBeaconBluetoothDevice
import network.xyo.ble.sample.R
import network.xyo.ble.sample.activities.XYOFinderDeviceActivity
import kotlinx.android.synthetic.main.device_item.view.*
import network.xyo.ble.devices.XYFinderBluetoothDevice
import network.xyo.ble.gatt.XYBluetoothGatt

/**
 * Created by arietrouw on 12/27/17.
 */

class XYDeviceItemView(context: Context, attrs: AttributeSet) : RelativeLayout(context, attrs) {

    private var device: XYBluetoothDevice? = null

    init {
        setOnClickListener {
            val device = device
            if (device != null) {
                openDevice(device)
            }
        }
    }

    fun openDevice(device: XYBluetoothDevice) {
        val intent = Intent(context, XYOFinderDeviceActivity::class.java)
        intent.putExtra(XYOFinderDeviceActivity.EXTRA_DEVICEHASH, device.hashCode())
        context.startActivity(intent)
    }

    fun update() {
        post {
            text_family.text = device?.javaClass?.simpleName
            text_name.text = device?.name
            text_connected.text = (device?.connectionState == XYBluetoothGatt.ConnectionState.Connected).toString()
            text_address.text = device?.address
            text_rssi.text = device?.rssi.toString()
            val majorLabelView = findViewById<TextView>(R.id.majorLabel)
            val minorLabelView = findViewById<TextView>(R.id.minorLabel)

            val ibeacon = device as? XYIBeaconBluetoothDevice
            if (ibeacon != null) {
                text_major.text = "0x${ibeacon.major.toInt().toString(16)}"
                text_minor.text = "0x${ibeacon.minor.toInt().toString(16)}"
                text_uuid.text = ibeacon.uuid.toString()
                text_major.visibility = View.VISIBLE
                text_minor.visibility = View.VISIBLE
                majorLabelView.visibility = View.VISIBLE
                minorLabelView.visibility = View.VISIBLE
            } else {
                text_uuid.text = "N/A"
                text_major.visibility = View.GONE
                text_minor.visibility = View.GONE
                majorLabelView.visibility = View.GONE
                minorLabelView.visibility = View.GONE
            }

            text_pulses.text = device!!.detectCount.toString()
        }
    }

    private val deviceListener = object : XYFinderBluetoothDevice.Listener() {
        override fun entered(device: XYBluetoothDevice) {

        }

        override fun exited(device: XYBluetoothDevice) {

        }

        override fun detected(device: XYBluetoothDevice) {
            update()
        }

        override fun connectionStateChanged(device: XYBluetoothDevice, newState: Int) {
            XYBase.logInfo(TAG,"connectionStateChanged")
        }

        override fun buttonSinglePressed(device: XYFinderBluetoothDevice) {
            super.buttonSinglePressed(device)
            openDevice(device)
        }
    }

    fun setDevice(device: XYBluetoothDevice?) {
        if (device != null) {
            device.removeListener(TAG)
        } else {
            XYBase.logError(TAG, "Setting NULL device")
        }

        this.device = device

        device?.addListener(TAG, deviceListener)
        update()
    }

    companion object {
        private val TAG = XYDeviceItemView::class.java.simpleName
    }
}
