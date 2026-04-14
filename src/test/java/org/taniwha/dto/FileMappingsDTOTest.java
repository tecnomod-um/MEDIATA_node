package org.taniwha.dto;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.mapping.MappingSpecDTO;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileMappingsDTOTest {

    @Test
    void gettersAndSetters_roundTrip() {
        FileMappingsDTO dto = new FileMappingsDTO();

        Map<String, List<String>> fileMappings = Map.of("cfg1", List.of("d1.csv", "d2.csv"));
        dto.setFileMappings(fileMappings);

        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setSpecVersion("1.0");
        dto.setMappingSpec(spec);

        DataCleaningOptionsDTO cleaningOptions = new DataCleaningOptionsDTO();
        dto.setCleaningOptions(cleaningOptions);

        assertThat(dto.getFileMappings()).isSameAs(fileMappings);
        assertThat(dto.getMappingSpec()).isSameAs(spec);
        assertThat(dto.getCleaningOptions()).isSameAs(cleaningOptions);
    }

    @Test
    void defaultValues_areNull() {
        FileMappingsDTO dto = new FileMappingsDTO();

        assertThat(dto.getFileMappings()).isNull();
        assertThat(dto.getMappingSpec()).isNull();
        assertThat(dto.getCleaningOptions()).isNull();
    }
}
