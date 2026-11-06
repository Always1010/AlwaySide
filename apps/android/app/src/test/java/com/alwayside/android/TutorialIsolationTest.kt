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
                    audited(it.name.substringAfterLast('/')) && it.name.endsWith(".class")
                }.associate { entry -> entry.name.substringAfterLast('/') to jar.getInputStream(entry).use { it.readBytes() } }
            }
        } else {
            Files.list(Paths.get(resource.toURI()).parent).use { paths ->
                paths.filter { audited(it.fileName.toString()) && it.toString().endsWith(".class") }
                    .toList().associate { it.fileName.toString() to Files.readAllBytes(it) }
            }
        }
        assertTrue("Must inspect the view code, not just the pure flow", files.containsKey("TutorialScreen.class"))
        assertTrue("Shared window components must also remain isolated",files.containsKey("AssistantWindowViews.class"))
        assertTrue(files.containsKey("AssistantMenuViews.class"))
        assertTrue(files.containsKey("AssistantBubbleView.class"))
        assertTrue(files.containsKey("BubbleTouchController.class"))
        for ((file, bytes) in files) {
            DataInputStream(bytes.inputStream()).use { input ->
                assertEquals(0xCAFEBABE.toInt(), input.readInt())
                input.readUnsignedShort(); input.readUnsignedShort()
                val count = input.readUnsignedShort()
                val strings = arrayOfNulls<String>(count)
                val classes = mutableListOf<Int>()
                val classNames = mutableMapOf<Int, Int>()
                val methods = mutableListOf<Pair<Int, Int>>()
                val names = mutableMapOf<Int, Int>()
                var index = 1
                while (index < count) {
                    when (val tag = input.readUnsignedByte()) {
                        1 -> strings[index] = input.readUTF()
                        3, 4 -> input.readInt()
                        5, 6 -> { input.readLong(); index++ }
                        7 -> input.readUnsignedShort().also { classes.add(it); classNames[index] = it }
                        8, 16, 19, 20 -> input.readUnsignedShort()
                        9, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                        10, 11 -> methods.add(input.readUnsignedShort() to input.readUnsignedShort())
                        12 -> { names[index] = input.readUnsignedShort(); input.readUnsignedShort() }
                        15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                        else -> error("Unknown class constant $tag")
                    }
                    index++
                }
                for (reference in classes.mapNotNull { strings[it] }) {
                    val type = reference.trimStart('[').removePrefix("L").removeSuffix(";")
                    val allowed = type.length == 1 || type in resourceClasses || type == inMemoryAppearance || allowedPrefixes.any(type::startsWith)
                    assertTrue("$file depends on forbidden capability $type", allowed)
                }
                for ((ownerIndex, methodIndex) in methods) {
                    val name = names[methodIndex]?.let { strings[it] } ?: continue
                    assertFalse("$file calls forbidden side effect $name", name in forbiddenMethods)
                    val owner = classNames[ownerIndex]?.let { strings[it] }
                    if (owner == inMemoryAppearance) {
                        // Shared presentation may read the selected theme already in memory,
                        // but cannot call mode(), save() or any preference/context accessor.
                        assertEquals("$file accesses appearance configuration", "isNight", name)
                    }
                }
            }
        }
    }

    companion object {
        private val resourceClasses=setOf("com/alwayside/android/R","androidx/appcompat/R","android/R")
        private const val inMemoryAppearance = "com/alwayside/android/AppAppearance"
        private val sharedClasses=listOf("AssistantWindowViews","AssistantWindowActions","AssistantMenuViews","AssistantMenuAction","AssistantBubbleView","AssistantPresentationKt","TapSequence",
            "BubbleTouchController","BubbleGestureHintsView","BubbleGestureStateMachine")
        private fun audited(name: String)=name.startsWith("Tutorial") || sharedClasses.any { name=="$it.class" || name.startsWith("$it$") }
        private val allowedPrefixes = listOf(
            "com/alwayside/android/Tutorial", "com/alwayside/android/UiKt", "com/alwayside/android/PrototypeIconsKt",
            "com/alwayside/android/InteractionConfig", "com/alwayside/android/GestureBinding", "com/alwayside/android/BubbleGesture",
            *sharedClasses.map { "com/alwayside/android/$it" }.toTypedArray(),
            "com/alwayside/android/R$", "android/app/Activity", "android/content/Context", "android/content/res/",
            "android/graphics/", "android/view/", "android/widget/", "android/text/", "android/animation/", "android/os/SystemClock", "android/os/Handler", "android/os/Looper",
            "androidx/appcompat/R$", "com/google/android/material/", "android/R$",
            "androidx/appcompat/app/AlertDialog", "android/content/DialogInterface",
            "android/util/DisplayMetrics",
            "java/lang/", "java/util/", "kotlin/", "org/jetbrains/annotations/",
        )
        private val forbiddenMethods = setOf(
            "startActivity", "startActivityForResult", "startService", "startForegroundService", "bindService",
            "sendBroadcast", "getSharedPreferences", "getSystemService", "getContentResolver", "requestPermissions",
            "openFileInput", "openFileOutput", "forName", "getDeclaredMethod", "loadClass",
        )
    }
}
