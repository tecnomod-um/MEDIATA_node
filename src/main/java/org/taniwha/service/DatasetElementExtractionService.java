package org.taniwha.service;

import org.springframework.stereotype.Service;
import org.taniwha.dto.DatasetElementDTO;
import org.taniwha.dto.DatasetElementsDTO;

import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DatasetElementExtractionService {

    private final FileService fileService;
    private final DataProcessingService dataProcessingService;

    public DatasetElementExtractionService(FileService fileService, DataProcessingService dataProcessingService) {
        this.fileService = fileService;
        this.dataProcessingService = dataProcessingService;
    }

    public List<DatasetElementsDTO> extractDatasetElements(List<String> fileNames) throws IOException {
        List<DatasetElementsDTO> out = new ArrayList<>();
        if (fileNames == null) return out;

        for (String fileName : fileNames) {
            if (fileName == null || fileName.isBlank()) continue;
            out.add(extractSingleDataset(fileName));
        }
        return out;
    }

    private DatasetElementsDTO extractSingleDataset(String fileName) throws IOException {
        return extractDatasetElements(fileName, Paths.get(fileService.getDatasetFilePath(fileName)));
    }

    public DatasetElementsDTO extractDatasetElements(String fileName, Path input) throws IOException {
        Map<String, ColumnAccumulator> byColumn = new LinkedHashMap<>();

        dataProcessingService.streamRows(
                input,
                row -> row.forEach((column, value) ->
                        byColumn.computeIfAbsent(column, ignored -> new ColumnAccumulator()).accept(value))
        );

        List<DatasetElementDTO> elements = new ArrayList<>();
        for (Map.Entry<String, ColumnAccumulator> entry : byColumn.entrySet()) {
            String column = entry.getKey() == null ? "" : entry.getKey().trim();
            if (column.isEmpty() || !entry.getValue().canRelease()) continue;
            elements.add(new DatasetElementDTO(column, entry.getValue().toElementValues()));
        }

        return new DatasetElementsDTO(fileName, elements);
    }

}
