package de.michelinside.glucodatahandler.common.tasks

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.utils.Log
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    companion object {
        private const val LOG_ID = "GDH.Task.MqttWorker"
    }

    override fun doWork(): Result {
        val sharedPref = applicationContext.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
        val enabled = sharedPref.getBoolean(Constants.SHARED_PREF_MQTT_SEND_ENABLED, false)
        if (!enabled) {
            return Result.success()
        }

        val url = sharedPref.getString(Constants.SHARED_PREF_MQTT_SEND_URL, "")?.trim() ?: ""
        val topic = sharedPref.getString(Constants.SHARED_PREF_MQTT_SEND_TOPIC, "")?.trim() ?: ""
        val user = sharedPref.getString(Constants.SHARED_PREF_MQTT_SEND_USER, "") ?: ""
        val password = sharedPref.getString(Constants.SHARED_PREF_MQTT_SEND_PASSWORD, "") ?: ""

        if (url.isEmpty() || topic.isEmpty()) {
            Log.e(LOG_ID, "URL or Topic is empty, cannot publish.")
            return Result.failure()
        }

        if (ReceiveData.time == 0L || ReceiveData.isObsoleteShort()) {
            Log.d(LOG_ID, "No valid data to publish.")
            return Result.success()
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

            Log.d(LOG_ID, "Connecting to MQTT broker: $url")
            client.connect(options)
            Log.d(LOG_ID, "Connected to MQTT broker")

            val jsonObject = JSONObject()
            jsonObject.put("date", ReceiveData.time)
            jsonObject.put("sgv", ReceiveData.getDbValue())
            if(!ReceiveData.rate.isNaN()) {
                jsonObject.put("rate", ReceiveData.rate)
            }

            val message = MqttMessage(jsonObject.toString().toByteArray())
            message.qos = 1

            Log.d(LOG_ID, "Publishing to topic: $topic")
            client.publish(topic, message)
            Log.d(LOG_ID, "Message published")

            return Result.success()
        } catch (e: Exception) {
            Log.e(LOG_ID, "MQTT error: ${e.message}", e)
            return Result.retry()
        } finally {
            try {
                if (client != null && client.isConnected) {
                    client.disconnect()
                }
                client?.close()
            } catch (e: Exception) {
                Log.e(LOG_ID, "Error disconnecting: ${e.message}")
            }
        }
    }
}