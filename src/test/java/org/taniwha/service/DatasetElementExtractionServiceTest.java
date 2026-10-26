package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.security.FileFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

class DatasetElementExtractionServiceTest {

    @TempDir
    Path baseDir;

    @Test
    void naturalLanguageColumnsExposeTypeMarkerInsteadOfExampleText() throws Exception {
        Path datasets = Files.createDirectories(baseDir.resolve("datasets"));
        String rows = IntStream.range(0, 11)
                .mapToObj(i -> "Patient " + i + " reports a distinct change in daily mobility;" + i)
                .reduce("note;score\n", (text, row) -> text + row + "\n");
        Files.writeString(datasets.resolve("notes.csv"), rows);

        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));
        FileService fileService = new FileService(fileFilter, baseDir.toString());
        DatasetElementExtractionService service = new DatasetElementExtractionService(
                fileService, new DataProcessingService(fileFilter));

        var result = service.extractDatasetElements(List.of("notes.csv"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getElements())
                .anySatisfy(element -> {
                    assertThat(element.getColumn()).isEqualTo("note");
                    assertThat(element.getValues()).containsExactly("Natural Language");
                });
    }

    @Test
    void columnsWithFiveOrFewerObservationsAreNotExtracted() throws Exception {
        Path datasets = Files.createDirectories(baseDir.resolve("datasets"));
        Files.writeString(datasets.resolve("small.csv"),
                "private_note;score\nOne;1\nTwo;2\nThree;3\nFour;4\nFive;5\n");

        FileFilter fileFilter = mock(FileFilter.class);
        doNothing().when(fileFilter).validate(any(Path.class));
        FileService fileService = new FileService(fileFilter, baseDir.toString());
        DatasetElementExtractionService service = new DatasetElementExtractionService(
                fileService, new DataProcessingService(fileFilter));

        var result = service.extractDatasetElements(List.of("small.csv"));
        assertThat(result.get(0).getElements()).isEmpty();
    }
}
