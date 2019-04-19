package network.xyo.ble.firmware

import kotlinx.coroutines.*
import network.xyo.ble.devices.XY4BluetoothDevice
import network.xyo.ble.devices.XYBluetoothDevice
import network.xyo.ble.gatt.peripheral.XYBluetoothResult
import network.xyo.ble.services.dialog.SpotaService
import network.xyo.core.XYBase

class XYBluetoothDeviceUpdate(private var spotaService: SpotaService, var device: XYBluetoothDevice, private val otaFile: XYOtaFile?): XYBase() {

    private val listeners = HashMap<String, XYOtaUpdate.Listener>()
    private var updateJob: Job? = null
    private var lastBlock = false
    private var lastBlockSent = false
    private var lastBlockReady = false
    private var endSignalSent = false
    private var retryCount = 0
    private var chunkCount = -1
    private var blockCounter = 0

    /**
     * Send REBOOT_SIGNAL after flashing. Default is true.
     */
    var sendRebootOnComplete = true

    /**
     * Image Bank to flash to - default is 0
     */
    var imageBank = 0

    //SPI_DI
    var miso_gpio = 0x05

    //SPI_DO
    var mosi_gpio = 0x06

    //SPI_EN
    var cs_gpio = 0x07

    //DPI_CLK
    var sck_gpio = 0x00

    /**
     * Starts the update
     */
    fun start() {
        startUpdate()
    }

    fun cancel() {
        GlobalScope.launch {
            updateJob?.cancelAndJoin()
            reset()
            listeners.clear()
        }

    }

