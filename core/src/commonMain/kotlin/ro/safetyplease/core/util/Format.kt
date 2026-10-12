package ro.safetyplease.core.util

/** [value] with exactly [decimals] digits after a point, whatever the phone's language: "43.950500". */
expect fun formatDecimal(value: Double, decimals: Int): String

/** The entries in key order, as a map that keeps it when iterated or serialized. */
fun <K : Comparable<K>, V> Map<K, V>.sortedByKey(): Map<K, V> = entries.sortedBy { it.key }.associate { it.key to it.value }
