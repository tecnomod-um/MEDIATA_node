package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class DataCleaningOptionsDTO {
    // Basic cleaning
    private boolean removeDuplicates;
    private boolean removeEmptyRows;
    private boolean trimWhitespace;
    
    // Date operations
    private boolean standardizeDates;
    private String dateOutputFormat;
    private boolean extractDateComponents; // Extract year, month, day into separate columns
    
    // Numeric operations
    private boolean standardizeNumeric;
    private List<String> numericColumns;
    private String numericMode; // "double", "int_round", "int_trunc"
    private boolean removeLeadingZeros;
    private boolean roundDecimals;
    private int decimalPlaces;
    
    // Text operations
    private boolean standardizeCase;
    private String caseMode; // "upper", "lower", "title", "sentence"
    private boolean normalizeText;
    private boolean removeSpecialCharacters;
    private String specialCharColumnsPattern;
    private boolean removePunctuation;
    private boolean removeExtraSpaces;
    private boolean removeLineBreaks;
    
    // Missing value handling
    private boolean fillMissingValues;
    private String fillStrategy; // "mean", "median", "mode", "constant", "forward", "backward", "interpolate"
    private String fillConstantValue;
    private List<String> fillColumns; // Specific columns to apply fill strategy
    
    // String manipulation
    private boolean replaceValues;
    private Map<String, String> replacementMap; // old_value -> new_value
    private boolean stripPrefix;
    private String prefixToStrip;
    private boolean stripSuffix;
    private String suffixToStrip;
    private boolean padValues;
    private String padDirection; // "left", "right"
    private int padLength;
    private String padCharacter;
    
    // Data type conversion
    private boolean convertDataTypes;
    private Map<String, String> typeConversionMap; // column -> type ("string", "integer", "float", "boolean")
    
    // Email and URL handling
    private boolean extractEmailDomain;
    private boolean validateEmails;
    private boolean extractURLComponents; // protocol, domain, path, query
    private boolean normalizeURLs;
    
    // Phone number standardization
    private boolean standardizePhoneNumbers;
    private String phoneFormat; // "international", "national", "e164"
    private String defaultCountryCode;
    
    // Encoding and character issues
    private boolean fixEncoding;
    private boolean removeNonPrintableChars;
    private boolean normalizeUnicode; // NFC, NFD, NFKC, NFKD
    private String unicodeNormalization;
    
    // Column operations
    private boolean splitColumn;
    private String columnToSplit;
    private String splitDelimiter;
    private List<String> newColumnNames;
    private boolean mergeColumns;
    private List<String> columnsToMerge;
    private String mergeDelimiter;
    private String mergedColumnName;
    
    // Data validation and filtering
    private boolean removeRowsWithPattern;
    private String rowFilterPattern; // regex pattern
    private String rowFilterColumn;
    private boolean keepOnlyNumericRows;
    private List<String> numericValidationColumns;
    
    // Statistical transformations
    private boolean normalizeData; // Min-max normalization
    private List<String> normalizeColumns;
    private boolean standardizeData; // Z-score standardization
    private List<String> standardizeColumns;
    private boolean binData; // Create bins/categories
    private String binColumn;
    private List<Double> binEdges;
    private List<String> binLabels;
    
    // Fuzzy matching and value merging
    private boolean mergeSimilarValues;
    private List<String> fuzzyMatchColumns; // Columns to apply fuzzy matching
    private String mergeSimilarityAlgorithm; // "levenshtein", "jaro_winkler", "cosine"
    private double mergeSimilarityThreshold; // 0.0-1.0, default 0.85 (85% similar)
    private boolean mergeCaseInsensitive; // Ignore case when comparing
    private boolean mergeTrimValues; // Trim whitespace before comparing
    private String mergePreferredValue; // "most_frequent", "shortest", "longest", "first", "alphabetical"
}
