package com.legendsayantan.adbtools.lib

import android.media.AudioDeviceInfo

/**
 * @author legendsayantan
 */
class AudioOutputMap {

    companion object {
        fun getName(type: Int) : String{
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphones"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "analog"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "digital"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bt call"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bt audio"
                AudioDeviceInfo.TYPE_HDMI,AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi"
                AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_ACCESSORY,AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
                AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "dock"
                AudioDeviceInfo.TYPE_FM, AudioDeviceInfo.TYPE_FM_TUNER -> "fm"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mic"
                AudioDeviceInfo.TYPE_TV_TUNER -> "tv"
                AudioDeviceInfo.TYPE_TELEPHONY -> "tele"
                AudioDeviceInfo.TYPE_AUX_LINE -> "aux"
                AudioDeviceInfo.TYPE_IP -> "ip"
                AudioDeviceInfo.TYPE_BUS -> "bus"
                AudioDeviceInfo.TYPE_HEARING_AID -> "H aid"
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble"
                else -> "type $type"
            }
        }


    }
}