package com.alwayside.android

import java.io.DataInputStream
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** Audit actual compiled tutorial classes, including click-handler lambdas, without loading Android views. */
class TutorialIsolationTest {
    @Test fun compiledTutorialCannotReachProvidersConfigurationOrCapture() {
        val resource = requireNotNull(TutorialFlow::class.java.getResource("TutorialFlow.class"))
        val files = if (resource.protocol == "jar") {
            (resource.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile.use { jar ->
                jar.entries().asSequence().filter {
                    it.name.startsWith("com/alwayside/android/Tutorial") && it.name.endsWith(".class")
                }.associate { entry -> entry.name.substringAfterLast('/') to jar.getInputStream(entry).use { it.readBytes() } }
            }
        } else {
            Files.list(Paths.get(resource.toURI()).parent).use { paths ->
                paths.filter { it.fileName.toString().startsWith("Tutorial") && it.toString().endsWith(".class") }
                    .toList().associate { it.fileName.toString() to Files.readAllBytes(it) }
            }
        }
        assertTrue("Must inspect the view code, not just the pure flow", files.containsKey("TutorialScreen.class"))
        for ((file, bytes) in files) {
            DataInputStream(bytes.inputStream()).use { input ->
                assertEquals(0xCAFEBABE.toInt(), input.readInt())
                input.readUnsignedShort(); input.readUnsignedShort()
                val count = input.readUnsignedShort()
                val strings = arrayOfNulls<String>(count)
                val classes = mutableListOf<Int>()
                val methods = mutableListOf<Int>()
                val names = mutableMapOf<Int, Int>()
                var index = 1
                while (index < count) {
                    when (val tag = input.readUnsignedByte()) {
                        1 -> strings[index] = input.readUTF()
                        3, 4 -> input.readInt()
                        5, 6 -> { input.readLong(); index++ }
                        7 -> classes.add(input.readUnsignedShort())
                        8, 16, 19, 20 -> input.readUnsignedShort()
                        9, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                        10, 11 -> { input.readUnsignedShort(); methods.add(input.readUnsignedShort()) }
                        12 -> { names[index] = input.readUnsignedShort(); input.readUnsignedShort() }
                        15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                        else -> error("Unknown class constant $tag")
                    }
                    index++
                }
                for (reference in classes.mapNotNull { strings[it] }) {
                    val type = reference.trimStart('[').removePrefix("L").removeSuffix(";")
                    val allowed = type.length == 1 || type == "com/alwayside/android/R" || allowedPrefixes.any(type::startsWith)
                    assertTrue("$file depends on forbidden capability $type", allowed)
                }
                for (name in methods.mapNotNull { names[it] }.mapNotNull { strings[it] }) {
                    assertFalse("$file calls forbidden side effect $name", name in forbiddenMethods)
                }
            }
        }
    }

    companion object {
        private val allowedPrefixes = listOf(
            "com/alwayside/android/Tutorial", "com/alwayside/android/UiKt", "com/alwayside/android/PrototypeIconsKt",
            "com/alwayside/android/R$", "android/app/Activity", "android/content/Context", "android/content/res/",
            "android/graphics/", "android/view/", "android/widget/", "com/google/android/material/",
            "java/lang/", "java/util/", "kotlin/", "org/jetbrains/annotations/",
        )
        private val forbiddenMethods = setOf(
            "startActivity", "startActivityForResult", "startService", "startForegroundService", "bindService",
            "sendBroadcast", "getSharedPreferences", "getSystemService", "getContentResolver", "requestPermissions",
            "openFileInput", "openFileOutput", "forName", "getDeclaredMethod", "loadClass",
        )
    }
}
