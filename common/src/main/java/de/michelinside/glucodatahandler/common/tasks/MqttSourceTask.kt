package de.michelinside.glucodatahandler.common.tasks

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.notifier.DataSource
import de.michelinside.glucodatahandler.common.utils.GlucoDataUtils
import de.michelinside.glucodatahandler.common.utils.JsonUtils
import de.michelinside.glucodatahandler.common.utils.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MqttSourceTask: DataSourceTask(Constants.SHARED_PREF_MQTT_ENABLED, DataSource.MQTT) {
    companion object {
        private const val LOG_ID = "GDH.Task.Source.MqttSourceTask"
        private var url = ""
        private var user = ""
        private var password = ""
        private var topic = ""
    }

    override fun checkPreferenceChanged(sharedPreferences: SharedPreferences, key: String?, context: Context): Boolean {
        var result = false
        if (key == null) {
            url = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_URL, "")?.trim() ?: ""
            user = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_USER, "") ?: ""
            password = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_PASSWORD, "") ?: ""
            topic = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_TOPIC, "")?.trim() ?: ""
            result = true
        } else {
            when(key) {
                Constants.SHARED_PREF_MQTT_URL -> {
                    url = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_URL, "")?.trim() ?: ""
                    result = true
                }
                Constants.SHARED_PREF_MQTT_USER -> {
                    user = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_USER, "") ?: ""
                    result = true
                }
                Constants.SHARED_PREF_MQTT_PASSWORD -> {
                    password = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_PASSWORD, "") ?: ""
                    result = true
                }
                Constants.SHARED_PREF_MQTT_TOPIC -> {
                    topic = sharedPreferences.getString(Constants.SHARED_PREF_MQTT_TOPIC, "")?.trim() ?: ""
                    result = true
                }
            }
        }
        return super.checkPreferenceChanged(sharedPreferences, key, context) || result
    }

    override fun getValue(): Boolean {
        if (url.isEmpty() || topic.isEmpty()) {
            Log.e(LOG_ID, "URL or Topic is empty")
            return false
        }

        var client: MqttClient? = null
        try {
            val persistence = MemoryPersistence()
            client = MqttClient(url, MqttClient.generateClientId(), persistence)

            val options = MqttConnectOptions()
            options.isCleanSession = true
            options.connectionTimeout = 10

            if (user.isNotEmpty()) {
                options.userName = user
            }
            if (password.isNotEmpty()) {
                options.password = password.toCharArray()
            }

            if (getTrustAllCertificates() && (url.startsWith("ssl://") || url.startsWith("tls://"))) {
                Log.d(LOG_ID, "Using trust all certificates for MQTT")
                try {
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                        object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                                return arrayOf()
                            }
                        }
                    )
                    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                    options.socketFactory = sslContext.socketFactory
                } catch (e: Exception) {
                    Log.e(LOG_ID, "Error setting SSL context: ${e.message}")
                }
            }

            Log.d(LOG_ID, "Connecting to MQTT broker: $url")
            client.connect(options)
            Log.d(LOG_ID, "Connected to MQTT broker")

            val latch = CountDownLatch(1)
            var success = false

            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(LOG_ID, "Connection lost: ${cause?.message}")
                    latch.countDown()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message != null) {
                        try {
                            val payload = String(message.payload)
                            Log.d(LOG_ID, "Message received: $payload")
                            val jsonObject = JSONObject(payload)

                            if (jsonObject.has("date") && jsonObject.has("sgv")) {
                                val valueTime = jsonObject.getLong("date")
                                val firstNeededValue = getFirstNeedGraphValueTime()

                                if (valueTime < firstNeededValue) {
                                    Log.d(LOG_ID, "Value too old")
                                } else {
                                    val glucoExtras = Bundle()
                                    setSgv(glucoExtras, jsonObject)
                                    setRate(glucoExtras, jsonObject)
                                    glucoExtras.putLong(ReceiveData.TIME, valueTime)

                                    if(jsonObject.has("device"))
                                        glucoExtras.putString(ReceiveData.SERIAL, jsonObject.getString("device"))

                                    handleResult(glucoExtras)
                                    success = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(LOG_ID, "Error parsing message: ${e.message}")
                        }
                    }
                    latch.countDown()
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            Log.d(LOG_ID, "Subscribing to topic: $topic")
            client.subscribe(topic)

            // Wait for message (5 seconds max)
            latch.await(5, TimeUnit.SECONDS)

            return success
        } catch (e: Exception) {
            Log.e(LOG_ID, "MQTT error: ${e.message}", e)
            setLastError(e.message ?: "Unknown MQTT error")
            return false
        } finally {
            try {
                if (client != null && client.isConnected) {
                    client.disconnect()
                    Log.d(LOG_ID, "Disconnected from MQTT broker")
                }
                client?.close()
            } catch (e: Exception) {
                Log.e(LOG_ID, "Error disconnecting: ${e.message}")
            }
        }
    }

    private fun setSgv( bundle: Bundle, jsonObject: JSONObject) {
        val glucose = JsonUtils.getFloat("sgv", jsonObject)
        if (glucose.isNaN())
            throw NumberFormatException("Invalid sgv format '" + jsonObject.optString("sgv") + "'")
        if (GlucoDataUtils.isMmolValue(glucose)) {
            bundle.putInt(ReceiveData.MGDL, GlucoDataUtils.mmolToMg(glucose).toInt())
            bundle.putFloat(ReceiveData.GLUCOSECUSTOM, glucose)
        } else {
            bundle.putInt(ReceiveData.MGDL, glucose.toInt())
        }
    }

    private fun setRate( bundle: Bundle, jsonObject: JSONObject) {
        if (jsonObject.has("trend"))
            bundle.putFloat(ReceiveData.RATE, getRateFromTrend(jsonObject.getInt("trend")))
        else if (jsonObject.has("direction"))
            bundle.putFloat(ReceiveData.RATE, GlucoDataUtils.getRateFromLabel(jsonObject.getString("direction")))
    }

    private fun getRateFromTrend(trend: Int): Float {
        return when(trend) {
            1 -> 4F
            2 -> 2F
            3 -> 1F
            4 -> 0F
            5 -> -1F
            6 -> -2F
            7 -> -4F
            else -> Float.NaN
        }
    }
}