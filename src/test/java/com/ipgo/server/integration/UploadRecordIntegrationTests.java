package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.support.UploadTestDatabase;
import com.ipgo.server.support.UploadTestFiles;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UploadRecordIntegrationTests extends DatabaseIntegrationTest {
    @TempDir static Path root;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("ipgo.upload.directory", () -> root.toString());
    }

    @AfterEach
    void cleanUp() {
        UploadTestDatabase.clear(jdbc);
    }

    @Test
    void recordsUploadWithPeriodFromFileName() throws Exception {
        long userId = newUser();
        upload(userId, "2026-09.xlsx", false)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("uploadId").isNumber())
                .andExpect(jsonPath("status").value("PROCESSING"))
                .andExpect(jsonPath("originalName").value("2026-09.xlsx"))
                .andExpect(jsonPath("periodStart").value("2026-09-01"))
                .andExpect(jsonPath("periodEnd").value("2026-09-30"))
                .andExpect(jsonPath("sha256").doesNotExist())
                .andExpect(jsonPath("storageKey").doesNotExist());

        var row = jdbc.queryForMap("SELECT u.* FROM upload_files u JOIN warehouses w ON w.id = u.warehouse_id "
                + "WHERE w.user_id = ?", userId);
        assertThat(row.get("ORIGINAL_NAME")).isEqualTo("2026-09.xlsx");
        assertThat(row.get("PERIOD_START")).hasToString("2026-09-01");
        assertThat(row.get("PERIOD_END")).hasToString("2026-09-30");
        assertThat(row.get("STATUS")).isEqualTo("PROCESSING");
        assertThat(row.get("REVISION_NO")).isEqualTo(1);
        assertThat((String) row.get("STORAGE_KEY")).startsWith(userId + "/");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void samePeriodIsRejectedUntilOverwriteIsConfirmed() throws Exception {
        long userId = newUser();
        upload(userId, "2026-09.xlsx", false).andExpect(status().isAccepted());

        upload(userId, "수불부_202609.xlsx", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("UPLOAD_DUPLICATE"))
                .andExpect(jsonPath("details.reason").value("SAME_PERIOD"))
                .andExpect(jsonPath("details.existing.originalName").value("2026-09.xlsx"))
                .andExpect(jsonPath("details.existing.periodStart").value("2026-09-01"))
                .andExpect(jsonPath("details.existing.periodEnd").value("2026-09-30"))
                .andExpect(jsonPath("details.existing.uploadId").isNumber())
                .andExpect(jsonPath("details.existing.uploadedAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
        assertThat(uploadCount(userId)).isEqualTo(1);
        assertThat(root.resolve(Long.toString(userId)).toFile().list()).hasSize(1);

        upload(userId, "수불부_202609.xlsx", true).andExpect(status().isAccepted());
        assertThat(jdbc.queryForList("SELECT u.revision_no FROM upload_files u JOIN warehouses w "
                + "ON w.id = u.warehouse_id WHERE w.user_id = ? ORDER BY u.revision_no", Integer.class, userId))
                .containsExactly(1, 2);
    }

    @Test
    void sameFileUnderAnotherPeriodIsRejectedEvenWithOverwrite() throws Exception {
        long userId = newUser();
        byte[] september = UploadTestFiles.workbook();
        upload(userId, "2026-09.xlsx", september, false).andExpect(status().isAccepted());

        for (boolean overwrite : new boolean[] {false, true}) {
            upload(userId, "2026-10.xlsx", september, overwrite)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("code").value("UPLOAD_DUPLICATE"))
                    .andExpect(jsonPath("details.reason").value("SAME_FILE"))
                    .andExpect(jsonPath("details.existing.originalName").value("2026-09.xlsx"))
                    .andExpect(jsonPath("details.existing.periodStart").value("2026-09-01"));
        }
        assertThat(uploadCount(userId)).isEqualTo(1);
        assertThat(root.resolve(Long.toString(userId)).toFile().list()).hasSize(1);
    }

    @Test
    void sameFileCanBeUploadedAgainForSamePeriodWithOverwrite() throws Exception {
        long userId = newUser();
        byte[] september = UploadTestFiles.workbook();
        upload(userId, "2026-09.xlsx", september, false).andExpect(status().isAccepted());

        upload(userId, "2026-09.xlsx", september, false)
                .andExpect(status().isConflict()).andExpect(jsonPath("details.reason").value("SAME_PERIOD"));
        upload(userId, "2026-09.xlsx", september, true).andExpect(status().isAccepted());
        assertThat(uploadCount(userId)).isEqualTo(2);
    }

    @Test
    void sameFileOfAnotherUserOrFailedUploadIsNotDuplicate() throws Exception {
        long owner = newUser();
        long other = newUser();
        byte[] september = UploadTestFiles.workbook();
        upload(owner, "2026-09.xlsx", september, false).andExpect(status().isAccepted());
        upload(other, "2026-10.xlsx", september, false).andExpect(status().isAccepted());

        jdbc.update("UPDATE upload_files SET status = 'FAILED' WHERE storage_key LIKE ?", owner + "/%");
        upload(owner, "2026-10.xlsx", september, false).andExpect(status().isAccepted());
    }

    @Test
    void rejectsFileNameWithoutPeriodBeforeStoringIt() throws Exception {
        long userId = newUser();
        upload(userId, "stock.xlsx", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("UPLOAD_INVALID_FORMAT"))
                .andExpect(jsonPath("details.fileName").value("stock.xlsx"))
                .andExpect(jsonPath("details.reason").isString());
        assertThat(uploadCount(userId)).isZero();
        assertThat(root.resolve(Long.toString(userId))).doesNotExist();
    }

    @Test
    void listsLatestUploadPerPeriodOnlyForOwner() throws Exception {
        long owner = newUser();
        long other = newUser();
        upload(owner, "2026-10.xlsx", false).andExpect(status().isAccepted());
        upload(owner, "2026-09.xlsx", false).andExpect(status().isAccepted());
        upload(owner, "2026-09 수정.xlsx", true).andExpect(status().isAccepted());
        upload(other, "2026-08.xlsx", false).andExpect(status().isAccepted());

        long latest = jdbc.queryForObject("SELECT MAX(u.id) FROM upload_files u JOIN warehouses w "
                + "ON w.id = u.warehouse_id WHERE w.user_id = ?", Long.class, owner);
        mvc.perform(get("/api/uploads/periods").with(jwt().jwt(claims -> claims.subject(Long.toString(owner)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(2))
                .andExpect(jsonPath("items[0].period").value("2026-09"))
                .andExpect(jsonPath("items[0].fileName").value("2026-09 수정.xlsx"))
                .andExpect(jsonPath("items[0].periodStart").value("2026-09-01"))
                .andExpect(jsonPath("items[0].periodEnd").value("2026-09-30"))
                .andExpect(jsonPath("items[0].status").value("PROCESSING"))
                .andExpect(jsonPath("items[0].uploadedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("items[1].period").value("2026-10"))
                .andExpect(jsonPath("latestUploadId").value(latest));
    }

    @Test
    void periodsAreEmptyBeforeFirstUploadAndRequireLogin() throws Exception {
        long userId = newUser();
        mvc.perform(get("/api/uploads/periods").with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("items.length()").value(0))
                .andExpect(jsonPath("latestUploadId").doesNotExist());
        mvc.perform(get("/api/uploads/periods")).andExpect(status().isUnauthorized());
    }

    @Test
    void arbitraryFileNameUsesLatestReceiptDateFromColumnF() throws Exception {
        long userId = newUser();
        upload(userId, "11월 재고.xlsx", datedWorkbook(), false)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("originalName").value("11월 재고.xlsx"))
                .andExpect(jsonPath("periodStart").value("2026-11-01"))
                .andExpect(jsonPath("periodEnd").value("2026-11-30"));
        assertThat(uploadCount(userId)).isEqualTo(1);
    }

    @Test
    void acceptsWorkbookWhoseCellsUseImplicitColumnPositions() throws Exception {
        var parts = UploadTestFiles.parts();
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="2"><c/><c/><c/><c/><c/><c t="inlineStr"><is><t>2026-11-20</t></is></c>
                    <c t="inlineStr"><is><t>2099-12-31</t></is></c></row>
                </sheetData></worksheet>
                """);
        upload(newUser(), "재고.xlsx", UploadTestFiles.zip(parts, 0), false)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("periodStart").value("2026-11-01"));
    }

    @Test
    void invalidYearDoesNotCreateAnUploadOrStoreAnOriginal() throws Exception {
        long userId = newUser();
        var parts = UploadTestFiles.parts();
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="2"><c r="F2" t="d"><v>0000-11-20</v></c></row>
                </sheetData></worksheet>
                """);
        upload(userId, "재고.xlsx", UploadTestFiles.zip(parts, 0), false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("UPLOAD_INVALID_FORMAT"));
        assertThat(uploadCount(userId)).isZero();
        assertThat(root.resolve(Long.toString(userId))).doesNotExist();
    }

    @Test
    void invalidClockTimeIsRejectedBeforeStoringAnUpload() throws Exception {
        long userId = newUser();
        var parts = UploadTestFiles.parts();
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="2"><c r="F2" t="d"><v>2026-11-20T25:00:00</v></c></row>
                </sheetData></worksheet>
                """);
        upload(userId, "재고.xlsx", UploadTestFiles.zip(parts, 0), false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("UPLOAD_INVALID_FORMAT"));
        assertThat(uploadCount(userId)).isZero();
        assertThat(root.resolve(Long.toString(userId))).doesNotExist();
    }

    @Test
    void phoneticHintIsNotPartOfTheReceiptDateValue() throws Exception {
        var parts = UploadTestFiles.parts();
        parts.put("[Content_Types].xml", parts.get("[Content_Types].xml").replace("</Types>",
                "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/></Types>"));
        parts.put("xl/_rels/workbook.xml.rels", parts.get("xl/_rels/workbook.xml.rels").replace("</Relationships>",
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
        parts.put("xl/sharedStrings.xml", """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si>
                  <t>2026-11-20</t><rPh sb="0" eb="10"><t>phonetic hint</t></rPh>
                </si></sst>
                """);
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="2"><c r="F2" t="s"><v>0</v></c></row>
                </sheetData></worksheet>
                """);
        upload(newUser(), "재고.xlsx", UploadTestFiles.zip(parts, 0), false)
                .andExpect(status().isAccepted()).andExpect(jsonPath("periodStart").value("2026-11-01"));
    }

    @Test
    void explicitFileNamePeriodStillWinsOverReceiptDates() throws Exception {
        upload(newUser(), "2026-09.xlsx", datedWorkbook(), false)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("periodStart").value("2026-09-01"));
    }

    @Test
    void inferredPeriodStillRequiresOverwriteConfirmation() throws Exception {
        long userId = newUser();
        byte[] file = datedWorkbook();
        upload(userId, "재고.xlsx", file, false).andExpect(status().isAccepted());
        upload(userId, "새 이름.xlsx", file, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("details.reason").value("SAME_PERIOD"));
        upload(userId, "새 이름.xlsx", file, true).andExpect(status().isAccepted());
        assertThat(uploadCount(userId)).isEqualTo(2);
    }

    private byte[] datedWorkbook() throws Exception {
        var parts = UploadTestFiles.parts();
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="F1" t="inlineStr"><is><t>입고일자</t></is></c></row>
                  <row r="2"><c r="F2" t="inlineStr"><is><t>2026-09-30</t></is></c>
                    <c r="G2" t="inlineStr"><is><t>2099-12-31</t></is></c></row>
                  <row r="3"><c r="F3" t="inlineStr"><is><t>2026-11-20</t></is></c></row>
                  <row r="4"><c r="F4" t="inlineStr"><is><t>2026-10-31</t></is></c></row>
                </sheetData></worksheet>
                """);
        return UploadTestFiles.zip(parts, 0);
    }

    private ResultActions upload(long userId, String fileName, boolean overwrite) throws Exception {
        return upload(userId, fileName, UploadTestFiles.workbook(), overwrite);
    }

    private ResultActions upload(long userId, String fileName, byte[] content, boolean overwrite) throws Exception {
        var file = new MockMultipartFile("file", fileName, "application/octet-stream", content);
        return mvc.perform(multipart("/api/uploads").file(file).param("overwrite", Boolean.toString(overwrite))
                .with(jwt().jwt(claims -> claims.subject(Long.toString(userId)))));
    }

    private long newUser() {
        return UploadTestDatabase.newUser(jdbc, "record-" + UUID.randomUUID() + "@example.test");
    }

    private int uploadCount(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM upload_files u JOIN warehouses w ON w.id = u.warehouse_id "
                + "WHERE w.user_id = ?", Integer.class, userId);
    }
}
