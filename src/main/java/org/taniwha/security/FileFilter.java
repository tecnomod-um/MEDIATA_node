package org.taniwha.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Component
public class FileFilter {

    private static final Logger logger = LoggerFactory.getLogger(FileFilter.class);

    public boolean isFileInvalid(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            logger.warn("File validation failed, is empty or null.");
            return true;
        }
        String fileName = file.getOriginalFilename();
        if (!hasAllowedExtension(fileName)) {
            logger.warn("File validation failed, has disallowed extension: {}", fileName);
            return true;
        }
        if (containsDisallowedContent(file)) {
            logger.warn("File validation failed, contains disallowed content patterns: {}", fileName);
            return true;
        }
        return false;
    }

    private boolean hasAllowedExtension(String fileName) {
        if (fileName == null || !fileName.contains("."))
            return false;

        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        boolean isAllowed = AllowedExtensions.isAllowed(extension);
        logger.debug("File extension check for {}: {}", extension, isAllowed ? "Allowed" : "Disallowed");
        return isAllowed;
    }

    private boolean containsDisallowedContent(MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (DisallowedContentPatterns.containsDisallowed(line)) {
                    logger.warn("Disallowed content pattern found in line: {}", line);
                    return true;
                }
            }
        } catch (IOException e) {
            logger.error("Error reading file content", e);
            return true;
        }
        return false;
    }
}
