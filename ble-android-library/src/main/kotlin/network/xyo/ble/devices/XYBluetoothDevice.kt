package network.xyo.ble.devices

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.xyo.ble.ads.XYBleAd
import network.xyo.ble.gatt.XYBluetoothGattClient
import network.xyo.ble.scanner.XYScanRecord
import network.xyo.ble.scanner.XYScanResult
import network.xyo.core.XYBase
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

open class XYBluetoothDevice(context: Context, device: BluetoothDevice?, private val hash: Int) : XYBluetoothGattClient(context, device, false, null, null, null, null) {

    //hash - the reason for the hash system is that some devices rotate MAC addresses or polymorph in other ways
    //the user generally wants to treat a single physical device as a single logical device so the
    //hash that is passed in to create the class is used to make sure that the reuse of existing instances
    //is done based on device specific logic on "sameness"

    protected val listeners = HashMap<String, Listener>()
    val ads = HashMap<Int, XYBleAd>()

    var detectCount = 0
    var enterCount = 0
    var exitCount = 0
    var averageDetectGap = 0L
    var lastDetectGap = 0L
    var firstDetectTime = 0L
    var lastDetectTime = 0L
    var maxDetectTime = 0L

    //set this to true if the device should report that it is out of
    //range right after disconnect.  Generally used for devices
    //with rotating MAC addresses
    var exitAfterDisconnect = false

    protected var _address: String? = null
    open val address: String
        get() {
            return device?.address ?: _address ?: "00:00:00:00:00:00"
        }

    protected var _name: String = ""
    open val name: String?
        get() {
            return device?.name ?: _name
        }

    open val id: String
        get() {
            return ""
        }

    open var outOfRangeDelay = OUTOFRANGE_DELAY

    var notifyExit: ((device: XYBluetoothDevice) -> (Unit))? = null

    var checkingForExit = false

    override fun hashCode(): Int {
        return hash
    }

    override fun equals(other: Any?): Boolean {
        return super.equals(other)
    }

    //this should only be called from the onEnter function so that
    //there is one onExit for every onEnter
    private fun checkForExit() {
        lastAccessTime = now
        if (checkingForExit) {
            return
        }
        checkingForExit = true
        GlobalScope.launch {
            while (checkingForExit) {
                //log.info("checkForExit: $id : $rssi : $now : $outOfRangeDelay : $lastAdTime : $lastAccessTime")
                delay(outOfRangeDelay)

                //check if something else has already marked it as exited
                //this should only happen if another system (exit on connection drop for example)
                //marks this as out of range
                if ((now - lastAdTime) > outOfRangeDelay && (now - lastAccessTime) > outOfRangeDelay) {
                    if (rssi != null) {
                        rssi = null
                        onExit()

                        //make it thread safe
                        val localNotifyExit = notifyExit
                        if (localNotifyExit != null) {
                            GlobalScope.launch {
                                localNotifyExit(this@XYBluetoothDevice)
                            }
                        }
                        checkingForExit = false
                    }
                }
            }
        }
    }

