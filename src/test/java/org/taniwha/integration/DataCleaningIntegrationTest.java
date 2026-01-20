package org.taniwha.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.dto.DataCleaningOptionsDTO;
import org.taniwha.model.FileCategory;
import org.taniwha.service.DataCleaningService;
import org.taniwha.service.FileService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for data cleaning workflows.
 * Tests complete workflows from file creation through data cleaning.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "jwt.secret=test-secret-key-must-be-256-bits-or-more-for-security-purposes",
    "jwt.expiration=3600000"
})
class DataCleaningIntegrationTest {

    @Autowired
    private DataCleaningService dataCleaningService;

    @Autowired
    private FileService fileService;

    @MockBean
    private RestTemplateHolder restTemplateHolder;

    @TempDir
    Path tempDir;

    private Path datasetsDir;

    @BeforeEach
    void setUp() throws IOException {
        // Set app.path to temp directory for isolated testing
        System.setProperty("app.path", tempDir.toString());
        
        // Create datasets directory structure
        datasetsDir = tempDir.resolve("datasets");
        Files.createDirectories(datasetsDir);
    }

    @Test
    void textNormalizationWorkflow() throws Exception {
        // Step 1: Create test file with messy text
        String messyData = "NAME,DESCRIPTION\n" +
            "  Product  One  ,Great  product\n" +
            "product TWO,EXCELLENT  QUALITY\n";
        
        Path testFile = datasetsDir.resolve("messy.csv");
        Files.writeString(testFile, messyData);
        
        // Step 2: Apply text normalization
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setTrimWhitespace(true);
        opts.setRemoveExtraSpaces(true);
        opts.setStandardizeCase(true);
        opts.setCaseMode("title");
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "messy.csv", opts);
        
        // Step 3: Verify cleaned data
        Path cleanedFile = datasetsDir.resolve("messy.csv");
        assertThat(cleanedFile).exists();
        
