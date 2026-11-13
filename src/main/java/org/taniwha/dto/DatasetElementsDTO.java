package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DatasetElementsDTO {
    private String fileName;
    private List<DatasetElementDTO> elements;
}
