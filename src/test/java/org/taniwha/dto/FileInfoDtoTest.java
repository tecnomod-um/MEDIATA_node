package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FileInfoDtoTest {

    @Test
    void constructor_withAllParameters_shouldSetFields() {
        FileInfoDto dto = new FileInfoDto(
                "test.csv",
                1024L,
                1234567890000L,
                1234567900000L
        );

        assertThat(dto.getName()).isEqualTo("test.csv");
        assertThat(dto.getSizeBytes()).isEqualTo(1024L);
        assertThat(dto.getCreatedAtMs()).isEqualTo(1234567890000L);
        assertThat(dto.getLastModifiedAtMs()).isEqualTo(1234567900000L);
    }

    @Test
    void setters_shouldUpdateFields() {
        FileInfoDto dto = new FileInfoDto("old.txt", 100L, 1000L, 2000L);

        dto.setName("new.csv");
        dto.setSizeBytes(2048L);
        dto.setCreatedAtMs(3000L);
        dto.setLastModifiedAtMs(4000L);

        assertThat(dto.getName()).isEqualTo("new.csv");
        assertThat(dto.getSizeBytes()).isEqualTo(2048L);
        assertThat(dto.getCreatedAtMs()).isEqualTo(3000L);
        assertThat(dto.getLastModifiedAtMs()).isEqualTo(4000L);
    }

    @Test
    void equals_withSameValues_shouldBeEqual() {
        FileInfoDto dto1 = new FileInfoDto("file.txt", 512L, 1000L, 2000L);
        FileInfoDto dto2 = new FileInfoDto("file.txt", 512L, 1000L, 2000L);

        assertThat(dto1).isEqualTo(dto2);
    }

    @Test
    void equals_withDifferentValues_shouldNotBeEqual() {
        FileInfoDto dto1 = new FileInfoDto("file1.txt", 512L, 1000L, 2000L);
        FileInfoDto dto2 = new FileInfoDto("file2.txt", 512L, 1000L, 2000L);

        assertThat(dto1).isNotEqualTo(dto2);
    }

    @Test
    void hashCode_withSameValues_shouldBeSame() {
        FileInfoDto dto1 = new FileInfoDto("file.txt", 512L, 1000L, 2000L);
        FileInfoDto dto2 = new FileInfoDto("file.txt", 512L, 1000L, 2000L);

        assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
    }

    @Test
    void toString_shouldContainAllFields() {
        FileInfoDto dto = new FileInfoDto("data.csv", 2048L, 5000L, 6000L);

        String result = dto.toString();

        assertThat(result).contains("data.csv");
        assertThat(result).contains("2048");
        assertThat(result).contains("5000");
        assertThat(result).contains("6000");
    }

    @Test
    void constructor_withZeroValues_shouldWork() {
        FileInfoDto dto = new FileInfoDto("empty.txt", 0L, 0L, 0L);

        assertThat(dto.getSizeBytes()).isEqualTo(0L);
        assertThat(dto.getCreatedAtMs()).isEqualTo(0L);
        assertThat(dto.getLastModifiedAtMs()).isEqualTo(0L);
    }

    @Test
    void constructor_withLargeValues_shouldWork() {
        long largeSize = Long.MAX_VALUE;
        long largeTime = System.currentTimeMillis();

        FileInfoDto dto = new FileInfoDto("large.bin", largeSize, largeTime, largeTime + 1000);

        assertThat(dto.getSizeBytes()).isEqualTo(largeSize);
        assertThat(dto.getCreatedAtMs()).isEqualTo(largeTime);
        assertThat(dto.getLastModifiedAtMs()).isEqualTo(largeTime + 1000);
    }
}
