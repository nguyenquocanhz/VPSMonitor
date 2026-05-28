package com.example.vpsmonitor.ssh

import com.example.vpsmonitor.data.VpsServer
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.concurrent.ConcurrentHashMap

object SshSessionPool {
  private val sessions = ConcurrentHashMap<String, Session>()

  /**
   * Get an existing active SSH session or connect a new one.
   * Reuses the connection if it's alive.
   */
  @Synchronized
  fun getSession(server: VpsServer, timeoutMs: Int = 10000): Session {
    val cached = sessions[server.id]
    if (cached != null && cached.isConnected) {
      try {
        // Send a quick keep-alive packet to check if socket is still active
        cached.sendKeepAliveMsg()
        return cached
      } catch (e: Exception) {
        // Cached connection is dead, clean it up
        try { cached.disconnect() } catch (ex: Exception) {}
        sessions.remove(server.id)
      }
    }

    // Connect a brand new session
    val jsch = JSch()
    val session = connectNewSession(jsch, server, timeoutMs)
    sessions[server.id] = session
    return session
  }

  /**
   * Close a specific server session.
   */
  @Synchronized
  fun closeSession(serverId: String) {
    sessions[serverId]?.let {
      try { it.disconnect() } catch (e: Exception) {}
      sessions.remove(serverId)
    }
  }

  /**
   * Close all active sessions in the pool (e.g. when app stops).
   */
  @Synchronized
  fun closeAll() {
    for (serverId in sessions.keys()) {
      closeSession(serverId)
    }
  }

  private fun connectNewSession(jsch: JSch, server: VpsServer, timeoutMs: Int): Session {
    val session = jsch.getSession(server.username, server.host, server.port)
    
    if (server.authType == "key") {
      val privateKeyBytes = server.privateKey.trim().toByteArray(Charsets.UTF_8)
      val passphraseBytes = if (server.passphrase.isNotEmpty()) {
        server.passphrase.toByteArray(Charsets.UTF_8)
      } else {
        null
      }
      jsch.addIdentity(server.id, privateKeyBytes, null, passphraseBytes)
    } else {
      session.setPassword(server.password)
    }

    session.setConfig("StrictHostKeyChecking", if (server.strictHostKeyChecking) "yes" else "no")
    session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
    
    // Performance optimization: send keep-alive packet every 10 seconds
    session.serverAliveInterval = 10000
    
    session.setTimeout(timeoutMs)
    session.connect(timeoutMs)
    return session
  }
}
