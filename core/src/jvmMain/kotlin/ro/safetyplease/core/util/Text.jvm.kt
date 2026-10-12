package ro.safetyplease.core.util

import java.text.Normalizer

actual fun String.decomposed(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
