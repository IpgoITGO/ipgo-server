package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.exception.UploadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.web.multipart.MultipartFile;

/** Reads receipt dates from column F of the first worksheet, without loading the workbook into memory. */
public final class UploadPeriodReader {
    // 실제 수불부는 첫 워크시트 1행 F열 머리글이 입고일자다. 공백 차이만 허용한다(#249).
    static final String RECEIPT_DATE_HEADER = "입고일자";
    // 서비스가 다루는 수불부는 2000년 이후 자료다. 그보다 이른 값은 금액·수량 같은 숫자를 날짜 번호로 잘못 읽은 것이다.
    static final LocalDate EARLIEST_RECEIPT_DATE = LocalDate.of(2000, 1, 1);
    // 입고 예정일이 조금 앞서 적힐 수는 있어도 1년보다 먼 미래는 날짜로 보지 않는다. 예: 2,500,000 → 8744-10-06(#249).
    static final Period LATEST_RECEIPT_DATE_AHEAD = Period.ofYears(1);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    static final String NOT_A_LEDGER = "수불부 형식이 아니에요. 입고일자(F열)가 있는 수불부를 올려 주세요.";
    private static final Pattern BLANK = Pattern.compile("[\\s\\u00A0\\u3000]+");
    private static final Pattern CELL_REFERENCE = Pattern.compile("([A-Z]{1,3})[1-9]\\d*");
    private static final Pattern DATE = Pattern.compile(
            "^(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})(?:[ T](?:[01]\\d|2[0-3]):[0-5]\\d"
                    + "(?::[0-5]\\d(?:\\.\\d+)?)?(?:Z|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)?)?$");

    private UploadPeriodReader() { }

    public static UploadPeriod resolve(MultipartFile file) {
        return resolve(file, LocalDate.now(BUSINESS_ZONE));
    }

