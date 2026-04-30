package org.projeto.cnpjdatapipeline.service;

import org.projeto.cnpjdatapipeline.config.PipelineConfig;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import java.util.Base64;

@Service
public class DownloaderService {

    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4}-\\d{2})/?$");
    private static final Pattern ZIP_PATTERN = Pattern.compile("/([^/]+\\.zip)$", Pattern.CASE_INSENSITIVE);
    private static final List<String> CNPJ_FILE_PATTERNS = List.of(
            "CNAECSV", "MOTICSV", "MUNICCSV", "NATJUCSV", "PAISCSV",
            "QUALSCSV", "EMPRECSV", "ESTABELE", "SOCIOCSV", "SIMPLES"
    );

    private final PipelineConfig config;
    private final HttpClient httpClient;
    private final String basicAuth;

    public DownloaderService(PipelineConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Basic auth: username=shareToken, password="" (same as Python: auth=(token, ""))
        String credentials = config.getShareToken() + ":";
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    public List<String> getAvailableDirectories() throws Exception {
        Document doc = propfind("");
        NodeList hrefs = doc.getElementsByTagNameNS("DAV:", "href");
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();
            Matcher m = MONTH_PATTERN.matcher(href);
            if (m.find()) dirs.add(m.group(1));
        }
        if (dirs.isEmpty()) throw new RuntimeException("No data directories found");
        Collections.sort(dirs);
        return dirs;
    }

    public String getLatestDirectory() throws Exception {
        List<String> dirs = getAvailableDirectories();
        return dirs.get(dirs.size() - 1);
    }

    public List<String> getDirectoryFiles(String month) throws Exception {
        Document doc = propfind(month);
        NodeList hrefs = doc.getElementsByTagNameNS("DAV:", "href");
        List<String> files = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();
            Matcher m = ZIP_PATTERN.matcher(href);
            if (m.find()) files.add(m.group(1));
        }
        return files;
    }

    public List<Path> downloadFile(String month, String zipFilename) throws Exception {
        Path tempDir = Path.of(config.getTempDir());
        Files.createDirectories(tempDir);

        Path zipPath = tempDir.resolve(zipFilename);

        if (config.isKeepDownloadedFiles() && Files.exists(zipPath)) {
            System.out.println("[SKIP] Already downloaded: " + zipFilename);
            return extractZip(zipPath, tempDir);
        }

        // Download URL: base_url/month/filename  (same as Python)
        String url = config.getBaseUrl() + "/" + month + "/" + zipFilename;

        for (int attempt = 1; attempt <= config.getRetryAttempts(); attempt++) {
            try {
                System.out.printf("[DOWNLOAD] %s (attempt %d/%d)%n",
                        zipFilename, attempt, config.getRetryAttempts());
                downloadToFile(url, zipPath);
                return extractZip(zipPath, tempDir);
            } catch (Exception e) {
                if (attempt == config.getRetryAttempts()) throw e;
                System.err.println("[RETRY] " + e.getMessage());
                Thread.sleep(config.getRetryDelaySeconds() * 1000L * attempt);
            }
        }
        throw new RuntimeException("Download failed after retries: " + zipFilename);
    }

    private Document propfind(String path) throws Exception {
        // URL: base_url/path/ (trailing slash required)
        String url = (config.getBaseUrl() + "/" + path).replaceAll("/+$", "") + "/";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header("Authorization", basicAuth)
                .header("Depth", "1")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 207) {
            throw new RuntimeException("PROPFIND failed: HTTP " + response.statusCode() + " for " + url);
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(response.body());
    }

    private void downloadToFile(String url, Path dest) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Authorization", basicAuth)
                .timeout(Duration.ofSeconds(600))
                .build();

        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(dest));

        if (response.statusCode() != 200) {
            throw new RuntimeException("Download failed: HTTP " + response.statusCode() + " for " + url);
        }
    }

    private List<Path> extractZip(Path zipPath, Path destDir) throws Exception {
        List<Path> extracted = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()) {
                    String nameUpper = entry.getName().toUpperCase();
                    boolean isCnpjFile = CNPJ_FILE_PATTERNS.stream()
                            .anyMatch(nameUpper::contains);

                    if (isCnpjFile) {
                        Path outPath = destDir.resolve(entry.getName()).normalize();
                        if (!outPath.startsWith(destDir)) {
                            throw new SecurityException("Zip slip detected: " + entry.getName());
                        }
                        Files.createDirectories(outPath.getParent());
                        Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                        extracted.add(outPath);
                        System.out.println("[EXTRACT] " + outPath.getFileName());
                    }
                }
                zis.closeEntry();
                entry = zis.getNextEntry();
            }
        }

        if (!config.isKeepDownloadedFiles()) {
            Files.deleteIfExists(zipPath);
        }

        return extracted;
    }

    public void cleanup() {
        if (config.isKeepDownloadedFiles()) return;
        Path tempDir = Path.of(config.getTempDir());
        deleteDirectory(tempDir);
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            System.err.println("[WARN] Could not delete temp dir: " + e.getMessage());
        }
    }
}