    internal open fun onEnter() {
        log.info("onEnter: $address")
        enterCount++
        lastAdTime = now
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    listener.entered(this@XYBluetoothDevice)
                }
            }
        }
        checkForExit()
    }

    internal open fun onExit() {
        log.info("onExit: $address")
        exitCount++
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    listener.exited(this@XYBluetoothDevice)
                }
            }
        }
        close()
    }

    override fun onDetect(scanResult: XYScanResult?) {
        detectCount++
        if (lastDetectTime == 0L) {
            lastDetectTime = now
        }
        if (firstDetectTime == 0L) {
            firstDetectTime = now
        }
        lastDetectGap = now - lastDetectTime
        if (lastDetectGap > maxDetectTime) {
            maxDetectTime = lastDetectGap
        }
        averageDetectGap = (lastDetectTime - firstDetectTime) / detectCount
        lastDetectTime = now
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    listener.detected(this@XYBluetoothDevice)
                }
            }
        }
        if (_stayConnected && connectionState == ConnectionState.Disconnected) {
            connect()
        }
    }

    override fun onConnectionStateChange(newState: Int) {
        log.info("onConnectionStateChange: $id : $newState: $listeners.size")
        synchronized(listeners) {
            for ((tag, listener) in listeners) {
                GlobalScope.launch {
                    log.info("connectionStateChanged: $tag : $newState")
                    listener.connectionStateChanged(this@XYBluetoothDevice, newState)
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        lastAccessTime = now
                    }
                }
            }
        }
        //if a connection drop means we should mark it as out of range, then lets do it!
        if (exitAfterDisconnect) {
            GlobalScope.launch {
                rssi = null
                onExit()

                //make it thread safe
                val localNotifyExit = notifyExit
                if (localNotifyExit != null) {
                    GlobalScope.launch {
                        localNotifyExit(this@XYBluetoothDevice)
                    }
                }
            }
        }
    }

    fun addListener(key: String, listener: Listener) {
        log.info("addListener:$key:$listener")
        GlobalScope.launch {
            synchronized(listeners) {
                listeners.put(key, listener)
            }
        }
    }

    fun removeListener(key: String) {
        log.info("removeListener:$key")
        GlobalScope.launch {
            synchronized(listeners) {
                listeners.remove(key)
            }
        }
    }

    open class Listener {
        open fun entered(device: XYBluetoothDevice) {}

        open fun exited(device: XYBluetoothDevice) {}

        open fun detected(device: XYBluetoothDevice) {}

        open fun connectionStateChanged(device: XYBluetoothDevice, newState: Int) {}
    }

    internal fun updateAds(record: XYScanRecord) {
        val buffer = ByteBuffer.wrap(record.bytes)
        while (buffer.hasRemaining()) {
            val ad = XYBleAd(buffer)
            ads[ad.hashCode()] = ad
        }
    }

    companion object : XYBase() {

        //the period of time to wait for marking something as out of range
        //if we have not gotten any ads or been connected to it
        const val OUTOFRANGE_DELAY = 10000L

        internal var canCreate = false
        val manufacturerToCreator = HashMap<Int, XYCreator>()
        val serviceToCreator = HashMap<UUID, XYCreator>()

        private fun getDevicesFromManufacturers(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<Int, XYBluetoothDevice>, newDevices: HashMap<Int, XYBluetoothDevice>) {
            for ((manufacturerId, creator) in manufacturerToCreator) {
                val bytes = scanResult.scanRecord?.getManufacturerSpecificData(manufacturerId)
                if (bytes != null) {
                    creator.getDevicesFromScanResult(context, scanResult, globalDevices, newDevices)
                }
            }
        }

        private fun getDevicesFromServices(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<Int, XYBluetoothDevice>, newDevices: HashMap<Int, XYBluetoothDevice>) {
            for ((uuid, creator) in serviceToCreator) {
                if (scanResult.scanRecord?.serviceUuids != null) {
                    if (scanResult.scanRecord?.serviceUuids?.contains(ParcelUuid(uuid)) == true) {
                        creator.getDevicesFromScanResult(context, scanResult, globalDevices, newDevices)
                    }
                }
            }
        }

        internal val creator = object : XYCreator() {
            override fun getDevicesFromScanResult(context: Context, scanResult: XYScanResult, globalDevices: ConcurrentHashMap<Int, XYBluetoothDevice>, foundDevices: HashMap<Int, XYBluetoothDevice>) {

                getDevicesFromServices(context, scanResult, globalDevices, foundDevices)
                getDevicesFromManufacturers(context, scanResult, globalDevices, foundDevices)

                if (foundDevices.size == 0) {
                    val hash = hashFromScanResult(scanResult)

                    val device = scanResult.device

                    if (canCreate && hash != null && device != null) {
                        val createdDevice = XYBluetoothDevice(context, device, hash)
                        foundDevices[hash] = createdDevice
                        globalDevices[hash] = createdDevice
                    }
                }
            }
        }

        internal fun hashFromScanResult(scanResult: XYScanResult): Int? {
            return scanResult.address.hashCode()
        }

    }
}