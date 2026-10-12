package ro.safetyplease.core.util

/** Canonical decomposition (NFD): "ș" becomes "s" followed by the combining comma. */
expect fun String.decomposed(): String
