package network.xyo.ble.sample.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.fragment_generic_access.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.xyo.ble.devices.XY2BluetoothDevice
import network.xyo.ble.devices.XY3BluetoothDevice
import network.xyo.ble.devices.XY4BluetoothDevice
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.sample.R
import network.xyo.ble.sample.XYDeviceData
import network.xyo.ui.ui

class GenericAccessFragment : XYDeviceFragment() {


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        return inflater.inflate(R.layout.fragment_generic_access, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        button_generic_refresh.setOnClickListener {
            setGenericAccessValues()
        }
    }

    override fun onResume() {
        super.onResume()

        if (deviceData?.deviceName.isNullOrEmpty()) {
            setGenericAccessValues()
        } else {
            updateUI()
        }
    }

    private fun updateUI() {
        ui {
            throbber?.hide()

            text_device_name?.text = deviceData?.deviceName
            text_appearance?.text = deviceData?.appearance
            text_privacy_flag?.text = deviceData?.privacyFlag
            text_reconnection_address?.text = deviceData?.reconnectionAddress
            text_peripheral_params?.text = deviceData?.peripheralPreferredConnectionParameters
        }
    }

    private fun setGenericAccessValues() {
        throbber?.show()

        when (device) {
            is XY4BluetoothDevice -> {
                val x4 = (device as? XY4BluetoothDevice)
                x4?.let { getXY4Values(it) }
            }
            is XY3BluetoothDevice -> {
                val x3 = (device as? XY3BluetoothDevice)
                x3?.let { getXY3Values(it) }
            }
            is XY2BluetoothDevice -> {
                val x2 = (device as? XY2BluetoothDevice)
                x2?.let { getXY2Values(it) }
            }
            else -> {
                text_device_name.text = getString(R.string.unknown_device)
            }
        }

        throbber?.hide()
    }

    private fun getXY4Values(device: XY4BluetoothDevice) {
        GlobalScope.launch {
            var hasConnectionError = true

            val conn = device.connection {
                hasConnectionError = false

                device.genericAccessService.deviceName.get().await().let { it ->
                    deviceData?.deviceName = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.appearance.get().await().let { it ->
                    deviceData?.appearance = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.privacyFlag.get().await().let { it ->
                    deviceData?.privacyFlag = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.reconnectionAddress.get().await().let { it ->
                    deviceData?.reconnectionAddress = "${it.value ?: it.error?.message
                    ?: "Error"}"
                }

                device.genericAccessService.peripheralPreferredConnectionParameters.get().await().let { it ->
                    deviceData?.peripheralPreferredConnectionParameters = "${it.value
                            ?: it.error?.message ?: "Error"}"
                }

                return@connection XYBluetoothResult(true)

            }
            conn.await()

            updateUI()
            checkConnectionError(hasConnectionError)
        }
    }

    private fun getXY3Values(device: XY3BluetoothDevice) {
        GlobalScope.launch {
            var hasConnectionError = true

            val conn = device.connection {
                hasConnectionError = false

                device.genericAccessService.deviceName.get().await().let { it ->
                    deviceData?.deviceName = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.appearance.get().await().let { it ->
                    deviceData?.appearance = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.privacyFlag.get().await().let { it ->
                    deviceData?.privacyFlag = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.reconnectionAddress.get().await().let { it ->
                    deviceData?.reconnectionAddress = "${it.value ?: it.error?.message
                    ?: "Error"}"
                }

                device.genericAccessService.peripheralPreferredConnectionParameters.get().await().let { it ->
                    deviceData?.peripheralPreferredConnectionParameters = "${it.value
                            ?: it.error?.message ?: "Error"}"
                }

                return@connection XYBluetoothResult(true)

            }
            conn.await()

            updateUI()
            checkConnectionError(hasConnectionError)
        }
    }

    private fun getXY2Values(device: XY2BluetoothDevice) {
        GlobalScope.launch {
            var hasConnectionError = true

            val conn = device.connection {
                hasConnectionError = false

                device.genericAccessService.deviceName.get().await().let { it ->
                    deviceData?.deviceName = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.appearance.get().await().let { it ->
                    deviceData?.appearance = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.privacyFlag.get().await().let { it ->
                    deviceData?.privacyFlag = "${it.value ?: it.error?.message ?: "Error"}"
                }

                device.genericAccessService.reconnectionAddress.get().await().let { it ->
                    deviceData?.reconnectionAddress = "${it.value ?: it.error?.message
                    ?: "Error"}"
                }

                device.genericAccessService.peripheralPreferredConnectionParameters.get().await().let { it ->
                    deviceData?.peripheralPreferredConnectionParameters = "${it.value
                            ?: it.error?.message ?: "Error"}"
                }

                return@connection XYBluetoothResult(true)

            }
            conn.await()

            updateUI()
            checkConnectionError(hasConnectionError)
        }
    }

    companion object {

        fun newInstance() =
                GenericAccessFragment()

        fun newInstance (device: XYBluetoothDevice?, deviceData : XYDeviceData?) : GenericAccessFragment {
            val frag = GenericAccessFragment()
            frag.device = device
            frag.deviceData = deviceData
            return frag
        }
    }
}
