package org.taniwha.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.exception.InvalidFileException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class FileFilterTest {

    private FileFilter fileFilter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileFilter = new FileFilter();
    }

    @Test
    void validate_multipartFile_withValidCsvFile_shouldNotThrow() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "data.csv",
                "text/csv",
                "name,age\nAlice,30\nBob,25".getBytes(StandardCharsets.UTF_8)
        );

        assertThatCode(() -> fileFilter.validate(file))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_multipartFile_withDisallowedExtension_shouldThrow() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "malicious.exe",
                "application/octet-stream",
                "malicious content".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> fileFilter.validate(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("malicious.exe");
    }

    @Test
    void validate_multipartFile_withNullFile_shouldThrow() {
        assertThatThrownBy(() -> fileFilter.validate((MultipartFile) null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("null");
    }

    @Test
    void validate_multipartFile_withEmptyFile_shouldThrow() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "empty.csv",
                "text/csv",
                new byte[0]
        );

        assertThatThrownBy(() -> fileFilter.validate(file))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void validate_path_withValidCsvFile_shouldNotThrow() throws IOException {
        Path csvFile = tempDir.resolve("valid.csv");
        Files.writeString(csvFile, "name,age\nAlice,30", StandardCharsets.UTF_8);

        assertThatCode(() -> fileFilter.validate(csvFile))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_path_withDisallowedExtension_shouldThrow() throws IOException {
        Path exeFile = tempDir.resolve("malicious.exe");
        Files.writeString(exeFile, "malicious content", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> fileFilter.validate(exeFile))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("malicious.exe");
    }

    @Test
    void validate_path_withNullPath_shouldThrow() {
        assertThatThrownBy(() -> fileFilter.validate((Path) null))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("null");
    }

    @Test
    void validate_path_withNonReadablePath_shouldThrow() {
        Path nonExistentFile = tempDir.resolve("nonexistent.csv");

        assertThatThrownBy(() -> fileFilter.validate(nonExistentFile))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void isFileInvalid_multipartFile_withValidTsvFile_shouldReturnFalse() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "data.tsv",
                "text/tab-separated-values",
                "name\tage\nAlice\t30".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(fileFilter.isFileInvalid(file)).isFalse();
    }

    @Test
    void isFileInvalid_multipartFile_withValidXlsxFile_shouldReturnFalse() {
        // Create a minimal XLSX file header (PK zip signature)
        byte[] xlsxHeader = new byte[]{0x50, 0x4B, 0x03, 0x04};
        MultipartFile file = new MockMultipartFile(
                "file",
                "data.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxHeader
        );

        assertThat(fileFilter.isFileInvalid(file)).isFalse();
    }

    @Test
    void isFileInvalid_path_withValidTtlFile_shouldReturnFalse() throws IOException {
        Path ttlFile = tempDir.resolve("data.ttl");
        Files.writeString(ttlFile, "@prefix ex: <http://example.org/> .", StandardCharsets.UTF_8);

        assertThat(fileFilter.isFileInvalid(ttlFile)).isFalse();
    }

    @Test
    void isFileInvalid_multipartFile_withNullFilename_shouldReturnTrue() {
        MultipartFile file = new MockMultipartFile(
                "file",
                null,
                "text/plain",
                "content".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(fileFilter.isFileInvalid(file)).isTrue();
    }

    @Test
    void isFileInvalid_multipartFile_withDisallowedTxtExtension_shouldReturnTrue() {
        // txt is not in AllowedExtensions, so it should be invalid
        MultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "Some notes content".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(fileFilter.isFileInvalid(file)).isTrue();
    }

    // -------------------------------------------------------------------------
    // isFileInvalid(Path) – disallowed content branches
    // -------------------------------------------------------------------------

    @Test
    void isFileInvalid_path_csvWithDisallowedContent_returnsTrue() throws IOException {
        Path csvFile = tempDir.resolve("bad.csv");
        Files.writeString(csvFile, "col1,col2\n<script>alert(1)</script>,value", StandardCharsets.UTF_8);

        assertThat(fileFilter.isFileInvalid(csvFile)).isTrue();
    }

    @Test
    void isFileInvalid_path_csvWithJavascriptScheme_returnsTrue() throws IOException {
        Path csvFile = tempDir.resolve("js.csv");
        Files.writeString(csvFile, "col1\njavascript:void(0)", StandardCharsets.UTF_8);

        assertThat(fileFilter.isFileInvalid(csvFile)).isTrue();
    }

    @Test
    void isFileInvalid_path_xlsxWithDisallowedContent_returnsTrue() throws Exception {
        // Build a minimal xlsx (zip with STORED/uncompressed entries) so the raw
        // bytes of the file contain the disallowed pattern and scanXlsxHead finds it.
        Path xlsxFile = tempDir.resolve("bad.xlsx");
        byte[] content = "javascript:void(0) evil content here".getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(xlsxFile))) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml");
            entry.setMethod(java.util.zip.ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }

        assertThat(fileFilter.isFileInvalid(xlsxFile)).isTrue();
    }

    @Test
    void isFileInvalid_path_xlsxWithCleanContent_returnsFalse() throws Exception {
        // Build a minimal xlsx (zip) with clean content
        Path xlsxFile = tempDir.resolve("clean.xlsx");
        byte[] content = "<worksheet><sheetData><row><c><v>1</v></c></row></sheetData></worksheet>"
                .getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(xlsxFile))) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml");
            entry.setMethod(java.util.zip.ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }

        assertThat(fileFilter.isFileInvalid(xlsxFile)).isFalse();
    }

    // -------------------------------------------------------------------------
    // isFileInvalid(MultipartFile) – disallowed content in CSV
    // -------------------------------------------------------------------------

    @Test
    void isFileInvalid_multipartCsv_withDisallowedContent_returnsTrue() {
        // Use the exact patterns from DisallowedContentPatterns
        MultipartFile file = new MockMultipartFile(
                "file",
                "data.csv",
                "text/csv",
                "name\n<script>evil()</script>".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(fileFilter.isFileInvalid(file)).isTrue();
    }

    @Test
    void isFileInvalid_multipartCsv_withVbscript_returnsTrue() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "data.csv",
                "text/csv",
                "col\nvbscript:run()".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(fileFilter.isFileInvalid(file)).isTrue();
    }

    // -------------------------------------------------------------------------
    // getExtension edge cases (via isFileInvalid dispatching)
    // -------------------------------------------------------------------------

    @Test
    void isFileInvalid_path_noExtension_returnsTrue() throws IOException {
        // A file with no extension is not in AllowedExtensions → invalid
        Path noExt = tempDir.resolve("noextension");
        Files.writeString(noExt, "data", StandardCharsets.UTF_8);

        assertThat(fileFilter.isFileInvalid(noExt)).isTrue();
    }

    @Test
    void isFileInvalid_multipartFile_noExtension_returnsTrue() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "noextension",
                "application/octet-stream",
                "data".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(fileFilter.isFileInvalid(file)).isTrue();
    }

    // -------------------------------------------------------------------------
    // validate(Path) with disallowed content – expect exception
    // -------------------------------------------------------------------------

    @Test
    void validate_path_withDisallowedContent_shouldThrow() throws IOException {
        Path csvFile = tempDir.resolve("inject.csv");
        Files.writeString(csvFile, "col\n<script>evil()</script>", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> fileFilter.validate(csvFile))
                .isInstanceOf(org.taniwha.exception.InvalidFileException.class);
    }

    @Test
    void validate_multipartFile_withDisallowedContent_shouldThrow() {
        MultipartFile file = new MockMultipartFile(
                "file",
                "attack.csv",
                "text/csv",
                "col\n<script>alert()</script>".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> fileFilter.validate(file))
                .isInstanceOf(org.taniwha.exception.InvalidFileException.class);
    }
}

