package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.taniwha.model.Dataset;
import org.taniwha.model.Distribution;
import org.taniwha.model.FileCategory;
import org.taniwha.model.NodeMetadata;
import org.taniwha.security.FileFilter;
import org.taniwha.dto.FileInfoDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

class FileServiceTest {

    @Mock
    private FileFilter fileFilter;

    private FileService fileService;

    @TempDir
    Path tempBase;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(fileFilter).validate(any(Path.class));
        fileService = new FileService(fileFilter, tempBase.toString());
    }

    @Test
    void listDatasetFiles_shouldReturnOnlyRegularFileNames() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.createFile(ds.resolve("a.csv"));
        Files.createFile(ds.resolve("b.txt"));
        Files.createDirectories(ds.resolve("subdir"));

        List<String> names = fileService.listDatasetFiles();
        assertThat(names).containsExactlyInAnyOrder("a.csv", "b.txt");
    }

    @Test
    void listMappedDatasetFiles_sameAsDatasetsFolder() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.createFile(ds.resolve("m1"));
        List<String> names = fileService.listMappedDatasetFiles();
        assertThat(names).containsExactly("m1");
    }

    @Test
    void listFhirMappingFiles_andElementFiles_andMetadataFiles() throws IOException {
        Path fm = tempBase.resolve("fhir_mappings");
        Files.createDirectories(fm);
        Files.createFile(fm.resolve("f1"));
        assertThat(fileService.listFhirMappingFiles()).containsExactly("f1");

        Path el = tempBase.resolve("dataset_elements");
        Files.createDirectories(el);
        Files.createFile(el.resolve("e1"));
        assertThat(fileService.listElementFiles()).containsExactly("e1");

        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        Files.createFile(md.resolve("d1"));
        assertThat(fileService.listMetadataFiles()).containsExactly("d1");
    }

    @Test
    void parseNodeMetadata_noMetadataFiles_returnsNull() {
        assertThat(fileService.parseNodeMetadata()).isNull();
    }

    @Test
    void parseNodeMetadata_readsSingleTtlFile_andParsesAllFields() throws Exception {
        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        String ttl = """
                @prefix dcat: <http://www.w3.org/ns/dcat#> .
                @prefix dct: <http://purl.org/dc/terms/> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                <http://example.org/ds1> a dcat:Dataset ;
                  dct:title "MyTitle" ;
                  dct:description "MyDesc" ;
                  dct:identifier "ID-123" ;
                  dct:issued "2025-01-01" ;
                  dct:modified "2025-02-02" ;
                  dct:accrualPeriodicity "monthly" ;
                  dcat:keyword "k1", "k2" ;
                  dcat:theme <http://th1> ;
                  dct:language <http://lang1> ;
                  dct:publisher <http://pub1> ;
                  dcat:contactPoint <http://cp1> ;
                  dct:spatial <http://sp1> ;
                  dct:temporal <http://tmp1> ;
                  dcat:distribution <http://ex.org/dist1> .
                <http://pub1> foaf:name "PubName" .
                <http://ex.org/dist1> a dcat:Distribution ;
                  dct:title "DistTitle" ;
                  dct:description "DistDesc" ;
                  dct:format "csv" ;
                  dct:license <http://lic1> ;
                  dcat:downloadURL <http://dl1> .
                """;
        Path ttlFile = md.resolve("meta.ttl");
        Files.writeString(ttlFile, ttl, StandardOpenOption.CREATE);

        NodeMetadata nm = fileService.parseNodeMetadata();
        assertThat(nm).isNotNull();
        assertThat(nm.getContext())
                .isEqualTo("https://www.w3.org/ns/dcat.jsonld");

        List<Dataset> dss = nm.getDataset();
        assertThat(dss).hasSize(1);
        Dataset ds = dss.get(0);

        assertThat(ds.getTitle()).isEqualTo("MyTitle");
        assertThat(ds.getDescription()).isEqualTo("MyDesc");
        assertThat(ds.getIdentifier()).isEqualTo("ID-123");
        assertThat(ds.getIssued()).isEqualTo("2025-01-01");
        assertThat(ds.getModified()).isEqualTo("2025-02-02");
        assertThat(ds.getAccrualPeriodicity()).isEqualTo("monthly");

        assertThat(ds.getKeyword()).containsExactlyInAnyOrder("k1", "k2");
        assertThat(ds.getTheme()).containsExactly("http://th1");
        assertThat(ds.getLanguage()).containsExactly("http://lang1");

        assertThat(ds.getPublisher()).isEqualTo("PubName");
        assertThat(ds.getContactPoint()).isEqualTo("http://cp1");
        assertThat(ds.getSpatial()).isEqualTo("http://sp1");
        assertThat(ds.getTemporal()).isEqualTo("http://tmp1");

        List<Distribution> dist = ds.getDistribution();
        assertThat(dist).hasSize(1);
        Distribution d = dist.get(0);
        assertThat(d.getTitle()).isEqualTo("DistTitle");
        assertThat(d.getDescription()).isEqualTo("DistDesc");
        assertThat(d.getFormat()).isEqualTo("csv");
        assertThat(d.getLicense()).isEqualTo("http://lic1");
        assertThat(d.getDownloadURL()).isEqualTo("http://dl1");
    }

    @Test
    void parseNodeMetadata_ioErrorOnRead_returnsNull() throws Exception {
        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        Files.createDirectory(md.resolve("meta.ttl"));
        assertThat(fileService.parseNodeMetadata()).isNull();
    }

    @Test
    void listDatasetFiles_whenNoDirectory_returnsEmpty() {
        List<String> names = fileService.listDatasetFiles();
        assertThat(names).isEmpty();
        assertThat(fileService.listMappedDatasetFiles()).isEmpty();
    }

    @Test
    void listOtherFileFolders_whenNoDirectory_returnsEmpty() {
        assertThat(fileService.listFhirMappingFiles()).isEmpty();
        assertThat(fileService.listElementFiles()).isEmpty();
        assertThat(fileService.listMetadataFiles()).isEmpty();
    }

    @Test
    void getFilePath_methods_returnCorrectSubpaths() {
        String ds = fileService.getDatasetFilePath("foo.csv");
        assertThat(ds).endsWith(Paths.get("datasets", "foo.csv").toString());

        String el = fileService.getElementFilePath("bar.txt");
        assertThat(el).endsWith(Paths.get("dataset_elements", "bar.txt").toString());

        String md = fileService.getMetadataFilePath("baz.ttl");
        assertThat(md).endsWith(Paths.get("dataset_metadata", "baz.ttl").toString());
    }

    @Test
    void saveDatasetElements_sanitizesFilenameAndWrites() throws IOException {
        Path elDir = tempBase.resolve("dataset_elements");
        Files.createDirectories(elDir);
        String returned = fileService.saveDatasetElements("My File!.CSV", "hello,world");
        Path out = Paths.get(returned);
        assertThat(out.getParent()).isEqualTo(elDir);
        assertThat(out.getFileName().toString()).isEqualTo("My_File__elements.csv");
        assertThat(Files.readString(out)).isEqualTo("hello,world");
    }

    @Test
    void saveDatasetElements_ioError_throwsRuntimeException() throws IOException {
        Path el = tempBase.resolve("dataset_elements");
        Files.createFile(el);

        assertThatThrownBy(() -> fileService.saveDatasetElements("x.csv", "data"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error saving dataset elements for file: x.csv");
    }

    @Test
    void parseNodeMetadata_publisherWithoutName_fallsBackToUri() throws Exception {
        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        String ttl = """
                @prefix dcat: <http://www.w3.org/ns/dcat#> .
                @prefix dct:  <http://purl.org/dc/terms/> .
                <http://example.org/ds> a dcat:Dataset ;
                  dct:publisher <http://example.org/org> .
                """;
        Path f = md.resolve("onlypub.ttl");
        Files.writeString(f, ttl);
        NodeMetadata nm = fileService.parseNodeMetadata();
        assertThat(nm).isNotNull();
        Dataset ds = nm.getDataset().get(0);
        assertThat(ds.getPublisher()).isEqualTo("http://example.org/org");
    }

    @Test
    void parseNodeMetadata_temporalNoDates_usesResourceUri() throws Exception {
        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        String ttl = """
                @prefix dcat: <http://www.w3.org/ns/dcat#> .
                @prefix dct:  <http://purl.org/dc/terms/> .
                <http://example.org/ds> a dcat:Dataset ;
                  dct:temporal <http://example.org/period> .
                """;
        Path f = md.resolve("temp.ttl");
        Files.writeString(f, ttl);
        NodeMetadata nm = fileService.parseNodeMetadata();
        Dataset ds = nm.getDataset().get(0);
        assertThat(ds.getTemporal()).isEqualTo("http://example.org/period");
    }

    @Test
    void parseNodeMetadata_noDistribution_returnsNullDistribution() throws Exception {
        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        String ttl = """
                @prefix dcat: <http://www.w3.org/ns/dcat#> .
                @prefix dct:  <http://purl.org/dc/terms/> .
                <http://example.org/ds> a dcat:Dataset ;
                  dct:title "T" .
                """;
        Path f = md.resolve("nodist.ttl");
        Files.writeString(f, ttl);
        NodeMetadata nm = fileService.parseNodeMetadata();
        Dataset ds = nm.getDataset().get(0);
        assertThat(ds.getDistribution()).isNull();
    }

    @Test
    void mappedDatasetsFolder_isSameAsDatasetsFolder() {
        String foo = fileService.getMappedDatasetsFolder();
        assertThat(foo).endsWith("/datasets");
        assertThat(fileService.listMappedDatasetFiles()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // resolveXxxFilePath delegates
    // -----------------------------------------------------------------------

    @Test
    void resolveDatasetFilePath_existingFile_returnsPath() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.createFile(ds.resolve("data.csv"));

        Path p = fileService.resolveDatasetFilePath("data.csv");
        assertThat(p.getFileName().toString()).isEqualTo("data.csv");
    }

    @Test
    void resolveElementFilePath_existingFile_returnsPath() throws IOException {
        Path el = tempBase.resolve("dataset_elements");
        Files.createDirectories(el);
        Files.createFile(el.resolve("el.csv"));

        Path p = fileService.resolveElementFilePath("el.csv");
        assertThat(p.getFileName().toString()).isEqualTo("el.csv");
    }

    @Test
    void resolveMappedDatasetFilePath_existingFile_returnsPath() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.createFile(ds.resolve("mapped.csv"));

        Path p = fileService.resolveMappedDatasetFilePath("mapped.csv");
        assertThat(p.getFileName().toString()).isEqualTo("mapped.csv");
    }

    @Test
    void resolveMetadataFilePath_existingFile_returnsPath() throws IOException {
        Path md = tempBase.resolve("dataset_metadata");
        Files.createDirectories(md);
        Files.createFile(md.resolve("meta.ttl"));

        Path p = fileService.resolveMetadataFilePath("meta.ttl");
        assertThat(p.getFileName().toString()).isEqualTo("meta.ttl");
    }

    // -----------------------------------------------------------------------
    // resolveExistingFilePath – error cases (dirFor coverage for all categories)
    // -----------------------------------------------------------------------

    @Test
    void resolveExistingFilePath_fhirMappings_existingFile() throws IOException {
        Path dir = tempBase.resolve("fhir_mappings");
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("map.json"));

        Path p = fileService.resolveExistingFilePath(FileCategory.FHIR_MAPPINGS, "map.json");
        assertThat(p.getFileName().toString()).isEqualTo("map.json");
    }

    @Test
    void resolveExistingFilePath_datasetElements_existingFile() throws IOException {
        Path dir = tempBase.resolve("dataset_elements");
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("el.csv"));

        Path p = fileService.resolveExistingFilePath(FileCategory.DATASET_ELEMENTS, "el.csv");
        assertThat(p.getFileName().toString()).isEqualTo("el.csv");
    }

    @Test
    void resolveExistingFilePath_datasetMetadata_existingFile() throws IOException {
        Path dir = tempBase.resolve("dataset_metadata");
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("meta.ttl"));

        Path p = fileService.resolveExistingFilePath(FileCategory.DATASET_METADATA, "meta.ttl");
        assertThat(p.getFileName().toString()).isEqualTo("meta.ttl");
    }

    @Test
    void resolveExistingFilePath_mappedDatasets_existingFile() throws IOException {
        Path dir = tempBase.resolve("datasets");
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("parsed.csv"));

        Path p = fileService.resolveExistingFilePath(FileCategory.MAPPED_DATASETS, "parsed.csv");
        assertThat(p.getFileName().toString()).isEqualTo("parsed.csv");
    }

    @Test
    void resolveExistingFilePath_missingFile_throwsIllegalArgument() throws IOException {
        Path dir = tempBase.resolve("datasets");
        Files.createDirectories(dir);

        assertThatThrownBy(() -> fileService.resolveExistingFilePath(FileCategory.DATASETS, "missing.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File does not exist");
    }

    @Test
    void resolveExistingFilePath_pathTraversal_throwsIllegalArgument() {
        assertThatThrownBy(() -> fileService.resolveExistingFilePath(FileCategory.DATASETS, "../escape.csv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // listFilesWithInfo
    // -----------------------------------------------------------------------

    @Test
    void listFilesWithInfo_returnsFileMetadata() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.writeString(ds.resolve("a.csv"), "col1,col2\n1,2\n");
        Files.writeString(ds.resolve("b.csv"), "x\n");

        List<FileInfoDto> infos = fileService.listFilesWithInfo(FileCategory.DATASETS);

        assertThat(infos).hasSize(2);
        // sorted alphabetically
        assertThat(infos.get(0).getName()).isEqualTo("a.csv");
        assertThat(infos.get(1).getName()).isEqualTo("b.csv");
        assertThat(infos.get(0).getSizeBytes()).isGreaterThan(0);
    }

    @Test
    void listFilesWithInfo_emptyDirectory_returnsEmptyList() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);

        List<FileInfoDto> infos = fileService.listFilesWithInfo(FileCategory.DATASETS);

        assertThat(infos).isEmpty();
    }

    @Test
    void listFilesWithInfo_nonExistentDirectory_returnsEmptyList() {
        List<FileInfoDto> infos = fileService.listFilesWithInfo(FileCategory.DATASETS);
        assertThat(infos).isEmpty();
    }

    @Test
    void listFilesWithInfo_skipsSubdirectories() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.writeString(ds.resolve("real.csv"), "data");
        Files.createDirectories(ds.resolve("subdir"));

        List<FileInfoDto> infos = fileService.listFilesWithInfo(FileCategory.DATASETS);
        assertThat(infos).hasSize(1).extracting(FileInfoDto::getName).containsExactly("real.csv");
    }

    // -----------------------------------------------------------------------
    // deleteFile
    // -----------------------------------------------------------------------

    @Test
    void deleteFile_existingFile_deletesIt() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Path f = ds.resolve("del.csv");
        Files.writeString(f, "data");

        fileService.deleteFile(FileCategory.DATASETS, "del.csv");

        assertThat(Files.exists(f)).isFalse();
    }

    @Test
    void deleteFile_nonExistentFile_doesNotThrow() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);

        assertThatCode(() -> fileService.deleteFile(FileCategory.DATASETS, "ghost.csv"))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // renameFile
    // -----------------------------------------------------------------------

    @Test
    void renameFile_renamesSuccessfully() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.writeString(ds.resolve("old.csv"), "data");

        fileService.renameFile(FileCategory.DATASETS, "old.csv", "new.csv");

        assertThat(Files.exists(ds.resolve("old.csv"))).isFalse();
        assertThat(Files.exists(ds.resolve("new.csv"))).isTrue();
    }

    @Test
    void renameFile_sameSourceAndDest_doesNothing() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.writeString(ds.resolve("same.csv"), "data");

        assertThatCode(() -> fileService.renameFile(FileCategory.DATASETS, "same.csv", "same.csv"))
                .doesNotThrowAnyException();

        assertThat(Files.exists(ds.resolve("same.csv"))).isTrue();
    }

    @Test
    void renameFile_destinationAlreadyExists_throws() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.writeString(ds.resolve("src.csv"), "data");
        Files.writeString(ds.resolve("dst.csv"), "existing");

        assertThatThrownBy(() -> fileService.renameFile(FileCategory.DATASETS, "src.csv", "dst.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Destination already exists");
    }

    @Test
    void renameFile_emptyDestinationName_throws() throws IOException {
        Path ds = tempBase.resolve("datasets");
        Files.createDirectories(ds);
        Files.writeString(ds.resolve("src.csv"), "data");

        assertThatThrownBy(() -> fileService.renameFile(FileCategory.DATASETS, "src.csv", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
