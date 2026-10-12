package com.visionnav.mapdata.photon

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Place
import com.visionnav.mapdata.util.MapDataJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal data class PhotonFeatureCollection(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties = PhotonProperties(),
)

@Serializable
internal data class PhotonGeometry(
    val type: String? = null,
    /** GeoJSON order: [lon, lat]. */
    val coordinates: List<Double> = emptyList(),
)

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val locality: String? = null,
    val district: String? = null,
    val city: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postcode: String? = null,
    @SerialName("osm_key") val osmKey: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
    @SerialName("osm_id") val osmId: Long? = null,
    @SerialName("osm_type") val osmType: String? = null,
    val type: String? = null,
)

/**
 * Converts Photon GeoJSON into [Place]s.
 *
 * - `name`: `properties.name`, else "housenumber street", else street, else locality/district/city.
 *   Features with none of these, or without valid coordinates, are dropped (never invented).
 * - `address`: "housenumber street, locality-or-district, city", omitting missing parts and parts
 *   identical to the name; null if nothing remains.
 * - `category`: raw OSM `key=value` (e.g. `railway=station`); null if either is missing.
 * - `distanceM`: great-circle distance from `near`, null without a bias point.
 */
object PhotonParser {

    fun parse(body: String, near: GeoPoint?): Outcome<List<Place>> {
        val fc = try {
            MapDataJson.decodeFromString(PhotonFeatureCollection.serializer(), body)
        } catch (e: SerializationException) {
            return Outcome.Failure(ServiceError.Parse("Photon: ${e.message?.take(200)}"))
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure(ServiceError.Parse("Photon: ${e.message?.take(200)}"))
        }
        return Outcome.Success(fc.features.mapNotNull { toPlace(it, near) })
    }

    internal fun toPlace(f: PhotonFeature, near: GeoPoint?): Place? {
        val c = f.geometry?.coordinates ?: return null
        if (c.size < 2) return null
        val lon = c[0]
        val lat = c[1]
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val point = GeoPoint(lat, lon)
        val p = f.properties
        val streetLine = streetLine(p)
        val name = p.name.clean() ?: streetLine ?: p.locality.clean() ?: p.district.clean() ?: p.city.clean()
            ?: return null
        return Place(
            name = name,
            point = point,
            address = composeAddress(p, name),
            category = category(p),
            distanceM = near?.let { GeoMath.distanceM(it, point) },
        )
    }

    internal fun composeAddress(p: PhotonProperties, name: String): String? {
        val parts = listOfNotNull(streetLine(p), p.locality.clean() ?: p.district.clean(), p.city.clean())
        val seen = HashSet<String>().apply { add(name.lowercase()) }
        val kept = parts.filter { seen.add(it.lowercase()) }
        return kept.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun streetLine(p: PhotonProperties): String? {
        val street = p.street.clean()
        val number = p.housenumber.clean()
        return when {
            street != null && number != null -> "$number $street"
            else -> street
        }
    }

    private fun category(p: PhotonProperties): String? {
        val k = p.osmKey.clean() ?: return null
        val v = p.osmValue.clean() ?: return null
        return "$k=$v"
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