    fun addListener(key: String, listener: XYOtaUpdate.Listener) {
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

    val handler = CoroutineExceptionHandler { _, exception ->
        println("Caught $exception")
    }
    private fun startUpdate() {
        updateJob = GlobalScope.launch {

            device.connection {

                var hasError = false

                //STEP 1 - memdev
                val memResult = setMemDev().await()
                memResult.error?.let { error ->
                    hasError = true
                    failUpdate(error.message.toString())
                    updateJob?.cancel()
                    log.info("startUpdate - MemDev ERROR: $error")
                }

                //STEP 2 - GpioMap
                val gpioResult = setGpioMap().await()
                gpioResult.error?.let { error ->
                    hasError = true
                    failUpdate(error.message.toString())
                    updateJob?.cancel()
                    log.info("startUpdate - GPIO ERROR: $error")
                }

                //STEP 3 - Set patch length for the first and last block
                val patchResult = setPatchLength().await()
                patchResult.error?.let { error ->
                    hasError = true
                    failUpdate(error.message.toString())
                    updateJob?.cancel()
                    log.info("startUpdate - patch ERROR: $error")
                }

                //STEP 4 - send blocks
                while (!lastBlockSent && !hasError) {
                    progressUpdate()
                    val blockResult = sendBlock().await()
                    blockResult.error?.let { error ->
                        hasError = true
                        failUpdate(error.message.toString())
                        updateJob?.cancel()
                        log.info("startUpdate - sendBlock ERROR: $error")
                    }

                    if (lastBlock) {
                        if (!lastBlockReady && otaFile?.numberOfBytes?.rem(otaFile.fileBlockSize) != 0) {
                            log.info("startUpdate LAST BLOCK - SET PATCH LEN: $lastBlock")
                            val finalPatchResult = setPatchLength().await()

                            finalPatchResult.error?.let { error ->
                                hasError = true
                                failUpdate(error.message.toString())
                                updateJob?.cancel()
                                log.info("startUpdate - finalPatchResult ERROR: $error")
                            }
                        }
                    }

                }

                log.info("startUpdate done sending blocks")

                //SEND END SIGNAL
                val endResult = sendEndSignal().await()
                endResult.error?.let { error ->
                    hasError = true
                    failUpdate(error.message.toString())
                    updateJob?.cancel()
                    log.info("startUpdate - endSignal Result ERROR: $error")
                }

                //REBOOT
                if (sendRebootOnComplete) {
                    val reboot = sendReboot().await()
                    reboot.error?.let { error ->
                        log.info("startUpdate - reboot ERROR: $error")
                    }

                    log.info("startUpdate - sent Reboot")
                }

                passUpdate()

                return@connection XYBluetoothResult(true)
            }
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
        synchronized(listeners) {
            for ((_, listener) in listeners) {
                GlobalScope.launch {
                    listener.updated(device)
                }
            }
        }
    }

    private fun failUpdate(error: String) {
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
        return GlobalScope.async {
            val memType = MEMORY_TYPE_EXTERNAL_SPI shl 24 or imageBank
            log.info("setMemDev: " + String.format("%#010x", memType))
            val result = spotaService.SPOTA_MEM_DEV.set(memType).await()

            return@async XYBluetoothResult(result.value, result.error)
        }
    }

    //STEP 2
    private fun setGpioMap(): Deferred<XYBluetoothResult<Int>> {
        return GlobalScope.async {
            val memInfo = miso_gpio shl 24 or (mosi_gpio shl 16) or (cs_gpio shl 8) or sck_gpio

            val result = spotaService.SPOTA_GPIO_MAP.set(memInfo).await()

            return@async XYBluetoothResult(result.value, result.error)
        }
    }

    //STEP 3 - (and when final block is sent)
    private fun setPatchLength(): Deferred<XYBluetoothResult<XYBluetoothResult<Int>>> {
        return GlobalScope.async {
            var blockSize = otaFile?.fileBlockSize
            if (lastBlock) {
                blockSize = otaFile?.numberOfBytes?.rem(otaFile.fileBlockSize)
                lastBlockReady = true
            }

            log.info("setPatchLength blockSize: $blockSize - ${String.format("%#06x", blockSize)}")

            val result = spotaService.SPOTA_PATCH_LEN.set(blockSize!!).await()

            return@async XYBluetoothResult(result, result.error)
        }
    }

    //STEP 4
    private fun sendBlock(): Deferred<XYBluetoothResult<ByteArray>> {
        return GlobalScope.async {
            val block = otaFile?.getBlock(blockCounter)
            val i = ++chunkCount
            var lastChunk = false
            if (chunkCount == block!!.size - 1) {
                chunkCount = -1
                lastChunk = true
            }

            val chunk = block[i]
            val msg = "Sending block " + (blockCounter + 1) + ", chunk " + (i + 1) + " of " + block.size + ", size " + chunk.size
            log.info(msg)



            if (lastChunk) {
                log.info("sendBlock... lastChunk")
                if (!lastBlock) {
                    blockCounter++
                } else {
                    lastBlockSent = true
                }

                if (blockCounter + 1 == otaFile?.numberOfBlocks) {
                    lastBlock = true
                }
            }
            val result = spotaService.SPOTA_PATCH_DATA.set(chunk).await()

            return@async XYBluetoothResult(result.value, result.error)
        }
    }


    private fun sendEndSignal(): Deferred<XYBluetoothResult<Int>> {
        log.info( "sendEndSignal...")
        return GlobalScope.async {
            val result = spotaService.SPOTA_MEM_DEV.set(END_SIGNAL).await()
            log.info("sendEndSignal result: $result")
            endSignalSent = true
            return@async XYBluetoothResult(result.value, result.error)
        }
    }

    //DONE
    private fun sendReboot(): Deferred<XYBluetoothResult<Int>> {
        log.info( "sendReboot...")
        return GlobalScope.async {
            val result = spotaService.SPOTA_MEM_DEV.set(REBOOT_SIGNAL).await()
            return@async XYBluetoothResult(result.value, result.error)
        }
    }

    companion object {
        private const val TAG = "XYBluetoothDeviceUpdate"

        //const val MAX_RETRY_COUNT = 3
        const val END_SIGNAL = -0x2000000
        const val REBOOT_SIGNAL = -0x3000000
        const val MEMORY_TYPE_EXTERNAL_SPI = 0x13
    }

}