package org.taniwha.privacy.pooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.security.FileFilter;
import org.taniwha.service.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SecurePoolInputsTest {
    @TempDir Path root;
    private final ObjectMapper mapper=new ObjectMapper();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2,3,4,5})
    void smallGroupsUseDurableIndependentInputsAndDoNotAlterOrdinaryHistory(int smallCount) throws Exception {
        var directory=Files.createDirectories(root.resolve("datasets"));
        Files.writeString(directory.resolve("input.csv"),"Partition,Reading\n"+"0,0\n".repeat(8)+"1,5\n".repeat(smallCount));
        var guard=new FileFilter();var files=new FileService(guard,root.toString());var rows=new DataProcessingService(guard);
        var extraction=new DatasetElementExtractionService(files,rows);
        var filters=Map.<String,Object>of("operator","AND","conditions",Map.of("Partition",Map.of("operators",List.of(),
                "conditions",List.of(Map.of("type","equal","filterType","continuous","value","1")))));
        var source=new SecurePoolSource("alpha","input.csv",filters);
        var first=new SecurePoolInputs(root.toString(),files,rows,mapper,extraction).release(source);
        var restarted=new SecurePoolInputs(root.toString(),files,rows,mapper,extraction);
        assertThat(restarted.release(source)).isEqualTo(first);
        assertThat(first.rowEpsilon()).isLessThanOrEqualTo(4*Math.log(2));
        assertThat(Files.exists(root.resolve("discovery-privacy/approximate-pooling"))).isFalse();
        assertThat(Files.exists(root.resolve("discovery-privacy/registry.json"))).isFalse();
        var whole=restarted.release(new SecurePoolSource("alpha","input.csv",null));
        assertThat(whole.selectionScope()).isEqualTo("dataset");
        assertThat(whole.rowEpsilon()).isEqualTo(first.rowEpsilon());
        assertThat(restarted.release(source)).isEqualTo(first);
    }

    @Test void cachedPaddingSurvivesRestartAndIsScopedByFeatureIdentity() throws Exception {
        var source=new PrivatePoolingDomain(List.of(-.5,.5),null,List.of(0.),"continuous",false,true);
        var target=new PrivatePoolingDomain(List.of(-.5,.5,1.5),null,List.of(0.,1.),"continuous",false,true);
        var histogram=new PrivatePoolingHistogram(source,List.of(100L,0L),4.);
        var grids=new SecurePoolRebinCache(root.toString(),mapper);
        var first=grids.rebin("first",histogram,target);
        assertThat(new SecurePoolRebinCache(root.toString(),mapper).rebin("first",histogram,target)).isEqualTo(first);
        grids.rebin("second",histogram,target);
        try(var entries=Files.list(root.resolve("discovery-privacy/secure-pooling/postprocessing"))) {
            assertThat(entries.filter(p->p.toString().endsWith(".json")).count()).isEqualTo(2);
        }
    }

    @Test void coarseningMeasurementsDoesNotReduceTheSupportedGroupingDomain() throws Exception {
        Files.createDirectories(root.resolve("datasets"));
        var csv=new StringBuilder("Partition,Reading\n");
        for(int group=0;group<49;group++) for(int i=0;i<7;i++) csv.append(group).append(',').append(i%2*5).append('\n');
        Files.writeString(root.resolve("datasets/groups.csv"),csv);
        var guard=new FileFilter();var files=new FileService(guard,root.toString());var rows=new DataProcessingService(guard);
        var extraction=new DatasetElementExtractionService(files,rows);
        var filters=Map.<String,Object>of("operator","AND","conditions",Map.of("Partition",Map.of("operators",List.of(),
                "conditions",List.of(Map.of("type","equal","filterType","continuous","value","48")))));
        var release=new SecurePoolInputs(root.toString(),files,rows,mapper,extraction)
                .release(new SecurePoolSource("alpha","groups.csv",filters));
        assertThat(release.group()).isEqualTo("48");
        assertThat(release.features().get("Reading").domain().values()).containsExactly(0.,5.);
    }
}
