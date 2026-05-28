package com.example.vpsmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Data Models
data class VpsServer(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val host: String,
  val port: Int = 22,
  val username: String = "root",
  val password: String = "",
  val authType: String = "password", // "password" or "key"
  val privateKey: String = "",
  val passphrase: String = "",
  val osType: String = "linux",
  val strictHostKeyChecking: Boolean = false // Strict Host Key Verification toggle (OPSec requirement)
)

data class VpsProcess(
  val pid: String,
  val name: String,
  val cpu: Double,
  val mem: Double
)

data class VpsNetworkInterface(
  val name: String,
  val rxPackets: Long,
  val txPackets: Long,
  val rxBytes: Long,
  val txBytes: Long,
  val errors: Long
)

data class VpsMetrics(
  val cpuUsage: Double = 0.0,
  val ramUsed: Double = 0.0,
  val ramTotal: Double = 0.0,
  val diskUsed: Double = 0.0,
  val diskTotal: Double = 0.0,
  val uptime: String = "Unknown",
  val processes: List<VpsProcess> = emptyList(),
  val networkInterfaces: List<VpsNetworkInterface> = emptyList(),
  val cpuTemp: Double = 0.0
)

data class SftpFileItem(
  val name: String,
  val path: String,
  val size: Long,
  val isDir: Boolean,
  val permissions: String,
  val lastModified: Long
)

data class LanDevice(
  val ip: String,
  val hostname: String,
  val isRouter: Boolean,
  val isSelf: Boolean,
  val isSshOpen: Boolean
)

// SharedPreferences Helpers
object PreferenceManager {
  private const val PREFS_PLAIN_NAME = "vps_monitor_prefs"
  private const val PREFS_SECURE_NAME = "vps_monitor_prefs_secure"
  private const val KEY_SERVERS_LIST = "servers_list"
  private const val KEY_ACTIVE_SERVER_ID = "active_server_id"
  private const val KEY_POLICY_ACCEPTED = "policy_accepted"

  /**
   * Returns a secure, encrypted SharedPreferences instance using Android Keystore.
   */
  fun getSecurePrefs(context: Context): SharedPreferences {
    return try {
      val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
      EncryptedSharedPreferences.create(
        PREFS_SECURE_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
      )
    } catch (e: Exception) {
      // Fallback to plain in case Keystore fails, but print stacktrace
      e.printStackTrace()
      context.getSharedPreferences(PREFS_PLAIN_NAME, Context.MODE_PRIVATE)
    }
  }

  /**
   * Migrate old unencrypted configurations to the secure vault.
   */
  fun migrateToSecure(context: Context) {
    val plainPrefs = context.getSharedPreferences(PREFS_PLAIN_NAME, Context.MODE_PRIVATE)
    val hasOldData = plainPrefs.contains(KEY_SERVERS_LIST)
    
    if (hasOldData) {
      val securePrefs = getSecurePrefs(context)
      val serversListStr = plainPrefs.getString(KEY_SERVERS_LIST, null)
      val activeServerId = plainPrefs.getString(KEY_ACTIVE_SERVER_ID, null)
      val policyAccepted = plainPrefs.getBoolean(KEY_POLICY_ACCEPTED, false)
      
      securePrefs.edit().apply {
        if (serversListStr != null) putString(KEY_SERVERS_LIST, serversListStr)
        if (activeServerId != null) putString(KEY_ACTIVE_SERVER_ID, activeServerId)
        putBoolean(KEY_POLICY_ACCEPTED, policyAccepted)
        apply()
      }
      
      // Clear sensitive info from plaintext prefs
      plainPrefs.edit().apply {
        remove(KEY_SERVERS_LIST)
        remove(KEY_ACTIVE_SERVER_ID)
        remove(KEY_POLICY_ACCEPTED)
        apply()
      }
    }
  }

  fun getSavedServers(prefs: SharedPreferences): List<VpsServer> {
    val jsonStr = prefs.getString(KEY_SERVERS_LIST, null) ?: return emptyList()
    return try {
      val array = JSONArray(jsonStr)
      val list = mutableListOf<VpsServer>()
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          VpsServer(
            id = obj.getString("id"),
            name = obj.getString("name"),
            host = obj.getString("host"),
            port = obj.getInt("port"),
            username = obj.getString("username"),
            password = obj.optString("password", ""),
            authType = obj.optString("authType", "password"),
            privateKey = obj.optString("privateKey", ""),
            passphrase = obj.optString("passphrase", ""),
            osType = obj.optString("osType", "linux"),
            strictHostKeyChecking = obj.optBoolean("strictHostKeyChecking", false)
          )
        )
      }
      list
    } catch (e: Exception) {
      emptyList()
    }
  }

  fun saveServers(prefs: SharedPreferences, servers: List<VpsServer>) {
    try {
      val array = JSONArray()
      for (server in servers) {
        val obj = JSONObject().apply {
          put("id", server.id)
          put("name", server.name)
          put("host", server.host)
          put("port", server.port)
          put("username", server.username)
          put("password", server.password)
          put("authType", server.authType)
          put("privateKey", server.privateKey)
          put("passphrase", server.passphrase)
          put("osType", server.osType)
          put("strictHostKeyChecking", server.strictHostKeyChecking)
        }
        array.put(obj)
      }
      prefs.edit().putString(KEY_SERVERS_LIST, array.toString()).apply()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}
