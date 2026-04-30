package org.projeto.cnpjdatapipeline.service;

import org.projeto.cnpjdatapipeline.config.PipelineConfig;
import org.projeto.cnpjdatapipeline.model.CsvBatch;
import org.projeto.cnpjdatapipeline.model.FileType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class PipelineRunner implements ApplicationRunner {

    private final PipelineConfig config;
    private final DownloaderService downloader;
    private final ProcessorService processor;
    private final DatabaseService database;

    public PipelineRunner(PipelineConfig config,
                          DownloaderService downloader,
                          ProcessorService processor,
                          DatabaseService database) {
        this.config = config;
        this.downloader = downloader;
        this.processor = processor;
        this.database = database;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean listMode = args.containsOption("list");
        boolean forceMode = args.containsOption("force");
        String month = args.containsOption("month")
                ? args.getOptionValues("month").get(0)
                : null;

        if (listMode) {
            listAvailableMonths();
            return;
        }

        if (!config.isParquetOutput()) {
            database.ensureSchema();
        }

        String targetMonth = (month != null) ? month : downloader.getLatestDirectory();
        System.out.println("[PIPELINE] Processing month: " + targetMonth);

        if (forceMode && !config.isParquetOutput()) {
            database.clearProcessedFiles(targetMonth);
        }

        Set<String> processedFiles = config.isParquetOutput()
                ? Collections.emptySet()
                : database.getProcessedFiles(targetMonth);

        List<String> allFiles = downloader.getDirectoryFiles(targetMonth);
        List<String> pending = allFiles.stream()
                .filter(f -> !processedFiles.contains(f))
                .collect(Collectors.toList());

        System.out.printf("[PIPELINE] %d files to process (%d already done)%n",
                pending.size(), processedFiles.size());

        // Pre-truncate for replace mode (thread safety)
        if (!config.isUpsertStrategy() && !config.isParquetOutput()) {
            pending.stream()
                    .map(this::resolveFileType)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(ft -> ft.getTableName())
                    .distinct()
                    .forEach(database::truncateTableForReplace);
        }

        // Process by dependency group (must be sequential across groups)
        for (int group = 1; group <= 3; group++) {
            final int g = group;
            List<String> groupFiles = pending.stream()
                    .filter(f -> resolveFileType(f).map(ft -> ft.getDependencyGroup() == g).orElse(false))
                    .collect(Collectors.toList());

            if (groupFiles.isEmpty()) continue;

            System.out.printf("[PIPELINE] Processing dependency group %d (%d files)%n", g, groupFiles.size());
            processGroup(groupFiles, targetMonth);
        }

        downloader.cleanup();
        System.out.println("[PIPELINE] Done!");
    }

    private void processGroup(List<String> zipFiles, String month) throws Exception {
        int workers = Math.max(1, config.getProcessWorkers());
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();

        for (String zipFile : zipFiles) {
            futures.add(executor.submit(() -> {
                try {
                    processZipFile(zipFile, month);
                } catch (Exception e) {
                    throw new RuntimeException("Failed processing " + zipFile, e);
                }
            }));
        }

        executor.shutdown();
        Exception firstFailure = null;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                System.err.println("[ERROR] " + cause.getMessage());
                if (cause.getCause() != null) {
                    System.err.println("[ERROR] Caused by: " + cause.getCause().getMessage());
                    cause.getCause().printStackTrace(System.err);
                } else {
                    cause.printStackTrace(System.err);
                }
                if (firstFailure == null) firstFailure = (Exception) cause;
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private void processZipFile(String zipFilename, String month) throws Exception {
        Optional<FileType> ftOpt = resolveFileType(zipFilename);
        if (ftOpt.isEmpty()) {
            System.out.println("[SKIP] Unknown file type: " + zipFilename);
            return;
        }
        FileType fileType = ftOpt.get();

        List<Path> csvFiles = downloader.downloadFile(month, zipFilename);

        for (Path csvPath : csvFiles) {
            processor.processCsvFile(csvPath, fileType, batch -> {
                try {
                    loadBatch(batch);
                } catch (Exception e) {
                    throw new RuntimeException("Load failed for " + csvPath, e);
                }
            });
        }

        if (!config.isParquetOutput()) {
            database.markProcessed(month, zipFilename);
        }
    }

    private void loadBatch(CsvBatch batch) throws Exception {
        if (!config.isParquetOutput()) {
            database.loadBatch(batch);
        } else {
            System.out.printf("[PARQUET] Batch %d rows → %s (not implemented yet)%n",
                    batch.size(), batch.fileType().getTableName());
        }
    }

    private void listAvailableMonths() throws Exception {
        List<String> dirs = downloader.getAvailableDirectories();
        System.out.println("Available months:");
        dirs.stream().sorted(Comparator.reverseOrder()).forEach(d -> System.out.println("  " + d));
    }

    private Optional<FileType> resolveFileType(String filename) {
        String upper = filename.toUpperCase();
        return Arrays.stream(FileType.values())
                .filter(ft -> upper.contains(ft.name()))
                .findFirst();
    }
}
