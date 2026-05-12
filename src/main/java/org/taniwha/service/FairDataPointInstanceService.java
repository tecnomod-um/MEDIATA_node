package org.taniwha.service;

import org.apache.jena.rdf.model.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import org.taniwha.config.RestTemplateHolder;
import org.taniwha.dto.FairDataPointAccessResponseDTO;
import org.taniwha.model.FairDataPointPublishedManifest;
import org.taniwha.util.DCatUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class FairDataPointInstanceService {

    private static final MediaType TURTLE = MediaType.valueOf("text/turtle");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final String FDP_O = "https://w3id.org/fdp/fdp-o#";

    private final RestTemplateHolder restTemplateHolder;
    private final FileService fileService;
    private final boolean enabled;
    private final String baseUrl;
    private final String persistentUrl;

    public FairDataPointInstanceService(RestTemplateHolder restTemplateHolder,
                                        FileService fileService,
                                        @Value("${fairdatapoint.enabled:false}") boolean enabled,
                                        @Value("${fairdatapoint.base-url:}") String baseUrl,
                                        @Value("${fairdatapoint.persistent-url:}") String persistentUrl) {
        this.restTemplateHolder = restTemplateHolder;
        this.fileService = fileService;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.persistentUrl = persistentUrl == null ? "" : persistentUrl.trim();
    }

    public FetchResult fetchMetadata(String localFdpPath, String acceptHeader, String localFacadeBaseUrl) {
        if (!isConfigured()) {
            return new FetchResult(null, FetchStatus.DISABLED);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(resolveAcceptHeader(acceptHeader));

        try {
            ResponseEntity<String> upstream = restTemplateHolder.get().exchange(
                    resolveUpstreamUrl(localFdpPath),
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );

            MediaType contentType = upstream.getHeaders().getContentType();
            String body = upstream.getBody();
            String adaptedBody = body == null ? "" : adaptUpstreamMetadata(localFdpPath, body, normalizeBaseUrl(localFacadeBaseUrl));

            return new FetchResult(
                    ResponseEntity.status(upstream.getStatusCode())
                            .contentType(resolveResponseContentType(contentType, adaptedBody))
                            .body(adaptedBody),
                    FetchStatus.OK
            );
        } catch (HttpClientErrorException.NotFound e) {
            return new FetchResult(null, FetchStatus.NOT_FOUND);
        } catch (HttpStatusCodeException e) {
            MediaType contentType = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType();
            String body = e.getResponseBodyAsString();
            String adaptedBody = adaptUpstreamMetadata(localFdpPath, body == null ? "" : body, normalizeBaseUrl(localFacadeBaseUrl));
            return new FetchResult(
                    ResponseEntity.status(e.getStatusCode())
                            .contentType(resolveErrorResponseContentType(contentType, adaptedBody))
                            .body(adaptedBody),
                    FetchStatus.OK
            );
        } catch (RestClientException e) {
            return new FetchResult(null, FetchStatus.UNAVAILABLE);
        }
    }

    public FairDataPointAccessResponseDTO buildAccessResponse(String publicBaseUrl, String distributionId) {
        DatasetEntry entry = findDistributionEntry(distributionId);
        if (entry == null) {
            return null;
        }

        String baseUrl = normalizeBaseUrl(publicBaseUrl);
        String applicationBaseUrl = baseUrl.endsWith("/fdp")
                ? baseUrl.substring(0, baseUrl.length() - 4)
                : baseUrl;
        boolean downloadable = fileService.isDatasetDownloadAllowed(entry.fileName());
        String message = downloadable
                ? "Access requires the node authorization flow. First submit the Kerberos service ticket to /node/authorize, then validate the user JWT and Kerberos token at /node/validate. Use the returned node JWT to call the authenticated dataset file endpoint."
                : fileService.datasetDownloadBlockedMessage(entry.fileName()) + " The file has been set to not leave the server.";
        return new FairDataPointAccessResponseDTO(
                entry.distributionId(),
                entry.datasetId(),
                entry.fileName(),
                message,
                downloadable ? applicationBaseUrl + "/node/authorize" : null,
                downloadable ? applicationBaseUrl + "/node/validate" : null,
                downloadable ? applicationBaseUrl + "/api/files/datasets/" + UriUtils.encodePathSegment(entry.fileName(), StandardCharsets.UTF_8) : null
        );
    }

    private boolean isConfigured() {
        return enabled && !isBlank(baseUrl);
    }

    private String resolveUpstreamUrl(String localFdpPath) {
        String path = localFdpPath == null || localFdpPath.isBlank() ? "/" : localFdpPath;
        return normalizeBaseUrl(baseUrl) + mapLocalPathToInstancePath(path);
    }

    private List<MediaType> resolveAcceptHeader(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return List.of(TURTLE);
        }

        try {
            List<MediaType> mediaTypes = new ArrayList<>(MediaType.parseMediaTypes(acceptHeader));
            MimeTypeUtils.sortBySpecificity(mediaTypes);

            boolean onlyWildcards = mediaTypes.stream()
                    .allMatch(mediaType -> mediaType.isWildcardType() && mediaType.isWildcardSubtype());
            return onlyWildcards ? List.of(TURTLE) : mediaTypes;
        } catch (IllegalArgumentException e) {
            return List.of(TURTLE);
        }
    }

    private String rewriteInstanceUris(String body, String localFacadeBaseUrl) {
        String rewritten = body;
        for (String instanceBase : instanceUriBases()) {
            rewritten = rewritten.replace(instanceBase, localFacadeBaseUrl);
        }
        rewritten = rewritten.replaceAll(
                java.util.regex.Pattern.quote(localFacadeBaseUrl) + "/metadata-schemas/[0-9a-fA-F\\-]+",
                localFacadeBaseUrl + "/spec"
        );
        return rewritten;
    }

    private String adaptUpstreamMetadata(String localFdpPath, String body, String localFacadeBaseUrl) {
        String rewritten = rewriteInstanceUris(body, localFacadeBaseUrl);
        String path = normalizeLocalPath(localFdpPath);
        if (!"/".equals(path) || rewritten.isBlank()) {
            return rewritten;
        }

        try {
            var rootModel = DCatUtil.readModel(rewritten, "root.ttl");
            FairDataPointPublishedManifest manifest = fileService.readFairDataPointPublishedManifest();
            if (manifest == null || manifest.catalogUris() == null || manifest.catalogUris().isEmpty()) {
                return rewritten;
            }

            Resource root = rootModel.createResource(localFacadeBaseUrl);
            for (String catalogUri : manifest.catalogUris()) {
                String localCatalogUri = rewriteSingleInstanceUri(catalogUri, localFacadeBaseUrl);
                root.addProperty(rootModel.createProperty(FDP_O + "metadataCatalog"),
                        rootModel.createResource(localCatalogUri));
            }

            java.io.StringWriter writer = new java.io.StringWriter();
            rootModel.write(writer, "TTL");
            return writer.toString();
        } catch (Exception ignored) {
            return rewritten;
        }
    }

    private MediaType resolveResponseContentType(MediaType upstreamContentType, String body) {
        if (upstreamContentType == null) {
            return TURTLE;
        }
        if (looksLikeTurtle(body) && isXmlish(upstreamContentType)) {
            return TURTLE;
        }
        return upstreamContentType;
    }

    private MediaType resolveErrorResponseContentType(MediaType upstreamContentType, String body) {
        if (upstreamContentType == null) {
            return MediaType.TEXT_PLAIN;
        }
        if (looksLikeTurtle(body) && isXmlish(upstreamContentType)) {
            return TURTLE;
        }
        return upstreamContentType;
    }

    private boolean isXmlish(MediaType mediaType) {
        String type = mediaType.getType();
        String subtype = mediaType.getSubtype();
        return "application".equalsIgnoreCase(type) && "xml".equalsIgnoreCase(subtype)
                || "text".equalsIgnoreCase(type) && "xml".equalsIgnoreCase(subtype);
    }

    private boolean looksLikeTurtle(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.stripLeading();
        return trimmed.startsWith("@prefix")
                || trimmed.startsWith("@base")
                || trimmed.startsWith("<")
                || trimmed.startsWith("PREFIX ")
                || trimmed.startsWith("prefix ");
    }

    private String rewriteSingleInstanceUri(String uri, String localFacadeBaseUrl) {
        String rewritten = uri;
        for (String instanceBase : instanceUriBases()) {
            if (rewritten.startsWith(instanceBase)) {
                rewritten = localFacadeBaseUrl + rewritten.substring(instanceBase.length());
            }
        }
        return rewritten;
    }

    private String mapLocalPathToInstancePath(String localFdpPath) {
        if (localFdpPath.matches("^/metadata-schemas/[0-9a-fA-F\\-]+/?$")) {
            return "/spec";
        }
        return localFdpPath;
    }

    private java.util.Set<String> instanceUriBases() {
        java.util.Set<String> bases = new java.util.LinkedHashSet<>();
        if (!isBlank(persistentUrl)) {
            bases.add(normalizeBaseUrl(persistentUrl));
        }
        if (!isBlank(baseUrl)) {
            bases.add(normalizeBaseUrl(baseUrl));
        }
        return bases;
    }

    private String normalizeLocalPath(String localFdpPath) {
        String path = localFdpPath == null || localFdpPath.isBlank() ? "/" : localFdpPath;
        return path.startsWith("/") ? path : "/" + path;
    }

    private DatasetEntry findDistributionEntry(String distributionId) {
        return listDatasetEntries().stream()
                .filter(entry -> entry.distributionId().equals(distributionId))
                .findFirst()
                .orElse(null);
    }

    private List<DatasetEntry> listDatasetEntries() {
        List<String> datasetFiles = new ArrayList<>(fileService.listDatasetFiles());
        datasetFiles.sort(String.CASE_INSENSITIVE_ORDER);

        List<DatasetEntry> entries = new ArrayList<>();
        for (String datasetFile : datasetFiles) {
            String stem = stripExtension(datasetFile);
            String datasetSlug = slugify(logicalDatasetStem(stem));
            String distributionSlug = slugify(stem);
            entries.add(new DatasetEntry(datasetSlug, distributionSlug, datasetFile));
        }
        return entries;
    }

    private String logicalDatasetStem(String stem) {
        String normalized = stem == null ? "" : stem.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("parsed_") && normalized.length() > 7) {
            return normalized.substring(7);
        }
        if (lower.startsWith("parsed-") && normalized.length() > 7) {
            return normalized.substring(7);
        }
        return normalized;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "dataset";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private String slugify(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = NON_SLUG.matcher(normalized).replaceAll("-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "dataset" : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DatasetEntry(String datasetId, String distributionId, String fileName) {
    }

    public enum FetchStatus {
        OK,
        NOT_FOUND,
        UNAVAILABLE,
        DISABLED
    }

    public record FetchResult(ResponseEntity<String> response, FetchStatus status) {
    }
}
