package network.xyo.ble.firmware

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import network.xyo.ble.devices.XY4BluetoothDevice
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.gatt.XYBluetoothResult
import network.xyo.ble.gatt.asyncBle
import network.xyo.core.XYBase.Companion.logInfo

@Suppress("unused")
class OtaUpdate(var device: XY4BluetoothDevice, private val otaFile: OtaFile?) {

    private val listeners = HashMap<String, Listener>()
    private var lastBlock = false
    private var lastBlockSent = false
    private var lastBlockReady = false
    private var endSignalSent = false
    private var skipPatch = false
    private var retryCount = 0
    private var chunkCount = -1
    private var blockCounter = 0

    private var _imageBank = 0
    var imageBank: Int
        get() = _imageBank
        set(value) {
            _imageBank = value
        }

    private var _miso_gpio = 0x05   //SPI_DI
    var MISO_GPIO: Int
        get() = _miso_gpio
        set(value) {
            _miso_gpio = value
        }

    private var _mosi_gpio = 0x06   //SPI_DO
    var MOSI_GPIO: Int
        get() = _mosi_gpio
        set(value) {
            _mosi_gpio = value
        }

    private var cs_gpio = 0x07      //SPI_EN
    var CS_GPIO: Int
        get() = cs_gpio
        set(value) {
            cs_gpio = value
        }

    private var _sck_gpio = 0x00    //DPI_CLK
    var SCK_GPIO: Int
        get() = _sck_gpio
        set(value) {
            _sck_gpio = value
        }

    //private var mtu = 23

    //todo - NOT IMPLEMENTED
    private var _allowRetry = true
    var allowRetry: Boolean
        get() = _allowRetry
        set(allow) {
            _allowRetry = allow
        }

    /**
     * Starts the update
     */
    fun start() {
        //reset()
        startUpdate()
    }

    fun addListener(key: String, listener: Listener) {
        GlobalScope.launch {
            synchronized(listeners) {
                listeners.put(key, listener)
            }
        }
    }

    fun removeListener(key: String) {
        GlobalScope.launch {
            synchronized(listeners) {
                listeners.remove(key)
            }
        }
    }

    fun reset() {
        retryCount = 0
        blockCounter = 0
        chunkCount = -1
        lastBlock = false
        lastBlockSent = false
        lastBlockReady = false
        endSignalSent = false
    }

    private fun startUpdate() {
        GlobalScope.launch {
            var hasError = false

            //STEP 1 - memdev
            val memResult = setMemDev().await()
            memResult.error?.let { error ->
                hasError = true
                failUpdate(error.message.toString())
                logInfo(TAG, "startUpdate - MemDev ERROR: $error")
            }

            //STEP 2 - GpioMap
            val gpioResult = setGpioMap().await()
            gpioResult.error?.let { error ->
                hasError = true
                failUpdate(error.message.toString())
                logInfo(TAG, "startUpdate - GPIO ERROR: $error")
            }

            //STEP 3 - (and when final block is sent)
            val patchResult = setPatchLength().await()
            patchResult.error?.let { error ->
                hasError = true
                failUpdate(error.message.toString())
                logInfo(TAG, "startUpdate - patch ERROR: $error")
            }

            //STEP 4 - send blocks
            while (!lastBlockSent && !hasError) {

                val blockResult = sendBlock().await()
                blockResult.error?.let { error ->
                    hasError = true
                    failUpdate(error.message.toString())
                    logInfo(TAG, "startUpdate - sendBlock ERROR: $error")
                }

                if (lastBlock) {

                    if (!lastBlockReady && otaFile?.numberOfBytes?.rem(otaFile.fileBlockSize) != 0) {
                    //if (!skipPatch) {
                        logInfo(TAG, "startUpdate LAST BLOCK - SET PATCH LEN ***************: $lastBlock")
                        val finalPatchResult = setPatchLength().await()
                       // lastBlockSent = true
                        finalPatchResult.error?.let { error ->
                            hasError = true
                            failUpdate(error.message.toString())
                            logInfo(TAG, "startUpdate - finalPatchResult ERROR: $error")
                        }
                    }

                }
                progressUpdate()

            }

            logInfo(TAG, "startUpdate done sending blocks.........")

            //SEND END SIGNAL
            val endResult = sendEndSignal().await()
            endResult.error?.let { error ->
                hasError = true
                failUpdate(error.message.toString())
                logInfo(TAG, "startUpdate - endResult ERROR: $error")
            }

            //REBOOT
            val reboot = sendReboot().await()
            reboot.error?.let { error ->
                hasError = true
                failUpdate(error.message.toString())
                logInfo(TAG, "startUpdate - reboot ERROR: $error")
            }

            passUpdate()
        }
    }

