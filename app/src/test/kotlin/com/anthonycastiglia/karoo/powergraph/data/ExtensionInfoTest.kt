package com.anthonycastiglia.karoo.powergraph.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Karoo lists a data field in the profile editor only if extension_info.xml declares it, while the
 * extension and settings screen build theirs from [GRAPH_FIELDS]. Android resources can't be
 * generated from Kotlin, so this keeps the two in step: adding a [GraphField] without its XML
 * entry, or the reverse, fails here instead of on the device.
 */
class ExtensionInfoTest {

    @Test
    fun extensionInfoDeclaresExactlyTheGraphFields() {
        assertEquals(
            "typeIds in GRAPH_FIELDS vs. $EXTENSION_INFO_PATH",
            GRAPH_FIELDS.map { it.typeId }.toSortedSet(),
            declaredTypeIds().toSortedSet(),
        )
    }

    private fun declaredTypeIds(): List<String> {
        val dataTypes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(EXTENSION_INFO_PATH))
            .getElementsByTagName("DataType")
        return (0 until dataTypes.length).map { dataTypes.item(it).attributes.getNamedItem("typeId").nodeValue }
    }

    private companion object {
        /** Relative to the app module, the working directory Gradle runs unit tests in. */
        const val EXTENSION_INFO_PATH = "src/main/res/xml/extension_info.xml"
    }
}
