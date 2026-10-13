package ro.safetyplease.ui

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/** A file of the repository by its path from the root; the iPhone simulator reads the Mac's disk too. */
fun repoFile(path: String): Path = REPO_ROOT.toPath() / path

fun Path.readText(): String = FileSystem.SYSTEM.read(this) { readUtf8() }

/** The names in a folder, none for one that isn't there. */
fun Path.names(): List<String> = FileSystem.SYSTEM.listOrNull(this).orEmpty().map { it.name }
