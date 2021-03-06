package org.coepi.android.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.bluetooth.BluetoothManager
import android.content.Context
import org.coepi.android.cen.CENRepo
import org.coepi.android.system.log.log
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.util.UUID
import kotlin.text.Charsets.UTF_8

class BleServiceManager(bluetoothManager: BluetoothManager, context: Context) : KoinComponent {
    private val gattServer: BluetoothGattServer
    val repo : CENRepo? by inject()

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            log.d("onConnectionStateChange: $status, newState: $newState")
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            log.d("onServiceAdded: status: $status, service: $service")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int,
                                                 offset: Int,
                                                 characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            log.d("onCharacteristicReadRequest")
            replyToCharacteristicReadRequest(requestId, device)
        }
    }

    init {
        gattServer = bluetoothManager.openGattServer(context, serverCallback).also {
            registerService(it)
            log.i("Registered service")
        }
    }

    private fun createService(): BluetoothGattService =
        BluetoothGattService(Uuids.service, SERVICE_TYPE_PRIMARY).apply {
            // THIS IS WHERE AN ANDROID PERIPHERAL SETS ITS CHARACTERISTIC TO THE CEN
            repo?.let {
                it.CEN.value?.let {
                    val c = BluetoothGattCharacteristic(Uuids.characteristic, PROPERTY_READ, PERMISSION_READ)
                    c.setValue(it)
                    addCharacteristic(c)
                }
            }
        }

    private fun registerService(server: BluetoothGattServer) {
        server.addService(createService().also {
            log.i("Registering service: $it")
        })
    }

    private fun replyToCharacteristicReadRequest(requestId: Int, deviceMaybe: BluetoothDevice?) {

        val device = deviceMaybe ?: run {
            log.w("No device. Can't reply to read request. Request id: $requestId")
            return
        }

        log.d("replyToCharacteristicReadRequest")
        gattServer.sendResponse(device, requestId, GATT_SUCCESS, 0,
            UUID.randomUUID().toString().toByteArray(UTF_8))
    }
}