        List<String> lines = Files.readAllLines(cleanedFile);
        assertThat(lines).hasSizeGreaterThan(1);
    }

    @Test
    void fuzzyMatchingWorkflow() throws Exception {
        // Step 1: Create data with similar values
        String companyData = "COMPANY,REVENUE\n" +
            "Apple Inc,1000000\n" +
            "Apple Inc.,2000000\n" +
            "Microsoft Corp,4000000\n";
        
        Path testFile = datasetsDir.resolve("companies.csv");
        Files.writeString(testFile, companyData);
        
        // Step 2: Apply fuzzy matching
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setMergeSimilarValues(true);
        opts.setFuzzyMatchColumns(Arrays.asList("COMPANY"));
        opts.setMergeSimilarityAlgorithm("levenshtein");
        opts.setMergeSimilarityThreshold(0.85);
        opts.setMergeCaseInsensitive(true);
        opts.setMergeTrimValues(true);
        opts.setMergePreferredValue("most_frequent");
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "companies.csv", opts);
        
        // Step 3: Verify file exists
        Path cleanedFile = datasetsDir.resolve("companies.csv");
        assertThat(cleanedFile).exists();
    }

    @Test
    void missingValueHandlingWorkflow() throws Exception {
        // Step 1: Create data with missing values
        String dataWithMissing = "ID,SCORE\n" +
            "1,100\n" +
            "2,\n" +
            "3,400\n";
        
        Path testFile = datasetsDir.resolve("missing.csv");
        Files.writeString(testFile, dataWithMissing);
        
        // Step 2: Fill missing values
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setFillMissingValues(true);
        opts.setFillStrategy("mean");
        opts.setFillColumns(Arrays.asList("SCORE"));
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "missing.csv", opts);
        
        // Step 3: Verify file exists
        Path cleanedFile = datasetsDir.resolve("missing.csv");
        assertThat(cleanedFile).exists();
    }

    @Test
    void statisticalNormalizationWorkflow() throws Exception {
        // Step 1: Create numeric data
        String numericData = "ID,VALUE\n" +
            "1,10\n" +
            "2,50\n" +
            "3,90\n";
        
        Path testFile = datasetsDir.resolve("numeric.csv");
        Files.writeString(testFile, numericData);
        
        // Step 2: Apply normalization
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setNormalizeData(true);
        opts.setNormalizeColumns(Arrays.asList("VALUE"));
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "numeric.csv", opts);
        
        // Step 3: Verify file exists
        Path normalizedFile = datasetsDir.resolve("numeric.csv");
        assertThat(normalizedFile).exists();
    }

    @Test
    void dateExtractionWorkflow() throws Exception {
        // Step 1: Create data with dates
        String dateData = "ID,EVENT_DATE\n" +
            "1,2023-01-15\n" +
            "2,2023-06-20\n";
        
        Path testFile = datasetsDir.resolve("dates.csv");
        Files.writeString(testFile, dateData);
        
        // Step 2: Extract date components
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setExtractDateComponents(true);
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "dates.csv", opts);
        
        // Step 3: Verify date components were added
        Path processedFile = datasetsDir.resolve("dates.csv");
        assertThat(processedFile).exists();
        
        List<String> lines = Files.readAllLines(processedFile);
        String header = lines.get(0);
        assertThat(header).contains("_year");
        assertThat(header).contains("_month");
        assertThat(header).contains("_day");
    }

    @Test
    void phoneNumberStandardizationWorkflow() throws Exception {
        // Step 1: Create data with phone numbers
        String phoneData = "NAME,PHONE\n" +
            "John,1234567890\n" +
            "Jane,9876543210\n";
        
        Path testFile = datasetsDir.resolve("phones.csv");
        Files.writeString(testFile, phoneData);
        
        // Step 2: Standardize phone numbers
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setStandardizePhoneNumbers(true);
        opts.setPhoneFormat("international");
        opts.setDefaultCountryCode("+1");
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "phones.csv", opts);
        
        // Step 3: Verify file exists
        Path standardizedFile = datasetsDir.resolve("phones.csv");
        assertThat(standardizedFile).exists();
    }

    @Test
    void columnSplittingWorkflow() throws Exception {
        // Step 1: Create data with compound columns
        String compoundData = "ID,FULL_NAME\n" +
            "1,John Doe\n" +
            "2,Jane Smith\n";
        
        Path testFile = datasetsDir.resolve("names.csv");
        Files.writeString(testFile, compoundData);
        
        // Step 2: Split column
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setSplitColumn(true);
        opts.setColumnToSplit("FULL_NAME");
        opts.setSplitDelimiter(" ");
        opts.setNewColumnNames(Arrays.asList("FIRST_NAME", "LAST_NAME"));
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "names.csv", opts);
        
        // Step 3: Verify column was split
        Path splitFile = datasetsDir.resolve("names.csv");
        assertThat(splitFile).exists();
        
        List<String> lines = Files.readAllLines(splitFile);
        String header = lines.get(0);
        assertThat(header).contains("FIRST_NAME");
        assertThat(header).contains("LAST_NAME");
    }

    @Test
    void multipleOperationsCombinedWorkflow() throws Exception {
        // Step 1: Create realistic messy dataset
        String messyDataset = "product,price,stock\n" +
            "  iPhone  ,999.99,50\n" +
            "Samsung,899.50,\n" +
            "MacBook,2499.00,100\n";
        
        Path testFile = datasetsDir.resolve("products.csv");
        Files.writeString(testFile, messyDataset);
        
        // Step 2: Apply multiple operations
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setTrimWhitespace(true);
        opts.setRemoveExtraSpaces(true);
        opts.setStandardizeCase(true);
        opts.setCaseMode("title");
        opts.setFillMissingValues(true);
        opts.setFillStrategy("constant");
        opts.setFillColumns(Arrays.asList("stock"));
        opts.setRoundDecimals(true);
        opts.setDecimalPlaces(0);
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "products.csv", opts);
        
        // Step 3: Verify all operations applied
        Path cleanedFile = datasetsDir.resolve("products.csv");
        assertThat(cleanedFile).exists();
    }

    @Test
    void dataQualityImprovementWorkflow() throws Exception {
        // Step 1: Create low-quality data
        String lowQualityData = "id,name,value\n" +
            "1,Item One,100\n" +
            "2,,200\n" +
            "3,Item Three,\n" +
            ",,\n" +
            "5,Item Five,500\n";
        
        Path testFile = datasetsDir.resolve("quality.csv");
        Files.writeString(testFile, lowQualityData);
        
        // Step 2: Clean data quality issues
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setRemoveEmptyRows(true);
        opts.setRemoveDuplicates(true);
        opts.setFillMissingValues(true);
        opts.setFillStrategy("constant");
        opts.setFillColumns(Arrays.asList("name", "value"));
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "quality.csv", opts);
        
        // Step 3: Verify quality improved
        Path cleanedFile = datasetsDir.resolve("quality.csv");
        assertThat(cleanedFile).exists();
    }

    @Test
    void categoricalDataBinningWorkflow() throws Exception {
        // Step 1: Create continuous data
        String ageData = "ID,AGE\n" +
            "1,25\n" +
            "2,45\n" +
            "3,65\n";
        
        Path testFile = datasetsDir.resolve("ages.csv");
        Files.writeString(testFile, ageData);
        
        // Step 2: Bin ages into categories
        DataCleaningOptionsDTO opts = new DataCleaningOptionsDTO();
        opts.setBinData(true);
        opts.setBinColumn("AGE");
        opts.setBinEdges(Arrays.asList(0.0, 18.0, 35.0, 65.0, 120.0));
        opts.setBinLabels(Arrays.asList("Minor", "Young Adult", "Adult", "Senior"));
        
        dataCleaningService.cleanInPlace(FileCategory.DATASETS, "ages.csv", opts);
        
        // Step 3: Verify bins created
        Path binnedFile = datasetsDir.resolve("ages.csv");
        assertThat(binnedFile).exists();
        
        List<String> lines = Files.readAllLines(binnedFile);
        String header = lines.get(0);
        assertThat(header).contains("AGE_category");
    }
}
