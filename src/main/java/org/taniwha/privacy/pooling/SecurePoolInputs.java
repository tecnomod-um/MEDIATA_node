package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.taniwha.dto.FileFilters;
import org.taniwha.service.DataProcessingService;
import org.taniwha.service.FileService;
import org.taniwha.service.DatasetElementExtractionService;
import java.io.IOException;

/** Independent, durable protected inputs exposed only through masked rounds. */
@Component
public class SecurePoolInputs {
    private final PrivatePoolingService store;

    public SecurePoolInputs(@Value("${app.path}") String base, FileService files, DataProcessingService rows,
                            ObjectMapper mapper, DatasetElementExtractionService extraction) {
        // Separate releases compose with the ordinary site's allowance. Do not
        // inherit a site's utility-focused setting of 80 for pooled computation.
        // Compact ordinal supports stay exact; dense numerical/date domains use
        // coarser public grids before adding noise, improving signal per bin.
        store=new PrivatePoolingService(base,files,rows,mapper,new PrivatePoolingMetadata(extraction,32),4*Math.log(2),"secure-pooling");
    }

    public PrivatePoolingRelease release(SecurePoolSource source) throws IOException {
        return versionedSource(source).release();
    }

    VersionedPoolingRelease versionedSource(SecurePoolSource source) throws IOException {
        FileFilters request=new FileFilters();request.setFileName(source.fileName());request.setFilters(source.filters());
        return store.versionedRelease(request).orElseThrow(()->new PrivatePoolingUnavailableException("secure-pool-unsupported-filter",
                "Secure pooling needs a published equality grouping. An unfiltered release can reuse an existing grouping covering all rows."));
    }
}
