package dev.savant.parsers.xml

import dev.savant.domain.model.FieldNode
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.asDisplayString
import dev.savant.domain.model.classifyField
import dev.savant.domain.model.displayNameForKey
import dev.savant.domain.parser.ChecksumStrategy
import dev.savant.domain.parser.Confidence
import dev.savant.domain.parser.CorruptSaveException
import dev.savant.domain.parser.NoChecksumStrategy
import dev.savant.domain.parser.SaveParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class XmlSaveParser @Inject constructor() : SaveParser {
    override val id: String = "xml"
    override val displayName: String = "XML"
    override val extensions: Set<String> = setOf("xml", "plist", "sav", "save")
    override val checksumStrategy: ChecksumStrategy = NoChecksumStrategy

    override fun detect(bytes: ByteArray): Confidence {
        if (bytes.isEmpty()) return Confidence.None
        val prefix = decodePrefix(bytes).trimStart().removePrefix("\uFEFF")
        return when {
            prefix.startsWith("<?xml", ignoreCase = true) -> Confidence.of(0.96f)
            XmlStart.containsMatchIn(prefix) -> Confidence.of(0.86f)
            else -> Confidence.None
        }
    }

    override fun parse(bytes: ByteArray): FieldTree {
        val document = try {
            secureFactory().newDocumentBuilder().parse(ByteArrayInputStream(bytes)).apply {
                documentElement.normalize()
            }
        } catch (error: Exception) {
            throw CorruptSaveException(
                "invalid XML: ${error.message.orEmpty().lineSequence().firstOrNull()?.take(180).orEmpty()}",
                error,
            )
        }
        val root = document.documentElement
            ?: throw CorruptSaveException("invalid XML: root element is missing")
        return FieldTree(
            formatId = id,
            roots = listOf(elementNode(root, "/${root.tagName}")),
            metadata = mapOf(
                "xmlVersion" to (document.xmlVersion ?: "1.0"),
                "hadDeclaration" to decodePrefix(bytes).trimStart().startsWith("<?xml").toString(),
            ),
        )
    }

    override fun write(tree: FieldTree): ByteArray {
        require(tree.formatId == id) { "XML parser cannot write '${tree.formatId}'" }
        val root = tree.roots.singleOrNull()
            ?: throw CorruptSaveException("XML tree must contain exactly one root")
        return try {
            val document = secureFactory().newDocumentBuilder().newDocument()
            document.xmlVersion = tree.metadata["xmlVersion"] ?: "1.0"
            document.appendChild(writeElement(document, root))

            val output = ByteArrayOutputStream()
            val transformer = TransformerFactory.newInstance().apply {
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setAttribute(AccessExternalDtd, "") }
                runCatching { setAttribute(AccessExternalStylesheet, "") }
            }.newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.INDENT, "no")
                setOutputProperty(
                    OutputKeys.OMIT_XML_DECLARATION,
                    if (tree.metadata["hadDeclaration"] == "false") "yes" else "no",
                )
            }
            transformer.transform(DOMSource(document), StreamResult(output))
            output.toByteArray()
        } catch (error: Exception) {
            throw CorruptSaveException("could not rebuild XML: ${error.message.orEmpty().take(180)}", error)
        }
    }

    private fun elementNode(element: Element, path: String): FieldNode {
        val attributeNodes = (0 until element.attributes.length).map { index ->
            val attribute = element.attributes.item(index)
            val attributePath = "$path/@${attribute.nodeName}"
            val (type, value) = inferValue(attribute.nodeValue.orEmpty())
            FieldNode(
                path = attributePath,
                displayName = displayNameForKey(attribute.nodeName),
                type = type,
                value = value,
                category = classifyField(attributePath, attribute.nodeName, type),
                editable = !attribute.nodeName.startsWith("xmlns"),
                metadata = buildMap {
                    put("xmlKind", "attribute")
                    put("xmlName", attribute.nodeName)
                    attribute.namespaceURI?.let { put("xmlNamespace", it) }
                },
            )
        }

        val directChildren = (0 until element.childNodes.length).map { element.childNodes.item(it) }
        val childElements = directChildren.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
        val totals = childElements.groupingBy(Element::getTagName).eachCount()
        val seen = mutableMapOf<String, Int>()
        var textIndex = 0
        val contentNodes = buildList {
            directChildren.forEach { child ->
                when (child.nodeType) {
                    Node.ELEMENT_NODE -> {
                        val childElement = child as Element
                        val name = childElement.tagName
                        val index = seen.getOrDefault(name, 0).also { seen[name] = it + 1 }
                        val suffix = if ((totals[name] ?: 0) > 1) "[$index]" else ""
                        add(elementNode(childElement, "$path/$name$suffix"))
                    }
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                        val text = child.nodeValue.orEmpty()
                        if (text.isNotBlank()) {
                            val suffix = if (textIndex == 0) "" else "[$textIndex]"
                            val textPath = "$path/#text$suffix"
                            val (type, value) = inferValue(text)
                            add(
                                FieldNode(
                                    path = textPath,
                                    displayName = displayNameForKey(element.tagName),
                                    type = type,
                                    value = value,
                                    category = classifyField(path, element.tagName, type),
                                    editable = true,
                                    metadata = mapOf(
                                        "xmlKind" to if (child.nodeType == Node.CDATA_SECTION_NODE) "cdata" else "text",
                                    ),
                                ),
                            )
                            textIndex += 1
                        }
                    }
                }
            }
        }

        return FieldNode(
            path = path,
            displayName = displayNameForKey(element.tagName),
            type = FieldType.OBJECT,
            category = classifyField(path, element.tagName, FieldType.OBJECT),
            children = attributeNodes + contentNodes,
            metadata = buildMap {
                put("xmlKind", "element")
                put("xmlName", element.tagName)
                element.namespaceURI?.let { put("xmlNamespace", it) }
            },
        )
    }

    private fun writeElement(document: Document, node: FieldNode): Element {
        val name = node.metadata["xmlName"]
            ?: throw CorruptSaveException("XML element metadata is missing at ${node.path}")
        val namespace = node.metadata["xmlNamespace"]
        val element = if (namespace.isNullOrBlank()) {
            document.createElement(name)
        } else {
            document.createElementNS(namespace, name)
        }

        node.children.filter { it.metadata["xmlKind"] == "attribute" }.forEach { attribute ->
            val attributeName = attribute.metadata["xmlName"].orEmpty()
            val value = attribute.value?.asDisplayString().orEmpty()
            val attributeNamespace = attribute.metadata["xmlNamespace"]
            if (attributeNamespace.isNullOrBlank()) element.setAttribute(attributeName, value)
            else element.setAttributeNS(attributeNamespace, attributeName, value)
        }
        node.children.filterNot { it.metadata["xmlKind"] == "attribute" }.forEach { child ->
            when (child.metadata["xmlKind"]) {
                "element" -> element.appendChild(writeElement(document, child))
                "cdata" -> element.appendChild(
                    document.createCDATASection(child.value?.asDisplayString().orEmpty()),
                )
                "text" -> element.appendChild(
                    document.createTextNode(child.value?.asDisplayString().orEmpty()),
                )
            }
        }
        return element
    }

    private fun inferValue(raw: String): Pair<FieldType, FieldValue> {
        val value = raw.trim()
        if (value == "true" || value == "false") {
            return FieldType.BOOL to FieldValue.BooleanValue(value.toBoolean())
        }
        if (IntegerPattern.matches(value)) {
            value.toLongOrNull()?.let { return FieldType.INT to FieldValue.IntegerValue(it) }
        }
        if (FloatPattern.matches(value)) {
            value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let {
                return FieldType.FLOAT to FieldValue.FloatValue(it)
            }
        }
        return FieldType.STRING to FieldValue.StringValue(raw)
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }

    private fun decodePrefix(bytes: ByteArray): String {
        val length = minOf(bytes.size, 4096)
        return when {
            length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 0, length - (length % 2), Charsets.UTF_16LE)
            length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 0, length - (length % 2), Charsets.UTF_16BE)
            else -> bytes.decodeToString(0, length)
        }
    }

    private companion object {
        const val AccessExternalDtd = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        const val AccessExternalSchema = "http://javax.xml.XMLConstants/property/accessExternalSchema"
        const val AccessExternalStylesheet = "http://javax.xml.XMLConstants/property/accessExternalStylesheet"
        val XmlStart = Regex("^<[A-Za-z_:][^>]*>")
        val IntegerPattern = Regex("-?(0|[1-9][0-9]*)")
        val FloatPattern = Regex("-?(?:0|[1-9][0-9]*)\\.[0-9]+(?:[eE][+-]?[0-9]+)?|-?(?:0|[1-9][0-9]*)[eE][+-]?[0-9]+")
    }
}
