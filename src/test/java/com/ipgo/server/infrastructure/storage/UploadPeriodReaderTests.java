package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.exception.UploadException;
import com.ipgo.server.support.UploadTestFiles;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadPeriodReaderTests {
    @Test
    void sharedRichTextDateExcludesPhoneticHints() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"s\"><v>0</v></c>");
        parts.put("xl/sharedStrings.xml", """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si>
                  <r><t>2026-11-</t></r><r><t>20</t></r>
                  <rPh sb="0" eb="10"><t>phonetic hint</t></rPh>
                </si></sst>
                """);
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void inlineDateExcludesPhoneticHints() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"inlineStr\"><is><t>2026-11-20</t>"
                + "<rPh sb=\"0\" eb=\"10\"><t>phonetic hint</t></rPh></is></c>");
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-20T25:00:00", "2026-11-20T23:60:00", "2026-11-20T23:59:60",
            "2026-11-20T12:00:00+25:00", "2026-11-20T12:00:00+09:60"})
    void rejectsInvalidReceiptTime(String timestamp) {
        var parts = sheet("<c r=\"F2\" t=\"d\"><v>" + timestamp + "</v></c>");
        assertThatThrownBy(() -> resolve(parts)).isInstanceOf(UploadException.class);
    }

    @Test
    void acceptsValidEndOfDayAndOffsetFormats() throws Exception {
        for (String timestamp : new String[]{"2026-11-20T23:59:59.999999Z", "2026-11-20 23:59:59+09:30"}) {
            assertThat(resolve(sheet("<c r=\"F2\" t=\"d\"><v>" + timestamp + "</v></c>")).label())
                    .isEqualTo("2026-11");
        }
    }

    @Test
    void ignoresInvalidFutureTimeWhenSelectingLatestValidDate() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"d\"><v>2027-12-31T25:00:00</v></c>"
                + "<c r=\"F3\" t=\"d\"><v>2026-11-20T12:00:00</v></c>");
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void rejectsYearZeroInsteadOfCreatingAnInvalidPeriod() {
        var parts = sheet("<c r=\"F2\" t=\"d\"><v>0000-11-20</v></c>");
        assertThatThrownBy(() -> resolve(parts)).isInstanceOf(UploadException.class);
    }

    @Test
    void ignores1904SerialOverflowAndKeepsTheLatestValidDate() throws Exception {
        var parts = sheet("<c r=\"F2\"><v>2958465</v></c>"
                + "<c r=\"F3\" t=\"d\"><v>2026-11-20</v></c>");
        parts.put("xl/workbook.xml", parts.get("xl/workbook.xml")
                .replace("<sheets>", "<workbookPr date1904=\"1\"/><sheets>"));
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void acceptsTimeOnTheLastSupportedDayInBothDateSystems() throws Exception {
        for (boolean date1904 : new boolean[]{false, true}) {
            LocalDate base = date1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 30);
            long last = ChronoUnit.DAYS.between(base, LocalDate.of(9999, 12, 31));
            var parts = sheet("<c r=\"F2\"><v>" + last + ".5</v></c>");
            if (date1904) parts.put("xl/workbook.xml", parts.get("xl/workbook.xml")
                    .replace("<sheets>", "<workbookPr date1904=\"true\"/><sheets>"));
            assertThat(resolve(parts).label()).isEqualTo("9999-12");
        }
    }

    @Test
    void infersColumnFWhenCellReferencesAreOmitted() throws Exception {
        var parts = sheet("<c/><c/><c/><c/><c/><c t=\"d\"><v>2026-11-20</v></c>"
                + "<c t=\"d\"><v>2099-12-31</v></c>");
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void explicitReferenceSetsPositionAndEachRowStartsAgainAtColumnA() throws Exception {
        var parts = sheet("<c r=\"E2\"/><c t=\"d\"><v>2026-11-20</v></c>");
        parts.put("xl/worksheets/sheet1.xml", parts.get("xl/worksheets/sheet1.xml")
                .replace("</sheetData>", "<row r=\"3\"><c t=\"d\"><v>2099-12-31</v></c></row></sheetData>"));
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void readsNumericExcelDatesAndCachedFormulaButIgnoresOtherColumnsAndInvalidDates() throws Exception {
        long serial = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2026, 11, 30));
        var parts = sheet("<c r=\"F2\"><f>DATE(2026,11,30)</f><v>" + serial + ".75</v></c>"
                + "<c r=\"F3\" t=\"inlineStr\"><is><t>2026.10.1</t></is></c>"
                + "<c r=\"F4\" t=\"d\"><v>2026-02-30</v></c>"
                + "<c r=\"G4\" t=\"d\"><v>2099-12-31</v></c>");
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void readsSharedRichTextAndInlineDates() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"s\"><v>1</v></c>"
                + "<c r=\"F3\" t=\"inlineStr\"><is><t>2026-10-31</t></is></c>");
        parts.put("xl/sharedStrings.xml", """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>입고일자</t></si><si><r><t>2026/11/</t></r><r><t>20</t></r></si>
                </sst>
                """);
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void readsIsoDateCellsAndTimestampText() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"d\"><v>2026-12-01T00:00:00Z</v></c>"
                + "<c r=\"F3\" t=\"inlineStr\"><is><t>2026-11-30 12:00:00</t></is></c>");
        assertThat(resolve(parts).label()).isEqualTo("2026-12");
    }

    @Test
    void supports1904DateSystem() throws Exception {
        long serial = ChronoUnit.DAYS.between(LocalDate.of(1904, 1, 1), LocalDate.of(2026, 11, 30));
        var parts = sheet("<c r=\"F2\"><v>" + serial + "</v></c>");
        parts.put("xl/workbook.xml", parts.get("xl/workbook.xml")
                .replace("<sheets>", "<workbookPr date1904=\"1\"/><sheets>"));
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void followsFirstWorksheetRelationshipRatherThanZipEntryName() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"d\"><v>2026-11-20</v></c>");
        parts.put("xl/worksheets/sheet7.xml", parts.get("xl/worksheets/sheet1.xml"));
        parts.put("xl/_rels/workbook.xml.rels", parts.get("xl/_rels/workbook.xml.rels")
                .replace("worksheets/sheet1.xml", "worksheets/sheet7.xml"));
        parts.put("xl/worksheets/sheet1.xml", "<worksheet><sheetData><row>"
                + "<c r=\"F2\" t=\"d\"><v>2099-12-31</v></c></row></sheetData></worksheet>");
        assertThat(resolve(parts).label()).isEqualTo("2026-11");
    }

    @Test
    void noValidDateGivesActionableInputError() throws Exception {
        var parts = sheet("<c r=\"F2\" t=\"inlineStr\"><is><t>입고일자</t></is></c>"
                + "<c r=\"F3\" t=\"n\"><v>NaN</v></c>"
                + "<c r=\"F4\" t=\"d\"><v>2026-02-30</v></c>");
        assertThatThrownBy(() -> resolve(parts)).isInstanceOf(UploadException.class)
                .hasMessageContaining("입고일자(F열)");
    }

    @Test
    void rejectsWorksheetDtdAndExternalEntities() {
        var parts = sheet("");
        parts.put("xl/worksheets/sheet1.xml", "<!DOCTYPE worksheet [<!ENTITY external SYSTEM "
                + "\"file:///does-not-exist\">]><worksheet><sheetData><row><c r=\"F2\" t=\"inlineStr\">"
                + "<is><t>&external;</t></is></c></row></sheetData></worksheet>");
        assertThatThrownBy(() -> resolve(parts)).isInstanceOf(UploadException.class);
    }

    @Test
    void ambiguousFileNameRemainsRejectedEvenWhenReceiptDatesExist() {
        var parts = sheet("<c r=\"F2\" t=\"d\"><v>2026-11-20</v></c>");
        assertThatThrownBy(() -> UploadPeriodReader.resolve(new MockMultipartFile("file",
                "2026-09_2026-10.xlsx", "application/octet-stream", UploadTestFiles.zip(parts, 0))))
                .isInstanceOf(UploadException.class).hasMessageContaining("여러 개");
    }

    private com.ipgo.server.entity.UploadPeriod resolve(Map<String, String> parts) throws Exception {
        return UploadPeriodReader.resolve(new MockMultipartFile("file", "재고.xlsx",
                "application/octet-stream", UploadTestFiles.zip(parts, 0)));
    }

    private Map<String, String> sheet(String cells) {
        var parts = UploadTestFiles.parts();
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData><row r="2">%s</row></sheetData></worksheet>
                """.formatted(cells));
        return parts;
    }
}
