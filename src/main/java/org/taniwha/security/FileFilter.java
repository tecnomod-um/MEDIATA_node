package org.taniwha.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.exception.InvalidFileException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileFilter {

    private static final Logger logger = LoggerFactory.getLogger(FileFilter.class);
    private static final int QUICK_SCAN_BYTES = 4 * 1024;

    public void validate(MultipartFile file) {
        if (file == null || isFileInvalid(file))
            throw new InvalidFileException("Dangerous upload: " + (file != null ? file.getOriginalFilename() : "null"));
    }

    public void validate(Path path) {
        if (path == null || isFileInvalid(path))
            throw new InvalidFileException("Dangerous server file: " + (path != null ? path : "null"));
    }

    public boolean isFileInvalid(Path path) {
        if (path == null || !Files.isReadable(path)) {
            logger.warn("File validation failed, not readable: {}", path);
            return true;
        }

        String name = path.getFileName().toString();
        String ext  = getExtension(name);

        if (!AllowedExtensions.isAllowed(ext)) {
            logger.warn("Disallowed path extension: {}", name);
            return true;
        }

        try {
            if (isTextExtension(ext)) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    return scanAsText(reader, path.toString());
                }
            } else if ("xlsx".equals(ext)) return scanXlsxHead(Files.newInputStream(path), path.toString());

            return false;
        } catch (IOException e) {
            logger.error("Error scanning {}: {}", path, e.getMessage());
            return isTextExtension(ext);
        }
    }

    public boolean isFileInvalid(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            logger.warn("File validation failed, is empty or null.");
            return true;
        }

        String name = file.getOriginalFilename();
        String ext  = getExtension(name);

        if (!AllowedExtensions.isAllowed(ext)) {
            logger.warn("Disallowed file extension: {}", name);
            return true;
        }

        try {
            if (isTextExtension(ext)) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                    return scanAsText(reader, name);
                }
            } else if ("xlsx".equals(ext)) {
                try (InputStream in = file.getInputStream()) {
                    return scanXlsxHead(in, name);
                }
            }

            return false;
        } catch (IOException e) {
            logger.error("Error scanning upload {}: {}", name, e.getMessage());
            return true;
        }
    }

    private boolean scanAsText(BufferedReader reader, String label) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (DisallowedContentPatterns.containsDisallowed(line)) {
                logger.warn("Disallowed content in {}: {}", label, line);
                return true;
            }
        }
        return false;
    }

    private boolean scanXlsxHead(InputStream in, String label) throws IOException {
        byte[] buf = new byte[QUICK_SCAN_BYTES];
        int n = in.read(buf);
        if (n > 0) {
            String snippet = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (DisallowedContentPatterns.containsDisallowed(snippet)) {
                logger.warn("Disallowed content in quick scan of {}", label);
                return true;
            }
        }
        return false;
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.lastIndexOf('.');
        return (idx >= 0 && idx < fileName.length() - 1)
                ? fileName.substring(idx + 1).toLowerCase()
                : "";
    }

    private boolean isTextExtension(String ext) {
        return ext.equals("csv") || ext.equals("txt") || ext.equals("log");
    }
}
