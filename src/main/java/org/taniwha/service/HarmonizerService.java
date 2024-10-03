package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class HarmonizerService {

    private static final Logger logger = LoggerFactory.getLogger(HarmonizerService.class);

    public String saveFile(MultipartFile file) {
        String folder = "/opt/shared-folder/";
        Path filePath = Paths.get(folder + file.getOriginalFilename());

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, file.getBytes());
            logger.info("File saved: {}", filePath);
            return "File saved successfully: " + filePath;
        } catch (IOException e) {
            logger.error("Error saving file", e);
            return "Failed to save file: " + e.getMessage();
        }
    }
}
