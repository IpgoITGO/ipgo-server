package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.exception.UploadException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

final class XlsxValidator {
    private static final long MAX_EXPANDED_BYTES = 100L * 1024 * 1024;
    private static final String WORKBOOK_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";

    private XlsxValidator() { }

    static void validate(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var names = new HashSet<String>();
            var entries = zip.entries();
            long expanded = 0;
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || names.size() > 2000 || name.startsWith("/") || name.contains("..")
                        || name.contains("\\") || name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw invalid();
                }
                CRC32 crc = new CRC32();
                try (var input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        expanded += read;
                        if (expanded > MAX_EXPANDED_BYTES) throw invalid();
                        crc.update(buffer, 0, read);
                    }
                }
                if (entry.getCrc() != crc.getValue()) throw invalid();
            }
            if (!names.contains("_rels/.rels") || !names.contains("xl/_rels/workbook.xml.rels")
                    || names.stream().noneMatch(name -> name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                throw invalid();
            }
            checkXml(zip, "[Content_Types].xml", "Types", "http://schemas.openxmlformats.org/package/2006/content-types");
            checkXml(zip, "xl/workbook.xml", "workbook", "http://schemas.openxmlformats.org/spreadsheetml/2006/main");
            checkXml(zip, "_rels/.rels", "Relationships", "http://schemas.openxmlformats.org/package/2006/relationships");
            checkXml(zip, "xl/_rels/workbook.xml.rels", "Relationships", "http://schemas.openxmlformats.org/package/2006/relationships");
        } catch (ZipException | XMLStreamException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void checkXml(ZipFile zip, String name, String root, String namespace)
            throws IOException, XMLStreamException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > 1024 * 1024) throw invalid();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, ns) -> { throw new XMLStreamException(); });
        try (var input = zip.getInputStream(entry)) {
            var reader = factory.createXMLStreamReader(input);
            boolean foundRoot = false;
            boolean workbookType = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid();
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    if (!foundRoot) {
                        boolean strictWorkbook = "workbook".equals(root)
                                && "http://purl.oclc.org/ooxml/spreadsheetml/main".equals(reader.getNamespaceURI());
                        if (!root.equals(reader.getLocalName())
                                || (!namespace.equals(reader.getNamespaceURI()) && !strictWorkbook)) throw invalid();
                        foundRoot = true;
                    }
                    if ("Types".equals(root) && "Override".equals(reader.getLocalName())) {
                        String contentType = reader.getAttributeValue(null, "ContentType");
                        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("macroenabled")) throw invalid();
                        if ("/xl/workbook.xml".equals(reader.getAttributeValue(null, "PartName"))
                                && WORKBOOK_TYPE.equals(contentType)) workbookType = true;
                    }
                }
                if (!foundRoot || ("Types".equals(root) && !workbookType)) throw invalid();
            } finally {
                reader.close();
            }
        }
    }

    private static UploadException invalid() {
        return LocalUploadStorage.invalid("지원하는 .xlsx 형식이 아니거나 파일이 손상되었습니다.");
    }
}