    static UploadPeriod resolve(MultipartFile file, LocalDate today) {
        var named = UploadPeriod.findInFileName(file.getOriginalFilename());
        Path pending = null;
        try {
            pending = Files.createTempFile("ipgo-period-", ".xlsx");
            try (var input = file.getInputStream()) {
                Files.copy(input, pending, StandardCopyOption.REPLACE_EXISTING);
            }
            // Apply the same archive size, structure and macro checks as original-file storage.
            XlsxValidator.validate(pending, file.getOriginalFilename());
            try (var zip = new ZipFile(pending.toFile())) {
                var workbook = workbook(zip);
                String sheet = worksheet(zip, workbook.relationship());
                var shared = sharedStrings(zip);
                var range = new DateRange(EARLIEST_RECEIPT_DATE, today.plus(LATEST_RECEIPT_DATE_AHEAD));
                var scan = scan(zip, sheet, shared, workbook.date1904(), range);
                if (!scan.ledger()) throw invalid(file, NOT_A_LEDGER);
                if (named.isPresent()) return named.get();
                LocalDate latest = scan.latest();
                if (latest == null) throw invalid(file, "입고일자(F열)에 유효한 날짜가 없어요. 날짜를 확인해주세요.");
                return new UploadPeriod(YearMonth.from(latest));
            }
        } catch (IOException | XMLStreamException | IllegalArgumentException exception) {
            throw invalid(file, "엑셀에서 입고일자를 읽지 못했어요. 파일 형식과 날짜를 확인해주세요.");
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); }
                catch (IOException exception) { throw new IllegalStateException("Cannot remove upload period temporary file", exception); }
            }
        }
    }

    private record Workbook(String relationship, boolean date1904) { }

    /** Shared string dates by index, and the indexes whose text is the receipt-date header. */
    private record SharedStrings(List<LocalDate> dates, BitSet headers) { }

    private record DateRange(LocalDate earliest, LocalDate latest) {
        LocalDate keep(LocalDate value) {
            return value == null || value.isBefore(earliest) || value.isAfter(latest) ? null : value;
        }
    }

    private record Scan(boolean ledger, LocalDate latest) { }

    private static Workbook workbook(ZipFile zip) throws IOException, XMLStreamException {
        String relationship = null;
        boolean date1904 = false;
        try (var input = zip.getInputStream(zip.getEntry("xl/workbook.xml"))) {
            var reader = reader(input);
            try {
                while (reader.hasNext()) {
                    if (next(reader) != XMLStreamConstants.START_ELEMENT) continue;
                    if ("workbookPr".equals(reader.getLocalName())) {
                        String value = reader.getAttributeValue(null, "date1904");
                        date1904 = "1".equals(value) || "true".equals(value);
                    }
                    if (relationship == null && "sheet".equals(reader.getLocalName())) {
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            if ("id".equals(reader.getAttributeLocalName(i))) relationship = reader.getAttributeValue(i);
                        }
                    }
                }
            } finally { reader.close(); }
        }
        if (relationship == null) throw new XMLStreamException("Missing first worksheet");
        return new Workbook(relationship, date1904);
    }

    private static String worksheet(ZipFile zip, String relationship) throws IOException, XMLStreamException {
        try (var input = zip.getInputStream(zip.getEntry("xl/_rels/workbook.xml.rels"))) {
            var reader = reader(input);
            try {
                while (reader.hasNext()) {
                    if (next(reader) != XMLStreamConstants.START_ELEMENT
                            || !"Relationship".equals(reader.getLocalName())
                            || !relationship.equals(reader.getAttributeValue(null, "Id"))) continue;
                    String target = reader.getAttributeValue(null, "Target");
                    if (target == null || "External".equals(reader.getAttributeValue(null, "TargetMode"))) break;
                    String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    if (path.contains("..") || path.contains("\\") || !path.startsWith("xl/worksheets/")
                            || zip.getEntry(path) == null) break;
                    return path;
                }
            } finally { reader.close(); }
        }
        throw new XMLStreamException("Missing worksheet relationship");
    }

    private static SharedStrings sharedStrings(ZipFile zip) throws IOException, XMLStreamException {
        var dates = new ArrayList<LocalDate>();
        var headers = new BitSet();
        var entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return new SharedStrings(dates, headers);
        try (var input = zip.getInputStream(entry)) {
            var reader = reader(input);
            StringBuilder text = null;
            boolean inText = false;
            int phoneticDepth = 0;
            try {
                while (reader.hasNext()) {
                    int event = next(reader);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if ("rPh".equals(reader.getLocalName())) phoneticDepth++;
                        if ("si".equals(reader.getLocalName())) text = new StringBuilder();
                        inText = "t".equals(reader.getLocalName()) && phoneticDepth == 0;
                    } else if (event == XMLStreamConstants.CHARACTERS && inText && text != null) {
                        append(text, reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("rPh".equals(reader.getLocalName())) phoneticDepth--;
                        if ("t".equals(reader.getLocalName())) inText = false;
                        if ("si".equals(reader.getLocalName())) {
                            if (text != null && isHeader(text.toString())) headers.set(dates.size());
                            dates.add(text == null ? null : date(text.toString()));
                            text = null;
                        }
                    }
                }
            } finally { reader.close(); }
        }
        return new SharedStrings(dates, headers);
    }

    /**
     * Streams the first worksheet. Row 1 must have the receipt-date header in column F; otherwise scanning stops
     * at the end of the first row. Later rows give the latest column F date inside the plausible range.
     */
    private static Scan scan(ZipFile zip, String sheet, SharedStrings shared, boolean date1904, DateRange range)
            throws IOException, XMLStreamException {
        LocalDate latest = null;
        boolean ledger = false;
        try (var input = zip.getInputStream(zip.getEntry(sheet))) {
            var reader = reader(input);
            StringBuilder text = null;
            String type = null;
            boolean value = false;
            int column = 0;
            int row = 0;
            int phoneticDepth = 0;
            try {
                while (reader.hasNext()) {
                    int event = next(reader);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if ("rPh".equals(reader.getLocalName())) phoneticDepth++;
                        if ("row".equals(reader.getLocalName())) {
                            column = 0;
                            row = rowIndex(reader.getAttributeValue(null, "r"), row);
                            // Rows are stored in order, so a sheet whose first row is not row 1 has no header row.
                            if (row != 1 && !ledger) return new Scan(false, null);
                        }
                        if ("c".equals(reader.getLocalName())) {
                            String ref = reader.getAttributeValue(null, "r");
                            column = ref == null ? column + 1 : columnIndex(ref);
                            text = column == 6 ? new StringBuilder() : null;
                            type = reader.getAttributeValue(null, "t");
                        }
                        value = ("v".equals(reader.getLocalName()) || "t".equals(reader.getLocalName()))
                                && phoneticDepth == 0;
                    } else if (event == XMLStreamConstants.CHARACTERS && value && text != null) {
                        append(text, reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("rPh".equals(reader.getLocalName())) phoneticDepth--;
                        if ("v".equals(reader.getLocalName()) || "t".equals(reader.getLocalName())) value = false;
                        if ("c".equals(reader.getLocalName()) && text != null) {
                            if (row == 1) ledger = headerCell(text.toString(), type, shared);
                            else {
                                LocalDate candidate = range.keep(cellDate(text.toString(), type, shared, date1904));
                                if (candidate != null && (latest == null || candidate.isAfter(latest))) latest = candidate;
                            }
                            text = null;
                        }
                        if ("row".equals(reader.getLocalName()) && row == 1 && !ledger) return new Scan(false, null);
                    }
                }
            } finally { reader.close(); }
        }
        return new Scan(ledger, latest);
    }

    private static int rowIndex(String reference, int previous) throws XMLStreamException {
        if (reference == null) return previous + 1;
        try {
            int row = Integer.parseInt(reference);
            if (row < 1) throw new XMLStreamException("Invalid worksheet row reference");
            return row;
        } catch (NumberFormatException exception) {
            throw new XMLStreamException("Invalid worksheet row reference");
        }
    }

    private static boolean headerCell(String text, String type, SharedStrings shared) {
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(text.trim());
                return index >= 0 && shared.headers().get(index);
            } catch (NumberFormatException exception) { return false; }
        }
        return ("inlineStr".equals(type) || "str".equals(type)) && isHeader(text);
    }

    private static boolean isHeader(String text) {
        return RECEIPT_DATE_HEADER.equals(BLANK.matcher(text).replaceAll(""));
    }

    private static int columnIndex(String reference) throws XMLStreamException {
        var match = CELL_REFERENCE.matcher(reference);
        if (!match.matches()) throw new XMLStreamException("Invalid worksheet cell reference");
        int column = 0;
        for (char letter : match.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return column;
    }

    private static LocalDate cellDate(String text, String type, SharedStrings shared, boolean date1904) {
        try {
            if ("s".equals(type)) {
                int index = Integer.parseInt(text.trim());
                return index >= 0 && index < shared.dates().size() ? shared.dates().get(index) : null;
            }
            if ("inlineStr".equals(type) || "str".equals(type) || "d".equals(type)) return date(text);
            if (type != null && !"n".equals(type)) return null;
            double serial = Double.parseDouble(text.trim());
            // The last Excel date may include a fractional time of day.
            if (!Double.isFinite(serial) || serial < 0 || serial >= 2958466) return null;
            long days = (long) serial;
            if (!date1904 && days == 60) return null; // Excel's fictitious 1900-02-29.
            LocalDate base = date1904 ? LocalDate.of(1904, 1, 1)
                    : LocalDate.of(1899, 12, days < 60 ? 31 : 30);
            LocalDate value = base.plusDays(days);
            return value.getYear() <= 9999 ? value : null;
        } catch (NumberFormatException | DateTimeException exception) { return null; }
    }

    private static LocalDate date(String text) {
        var matcher = DATE.matcher(text.trim());
        if (!matcher.matches()) return null;
        if (Integer.parseInt(matcher.group(1)) == 0) return null;
        try { return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))); }
        catch (DateTimeException exception) { return null; }
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() <= 64) text.append(part, 0, Math.min(part.length(), 65 - text.length()));
    }

    private static XMLStreamReader reader(java.io.InputStream input) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External XML forbidden"); });
        return factory.createXMLStreamReader(input);
    }

    private static int next(XMLStreamReader reader) throws XMLStreamException {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
            throw new XMLStreamException("DTD forbidden");
        return event;
    }

    private static UploadException invalid(MultipartFile file, String reason) {
        return UploadException.invalidFormat(file.getOriginalFilename(), reason);
    }
}
