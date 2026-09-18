package com.darusc.vcamdroid.droidcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.darusc.vcamdroid.util.Logger

class MdnsAdvertiser(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    companion object {
        const val SERVICE_TYPE = "_droidcamobs._tcp."
        const val DEFAULT_PORT = 4747
    }

    fun startAdvertising(port: Int = DEFAULT_PORT, customName: String? = null) {
        if (isRegistered || nsdManager == null) return

        val deviceName = customName ?: "VCamdroid (${Build.MODEL})"

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("name", deviceName)
            }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                isRegistered = true
                Logger.log("MDNS", "Registered mDNS service '${serviceInfo?.serviceName}' on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                isRegistered = false
                Logger.log("MDNS", "Failed to register mDNS service: error code $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                isRegistered = false
                Logger.log("MDNS", "Unregistered mDNS service")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                isRegistered = false
                Logger.log("MDNS", "Failed to unregister mDNS service: error code $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Logger.log("MDNS", "Exception registering mDNS service: ${e.message}")
        }
    }

    fun stopAdvertising() {
        if (!isRegistered || nsdManager == null || registrationListener == null) return

        try {
            nsdManager.unregisterService(registrationListener)
        } catch (e: Exception) {
            Logger.log("MDNS", "Exception unregistering mDNS service: ${e.message}")
        } finally {
            isRegistered = false
            registrationListener = null
        }
    }
}
