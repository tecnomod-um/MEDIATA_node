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
}
