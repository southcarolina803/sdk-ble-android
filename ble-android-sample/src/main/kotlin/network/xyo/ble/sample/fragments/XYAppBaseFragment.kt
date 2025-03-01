package network.xyo.ble.sample.fragments


import network.xyo.ble.sample.fragments.core.ProgressListener
import network.xyo.base.XYBase
import network.xyo.ui.XYBaseFragment
import network.xyo.ui.ui

abstract class XYAppBaseFragment : XYBaseFragment() {

    override fun onPause() {
        super.onPause()
        throbber?.hide()
    }

    open fun update() {}


    fun checkConnectionError(hasConnectionError: Boolean) {
        if (hasConnectionError) {
            ui {
                showToast("Connection failed. Try Refresh")
            }

        }
    }

    companion object: XYBase()
}