package com.ipgo.server.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class UploadTestFiles {
    private UploadTestFiles() { }

    static Map<String, String> parts() {
        var parts = new LinkedHashMap<String, String>();
        parts.put("[Content_Types].xml", """
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """);
        parts.put("_rels/.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """);
        parts.put("xl/workbook.xml", """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="수불부" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """);
        parts.put("xl/_rels/workbook.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
                """);
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1"><v>10</v></c></row></sheetData>
                </worksheet>
                """);
        return parts;
    }

    // 서버가 내용이 같은 파일을 막으므로, 부를 때마다 셀 값이 다른 파일을 만든다.
    // 같은 파일을 다시 올리는 테스트는 돌려받은 바이트를 그대로 다시 쓴다.
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static byte[] workbook() throws IOException {
        var parts = parts();
        parts.put("xl/worksheets/sheet1.xml", parts.get("xl/worksheets/sheet1.xml")
                .replace("<v>10</v>", "<v>" + SEQUENCE.incrementAndGet() + "</v>"));
        return zip(parts, 0);
    }

    static byte[] zip(Map<String, String> parts, int expandedMegabytes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (expandedMegabytes > 0) {
                zip.putNextEntry(new ZipEntry("padding.bin"));
                byte[] block = new byte[1024 * 1024];
                for (int i = 0; i < expandedMegabytes; i++) zip.write(block);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
