package com.ipgo.server.infrastructure.storage;

import com.ipgo.server.entity.UploadPeriod;
import com.ipgo.server.exception.UploadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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
    private static final Pattern CELL_REFERENCE = Pattern.compile("([A-Z]{1,3})[1-9]\\d*");
    private static final Pattern DATE = Pattern.compile(
            "^(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})(?:[ T](?:[01]\\d|2[0-3]):[0-5]\\d"
                    + "(?::[0-5]\\d(?:\\.\\d+)?)?(?:Z|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)?)?$");

    private UploadPeriodReader() { }

    public static UploadPeriod resolve(MultipartFile file) {
        var named = UploadPeriod.findInFileName(file.getOriginalFilename());
        if (named.isPresent()) return named.get();
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
                LocalDate latest = latestDate(zip, sheet, sharedDates(zip), workbook.date1904());
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

    private static List<LocalDate> sharedDates(ZipFile zip) throws IOException, XMLStreamException {
        var dates = new ArrayList<LocalDate>();
        var entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return dates;
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
                            dates.add(text == null ? null : date(text.toString()));
                            text = null;
                        }
                    }
                }
            } finally { reader.close(); }
        }
        return dates;
    }

    private static LocalDate latestDate(ZipFile zip, String sheet, List<LocalDate> shared, boolean date1904)
            throws IOException, XMLStreamException {
        LocalDate latest = null;
        try (var input = zip.getInputStream(zip.getEntry(sheet))) {
            var reader = reader(input);
            StringBuilder text = null;
            String type = null;
            boolean value = false;
            int column = 0;
            int phoneticDepth = 0;
            try {
                while (reader.hasNext()) {
                    int event = next(reader);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if ("rPh".equals(reader.getLocalName())) phoneticDepth++;
                        if ("row".equals(reader.getLocalName())) column = 0;
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
                            LocalDate candidate = cellDate(text.toString(), type, shared, date1904);
                            if (candidate != null && (latest == null || candidate.isAfter(latest))) latest = candidate;
                            text = null;
                        }
                    }
                }
            } finally { reader.close(); }
        }
        return latest;
    }

    private static int columnIndex(String reference) throws XMLStreamException {
        var match = CELL_REFERENCE.matcher(reference);
        if (!match.matches()) throw new XMLStreamException("Invalid worksheet cell reference");
        int column = 0;
        for (char letter : match.group(1).toCharArray()) column = column * 26 + letter - 'A' + 1;
        return column;
    }

    private static LocalDate cellDate(String text, String type, List<LocalDate> shared, boolean date1904) {
        try {
            if ("s".equals(type)) {
                int index = Integer.parseInt(text.trim());
                return index >= 0 && index < shared.size() ? shared.get(index) : null;
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
