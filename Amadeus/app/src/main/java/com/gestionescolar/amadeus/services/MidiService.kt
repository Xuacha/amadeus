package com.gestionescolar.amadeus.services

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log

class MidiService(private val context: Context) {
    private val midiManager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var midiDevice: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null

    interface MidiEventListener {
        fun onNoteOn(note: Int, velocity: Int)
        fun onNoteOff(note: Int)
        fun onDeviceAdded(device: MidiDeviceInfo)
        fun onDeviceRemoved(device: MidiDeviceInfo)
    }

    private var listener: MidiEventListener? = null

    fun setListener(listener: MidiEventListener) {
        this.listener = listener
    }

    fun getDevices(): List<MidiDeviceInfo> {
        return midiManager?.devices?.toList() ?: emptyList()
    }

    fun openDevice(deviceInfo: MidiDeviceInfo) {
        midiManager?.openDevice(deviceInfo, { device ->
            midiDevice = device
            val portInfo = deviceInfo.ports.find { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
            if (portInfo != null) {
                outputPort = device.openOutputPort(portInfo.portNumber)
                outputPort?.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val status = data[offset].toInt() and 0xFF
                        if (status and 0xF0 == 0x90) { // Note On
                            val note = data[offset + 1].toInt() and 0x7F
                            val velocity = data[offset + 2].toInt() and 0x7F
                            if (velocity > 0) {
                                listener?.onNoteOn(note, velocity)
                            } else {
                                listener?.onNoteOff(note)
                            }
                        } else if (status and 0xF0 == 0x80) { // Note Off
                            val note = data[offset + 1].toInt() and 0x7F
                            listener?.onNoteOff(note)
                        }
                    }
                })
                Log.d("MidiService", "Connected to ${deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}")
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun closeDevice() {
        outputPort?.close()
        midiDevice?.close()
        outputPort = null
        midiDevice = null
    }

    init {
        midiManager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                listener?.onDeviceAdded(device)
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                listener?.onDeviceRemoved(device)
            }
        }, Handler(Looper.getMainLooper()))
    }
}
