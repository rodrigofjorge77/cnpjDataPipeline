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
import java.util.zip.ZipInputStream;

@Service
public class DownloaderService {

    private final PipelineConfig config;
    private final HttpClient httpClient;

    public DownloaderService(PipelineConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getRetryDelaySeconds() * 10L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<String> getAvailableDirectories() throws Exception {
        String propfindUrl = config.getBaseUrl() + "/download?path=/&files=";
        String webdavUrl = buildWebDavUrl("/");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webdavUrl))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header("Depth", "1")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 207) {
            throw new RuntimeException("PROPFIND failed: HTTP " + response.statusCode());
        }

        return parseDirectoriesFromWebDav(response.body());
    }

    public String getLatestDirectory() throws Exception {
        List<String> dirs = getAvailableDirectories();
        return dirs.stream()
                .filter(d -> d.matches("\\d{4}-\\d{2}"))
                .sorted(Comparator.reverseOrder())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No directories found"));
    }

    public List<String> getDirectoryFiles(String month) throws Exception {
        String webdavUrl = buildWebDavUrl("/" + month + "/");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webdavUrl))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header("Depth", "1")
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 207) {
            throw new RuntimeException("PROPFIND failed for " + month + ": HTTP " + response.statusCode());
        }

        return parseFilesFromWebDav(response.body());
    }

    public List<Path> downloadFile(String month, String zipFilename) throws Exception {
        Path tempDir = Path.of(config.getTempDir(), month);
        Files.createDirectories(tempDir);

        Path zipPath = tempDir.resolve(zipFilename);

        if (config.isKeepDownloadedFiles() && Files.exists(zipPath)) {
            System.out.println("[SKIP] Already downloaded: " + zipFilename);
            return extractZip(zipPath, tempDir);
        }

        String downloadUrl = config.getBaseUrl() + "/download?path=/" + month + "/&files=" + zipFilename;

        for (int attempt = 1; attempt <= config.getRetryAttempts(); attempt++) {
            try {
                System.out.printf("[DOWNLOAD] %s (attempt %d/%d)%n", zipFilename, attempt, config.getRetryAttempts());
                downloadToFile(downloadUrl, zipPath);
                return extractZip(zipPath, tempDir);
            } catch (Exception e) {
                if (attempt == config.getRetryAttempts()) throw e;
                System.err.println("[RETRY] " + e.getMessage());
                Thread.sleep(config.getRetryDelaySeconds() * 1000L * attempt);
            }
        }
        throw new RuntimeException("Download failed after retries: " + zipFilename);
    }

    private void downloadToFile(String url, Path dest) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
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
                    Path outPath = destDir.resolve(entry.getName()).normalize();
                    if (!outPath.startsWith(destDir)) {
                        throw new SecurityException("Zip slip detected: " + entry.getName());
                    }
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                    extracted.add(outPath);
                    System.out.println("[EXTRACT] " + outPath.getFileName());
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

    public void cleanup(String month) {
        if (config.isKeepDownloadedFiles()) return;
        Path tempDir = Path.of(config.getTempDir(), month);
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

    private String buildWebDavUrl(String path) {
        return config.getBaseUrl().replace("/index.php/s/", "/index.php/s/") + "/webdav" + path;
    }

    private List<String> parseDirectoriesFromWebDav(InputStream body) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(body);
        NodeList hrefs = doc.getElementsByTagNameNS("DAV:", "href");
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();
            String[] parts = href.split("/");
            if (parts.length > 0) {
                String last = parts[parts.length - 1];
                if (last.matches("\\d{4}-\\d{2}")) dirs.add(last);
            }
        }
        return dirs;
    }

    private List<String> parseFilesFromWebDav(InputStream body) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(body);
        NodeList hrefs = doc.getElementsByTagNameNS("DAV:", "href");
        List<String> files = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();
            String[] parts = href.split("/");
            if (parts.length > 0) {
                String last = parts[parts.length - 1];
                if (last.endsWith(".zip") || last.endsWith(".ZIP")) files.add(last);
            }
        }
        return files;
    }
}
