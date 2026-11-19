package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.List;

// Persist data-free grid padding too, so refreshes reproduce the same result.
@Component
public class SecurePoolRebinCache {
    private final Path directory;
    private final ObjectMapper mapper;
    private final SecureRandom random=new SecureRandom();
    public SecurePoolRebinCache(@Value("${app.path}") String base,ObjectMapper mapper) {
        this.directory=Path.of(base).toAbsolutePath().resolve("discovery-privacy/secure-pooling/postprocessing");this.mapper=mapper;
    }

    public synchronized long[] rebin(Object scope,PrivatePoolingHistogram input,PrivatePoolingDomain target) throws IOException {
        String key=SecurePoolCrypto.hash(mapper.writeValueAsString(List.of("secure-grid-v1",scope,input,target)));
        if(directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.createDirectories(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(directory);
        try(var channel=FileChannel.open(directory.resolve("cache.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var ignored=channel.lock()) {
            Path stored=directory.resolve(key+".json");
            if(Files.exists(stored)) {
                long[] counts=mapper.readValue(stored.toFile(),long[].class);
                if(counts.length!=target.size()) throw new IOException("Saved grid shape differs");
                for(int cell=0;cell<counts.length;cell++)
                    if(counts[cell] < -64_000_000_000L || counts[cell] > 64_000_000_000L || (target.structuralZero(cell)&&counts[cell]!=0))
                        throw new IOException("Invalid saved grid");
                return counts;
            }
            long[] counts=SecurePoolDomains.rebin(input,target,random);
            Path temporary=Files.createTempFile(directory,"grid-",".tmp");
            try {
                mapper.writeValue(temporary.toFile(),counts);
                try(var file=FileChannel.open(temporary,StandardOpenOption.WRITE)) {file.force(true);}
                Files.move(temporary,stored,StandardCopyOption.ATOMIC_MOVE);
                if(directory.getFileSystem().supportedFileAttributeViews().contains("posix"))
                    try(var folder=FileChannel.open(directory,StandardOpenOption.READ)) {folder.force(true);}
            } finally {Files.deleteIfExists(temporary);}
            return counts;
        }
    }
}