    private fun progressUpdate() {
        val chunkNumber = blockCounter * (otaFile?.chunksPerBlockCount ?: 0) + chunkCount + 1
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    otaFile?.totalChunkCount?.let { listener.progress(chunkNumber, it) }
                }
            }
        }
    }

    private fun passUpdate() {
        logInfo(TAG, "passUpdate -- listener.updated")
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    listener.updated(device)
                }
            }
        }
    }

    private fun failUpdate(error: String) {
        logInfo(TAG, "failUpdate -- listener.failed")
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    listener.failed(device, error)
                }
            }
        }
    }

    //STEP 1
    private fun setMemDev(): Deferred<XYBluetoothResult<Int>> {
        return asyncBle {
            val memType = MEMORY_TYPE_EXTERNAL_SPI shl 24 or _imageBank
            logInfo(TAG, "setMemDev: " + String.format("%#010x", memType))
            val result = device.spotaService.SPOTA_MEM_DEV.set(memType).await()
            logInfo(TAG, "setMemDev result: ${result.value}")

            return@asyncBle XYBluetoothResult(result.value, result.error)
        }
    }

    //STEP 2
    private fun setGpioMap(): Deferred<XYBluetoothResult<Int>> {
        return asyncBle {
            val memInfo = _miso_gpio shl 24 or (_mosi_gpio shl 16) or (cs_gpio shl 8) or _sck_gpio
           // logInfo(TAG, "setGpioMap: " + String.format("%#010x", Integer.valueOf(memInfo)))

            val result = device.spotaService.SPOTA_GPIO_MAP.set(memInfo).await()
            logInfo(TAG, "setGpioMap result: ${result.value}")
            return@asyncBle XYBluetoothResult(result.value, result.error)
        }
    }

    //STEP 3 - (and when final block is sent)
    private fun setPatchLength(): Deferred<XYBluetoothResult<XYBluetoothResult<Int>>> {
        return asyncBle {
            //TODO - is this correct?
            var blockSize = otaFile?.fileBlockSize
            if (lastBlock) {
                blockSize = otaFile?.numberOfBytes?.rem(otaFile.fileBlockSize)
                lastBlockReady = true
            }

            logInfo(TAG, "setPatchLength blockSize: $blockSize - ${String.format("%#06x", blockSize)}")

            val result = device.spotaService.SPOTA_PATCH_LEN.set(blockSize!!).await()
            logInfo(TAG, "setPatchLength result: ${result.value.toString()}")
            return@asyncBle XYBluetoothResult(result, result.error)
        }
    }

    //STEP 4
    private fun sendBlock(): Deferred<XYBluetoothResult<ByteArray>> {
        logInfo(TAG, "sendBlock...")
        return asyncBle {
            val block = otaFile?.getBlock(blockCounter)
            val i = ++chunkCount
            var lastChunk = false
            if (chunkCount == block!!.size - 1) {
                chunkCount = -1
                lastChunk = true
            }

            //String systemLogMessage = "Sending block " + (blockCounter + 1) + ", chunk " + (i + 1) + " of " + block.length + ", size " + chunk.length

            val chunk = block[i]
            var msg = "Sending block " + (blockCounter + 1) + ", chunk " + (i + 1) + " of " + block.size + ", size " + chunk.size
            logInfo(TAG, msg)



            if (lastChunk) {
                logInfo(TAG, "sendBlock... lastChunk")
                if (!lastBlock) {
                    blockCounter++
                } else {
                    lastBlockSent = true
                }
                //otaFile?.totalChunkCount

                if (blockCounter +1 == otaFile?.numberOfBlocks) {
                    skipPatch = true
                 //if (chunkNumber == otaFile.totalChunkCount)   {
                    //Sending block 264, chunk 2 of 3, size 20
                    //Sending block 264, chunk 3 of 3, size 17
                    val chunkNumber = blockCounter * (otaFile.chunksPerBlockCount) + chunkCount + 1 //264
                    logInfo(TAG, "sendBlock... lastBlock: ${blockCounter+1} == ${otaFile.numberOfBlocks}")
                    logInfo(TAG, "sendBlock... lastBlock: ${chunkNumber+1} == ${otaFile.totalChunkCount}") //1842 = 1844

                    lastBlock = true
                }
            }
            val result = device.spotaService.SPOTA_PATCH_DATA.set(chunk).await()

            //logInfo(TAG, "sendBlock result: ${result.value}")

            return@asyncBle XYBluetoothResult(result.value, result.error)
        }
    }


    private fun sendEndSignal(): Deferred<XYBluetoothResult<Int>> {
        logInfo(TAG, "sendEndSignal...")
        return asyncBle {
            val result = device.spotaService.SPOTA_MEM_DEV.set(END_SIGNAL).await()
            endSignalSent = true
            return@asyncBle XYBluetoothResult(result.value, result.error)
        }
    }

    //DONE
    private fun sendReboot(): Deferred<XYBluetoothResult<Int>> {
        logInfo(TAG, "sendReboot...")
        return asyncBle {
            val result = device.spotaService.SPOTA_MEM_DEV.set(REBOOT_SIGNAL).await()
            return@asyncBle XYBluetoothResult(result.value, result.error)
        }
    }


    companion object {
        private const val TAG = "OtaUpdate"

        //TODO - setBlock retry
        const val MAX_RETRY_COUNT = 3
        const val END_SIGNAL = -0x2000000
        const val REBOOT_SIGNAL = -0x3000000
        const val MEMORY_TYPE_EXTERNAL_SPI = 0x13
    }

    open class Listener {
        open fun updated(device: XYBluetoothDevice) {}
        open fun failed(device: XYBluetoothDevice, error: String) {}
        open fun progress(sent: Int, total: Int) {}
    }
}