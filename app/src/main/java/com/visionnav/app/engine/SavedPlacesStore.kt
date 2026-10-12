package com.visionnav.app.engine

import android.content.Context
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Place
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SavedPlace(val id: String, val name: String, val place: Place)

/** User's saved places ("Home", "Work", …). Stored on the device only, as a small JSON file. */
interface SavedPlacesStore {
    val places: StateFlow<List<SavedPlace>>
    suspend fun save(place: Place): SavedPlace
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)

    /** Case-insensitive lookup by name. */
    fun find(name: String): Place? =
        places.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.place
}

@Singleton
class FileSavedPlacesStore internal constructor(private val file: File) : SavedPlacesStore {

    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "saved_places.json"))

    @Serializable
    private data class Entry(val id: String, val name: String, val lat: Double, val lon: Double, val address: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _places = MutableStateFlow(load())
    override val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    override suspend fun save(place: Place): SavedPlace = mutate { list ->
        // Saving under an existing name replaces it ("save this place as home" twice).
        val existing = list.firstOrNull { it.name.equals(place.name, ignoreCase = true) }
        val saved = SavedPlace(existing?.id ?: UUID.randomUUID().toString(), place.name, place)
        (list.filterNot { it.id == saved.id } + saved) to saved
    }

    override suspend fun rename(id: String, name: String) {
        mutate { list -> list.map { if (it.id == id) it.copy(name = name, place = it.place.copy(name = name)) else it } to Unit }
    }

    override suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } to Unit }
    }

    private suspend fun <T> mutate(block: (List<SavedPlace>) -> Pair<List<SavedPlace>, T>): T = mutex.withLock {
        val (next, result) = block(_places.value)
        _places.value = next
        withContext(Dispatchers.IO) { persist(next) }
        result
    }

    private fun load(): List<SavedPlace> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString<List<Entry>>(file.readText()).map {
            SavedPlace(it.id, it.name, Place(it.name, GeoPoint(it.lat, it.lon), it.address, "saved", null))
        }
    }.getOrElse { emptyList() }

    private fun persist(list: List<SavedPlace>) {
        val entries = list.map { Entry(it.id, it.name, it.place.point.lat, it.place.point.lon, it.place.address) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
