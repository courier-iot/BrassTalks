package com.example.p2pchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class Msg(val id: String, val senderId: String, val senderName: String, val text: String, val ts: Long)
data class RoomInfo(val code: String, val password: String, val name: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Identity.securePrefs(app)
    val myId: String = Identity.deviceId(app)

    val room = MutableStateFlow<RoomInfo?>(null)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val peers = MutableStateFlow(0)
    val joining = MutableStateFlow(false)

    private var transport: Transport? = null
    private val seen = HashSet<String>()

    init {
        // You stay in the room until you tap Leave: restore the saved session on launch.
        val code = prefs.getString("room_code", null)
        val pw = prefs.getString("room_pw", null)
        val name = prefs.getString("name", null)
        if (code != null && pw != null && name != null) join(RoomInfo(code, pw, name))
    }

    fun join(r: RoomInfo) {
        if (joining.value || room.value != null) return
        viewModelScope.launch {
            joining.value = true
            val key = withContext(Dispatchers.Default) { Crypto.deriveKey(r.password, r.code) }
            val t = LanTransport(getApplication())
            t.start(myId, Crypto.roomTag(r.code), key, ::onFrame) { peers.value = it }
            transport = t
            prefs.edit().putString("room_code", r.code).putString("room_pw", r.password)
                .putString("name", r.name).apply()
            room.value = r
            joining.value = false
        }
    }

    fun leave() {
        transport?.stop(); transport = null
        prefs.edit().remove("room_code").remove("room_pw").apply()
        room.value = null; messages.value = emptyList(); peers.value = 0; seen.clear()
    }

    fun send(text: String) {
        val r = room.value ?: return
        val t = text.trim().ifEmpty { return }
        val m = Msg(UUID.randomUUID().toString(), myId, r.name, t, System.currentTimeMillis())
        add(m)
        transport?.send(JSONObject().put("id", m.id).put("sid", m.senderId).put("n", m.senderName)
            .put("t", m.text).put("ts", m.ts).toString().toByteArray())
    }

    private fun onFrame(bytes: ByteArray) {
        try {
            val j = JSONObject(bytes.toString(Charsets.UTF_8))
            add(Msg(j.getString("id"), j.getString("sid"), j.getString("n"), j.getString("t"), j.getLong("ts")))
        } catch (_: Exception) {}
    }

    private fun add(m: Msg) {
        synchronized(seen) { if (!seen.add(m.id)) return }
        messages.update { (it + m).sortedBy { x -> x.ts } }
    }

    override fun onCleared() { transport?.stop() }
}
