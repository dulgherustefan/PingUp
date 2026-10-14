package ro.safetyplease.core.util

/**
 * A latitude and a longitude on the earth. What comes from the network is checked with it as it is read, and a map
 * checks again before it moves: MapLibre throws for a latitude past 90°.
 */
fun validLatLon(lat: Double, lon: Double): Boolean = lat in -90.0..90.0 && lon in -180.0..180.0
