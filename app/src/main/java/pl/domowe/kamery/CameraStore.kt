// Saves the camera list on the phone (DataStore, the whole list stored as one encrypted JSON string).
package pl.domowe.kamery

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException

// previewUrl: optional lighter stream for the grid; empty means "use url".
data class Camera(val id: String, val name: String, val url: String, val previewUrl: String = "")

// Stage 2 stored the list as plain text under this key; it is encrypted on first start.
private val PLAIN_KEY = stringPreferencesKey("cameras_json")
private val ENCRYPTED_KEY = stringPreferencesKey("cameras_enc")

private val Context.dataStore by preferencesDataStore(
    name = "cameras",
    produceMigrations = { listOf(EncryptPlainListMigration) },
)

private object EncryptPlainListMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = PLAIN_KEY in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[PLAIN_KEY]?.let { prefs[ENCRYPTED_KEY] = Crypto.encrypt(it) }
        prefs.remove(PLAIN_KEY)
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

class CameraStore(context: Context) {
    private val dataStore = context.applicationContext.dataStore

    // Keystore calls go to a background thread, away from the UI.
    val cameras: Flow<List<Camera>> = dataStore.data
        .map { prefs -> read(prefs) }
        .flowOn(Dispatchers.IO)

    // Adds a new camera or replaces the one with the same id.
    suspend fun save(camera: Camera) {
        dataStore.edit { prefs ->
            val list = read(prefs)
            val updated = if (list.any { it.id == camera.id }) {
                list.map { if (it.id == camera.id) camera else it }
            } else {
                list + camera
            }
            prefs[ENCRYPTED_KEY] = Crypto.encrypt(encode(updated))
        }
    }

    // Appends several new cameras at once (e.g. all chosen channels of a recorder).
    suspend fun addAll(cameras: List<Camera>) {
        dataStore.edit { prefs ->
            prefs[ENCRYPTED_KEY] = Crypto.encrypt(encode(read(prefs) + cameras))
        }
    }

    // Moves the camera one place up (offset -1) or down (+1); the list order is the display order.
    suspend fun move(id: String, offset: Int) {
        dataStore.edit { prefs ->
            val list = read(prefs).toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + offset
            if (from == -1 || to !in list.indices) return@edit
            list.add(to, list.removeAt(from))
            prefs[ENCRYPTED_KEY] = Crypto.encrypt(encode(list))
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            prefs[ENCRYPTED_KEY] = Crypto.encrypt(encode(read(prefs).filterNot { it.id == id }))
        }
    }
}

private fun read(prefs: Preferences): List<Camera> {
    val data = prefs[ENCRYPTED_KEY] ?: return emptyList()
    return try {
        decode(Crypto.decrypt(data))
    } catch (e: GeneralSecurityException) {
        // E.g. data restored on another phone, where the Keystore key does not exist.
        Log.w("CameraStore", "Cannot decrypt camera list, starting empty", e)
        emptyList()
    }
}

private fun encode(cameras: List<Camera>): String {
    val array = JSONArray()
    cameras.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("url", it.url)
                .put("previewUrl", it.previewUrl)
        )
    }
    return array.toString()
}

private fun decode(json: String): List<Camera> {
    val array = JSONArray(json)
    return List(array.length()) { i ->
        val obj = array.getJSONObject(i)
        Camera(
            id = obj.getString("id"),
            name = obj.getString("name"),
            url = obj.getString("url"),
            // Missing in cameras saved before the grid was added.
            previewUrl = obj.optString("previewUrl", ""),
        )
    }
}
