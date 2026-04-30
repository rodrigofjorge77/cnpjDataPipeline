package org.projeto.cnpjdatapipeline.service;

import org.projeto.cnpjdatapipeline.config.PipelineConfig;
import org.projeto.cnpjdatapipeline.model.CsvBatch;
import org.projeto.cnpjdatapipeline.model.FileType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DatabaseService {

    private final JdbcTemplate jdbc;
    private final PipelineConfig config;
    private final Set<String> truncatedTables = ConcurrentHashMap.newKeySet();

    public DatabaseService(JdbcTemplate jdbc, PipelineConfig config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public void ensureSchema() throws Exception {
        System.out.println("[DB] Ensuring schema...");
        ClassPathResource resource = new ClassPathResource("initial.sql");
        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
        System.out.println("[DB] Schema ready.");
    }

    public void loadBatch(CsvBatch batch) throws Exception {
        if (config.isUpsertStrategy()) {
            upsertBatch(batch);
        } else {
            replaceBatch(batch);
        }
    }

    private void upsertBatch(CsvBatch batch) throws Exception {
        FileType ft = batch.fileType();
        String table = ft.getTableName();
        String[] columns = ft.getColumns();
        String tempTable = "temp_" + table + "_" + Thread.currentThread().getId();

        jdbc.execute("DROP TABLE IF EXISTS " + tempTable);
        jdbc.execute("CREATE TEMP TABLE " + tempTable + " (LIKE " + table + " INCLUDING DEFAULTS)");

        copyToTable(tempTable, columns, batch.rows());

        String[] pks = getPrimaryKeys(table);
        String pkList = String.join(", ", pks);
        String setClauses = Arrays.stream(columns)
                .filter(c -> !isPrimaryKey(c, pks))
                .map(c -> c + " = EXCLUDED." + c)
                .collect(Collectors.joining(", "));

        String insertSql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") " +
                "SELECT DISTINCT ON (" + pkList + ") " + String.join(", ", columns) + " " +
                "FROM " + tempTable + " " +
                "ON CONFLICT (" + pkList + ") DO UPDATE SET " + setClauses +
                ", data_atualizacao = NOW()";

        jdbc.execute(insertSql);
        jdbc.execute("DROP TABLE IF EXISTS " + tempTable);

        System.out.printf("[DB] Upserted %d rows into %s%n", batch.size(), table);
    }

    private void replaceBatch(CsvBatch batch) throws Exception {
        String table = batch.fileType().getTableName();
        String[] columns = batch.fileType().getColumns();

        if (truncatedTables.add(table)) {
            jdbc.execute("TRUNCATE TABLE " + table);
            System.out.println("[DB] Truncated " + table);
        }

        copyToTable(table, columns, batch.rows());
        System.out.printf("[DB] Inserted %d rows into %s%n", batch.size(), table);
    }

    private void copyToTable(String table, String[] columns, List<String[]> rows) throws Exception {
        String colList = String.join(", ", columns);
        String copySql = "COPY " + table + " (" + colList + ") FROM STDIN WITH (FORMAT CSV, DELIMITER ';', NULL '')";

        jdbc.execute((Connection conn) -> {
            var pgConn = conn.unwrap(org.postgresql.core.BaseConnection.class);
            var copyManager = new org.postgresql.copy.CopyManager(pgConn);

            StringBuilder sb = new StringBuilder();
            for (String[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(';');
                    String val = row[i];
                    if (val != null) {
                        sb.append(val.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", ""));
                    }
                }
                sb.append('\n');
            }

            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            try {
                copyManager.copyIn(copySql, new ByteArrayInputStream(data));
            } catch (java.io.IOException e) {
                throw new SQLException("COPY failed", e);
            }
            return null;
        });
    }

    public Set<String> getProcessedFiles(String month) {
        List<String> files = jdbc.queryForList(
                "SELECT filename FROM processed_files WHERE directory = ?",
                String.class, month);
        return new HashSet<>(files);
    }

    public void markProcessed(String month, String filename) {
        jdbc.update(
                "INSERT INTO processed_files (directory, filename) VALUES (?, ?) ON CONFLICT DO NOTHING",
                month, filename);
    }

    public void clearProcessedFiles(String month) {
        jdbc.update("DELETE FROM processed_files WHERE directory = ?", month);
        System.out.println("[DB] Cleared processed files for " + month);
    }

    public void truncateTableForReplace(String tableName) {
        if (truncatedTables.add(tableName)) {
            jdbc.execute("TRUNCATE TABLE " + tableName);
        }
    }

    private String[] getPrimaryKeys(String table) {
        return switch (table) {
            case "cnaes", "motivos", "municipios", "naturezas_juridicas",
                 "paises", "qualificacoes_socios" -> new String[]{"codigo"};
            case "empresas" -> new String[]{"cnpj_basico"};
            case "dados_simples" -> new String[]{"cnpj_basico"};
            case "estabelecimentos" -> new String[]{"cnpj_basico", "cnpj_ordem", "cnpj_dv"};
            case "socios" -> new String[]{"cnpj_basico", "identificador_de_socio", "cnpj_cpf_do_socio"};
            default -> throw new IllegalArgumentException("Unknown table: " + table);
        };
    }

    private boolean isPrimaryKey(String column, String[] pks) {
        for (String pk : pks) {
            if (pk.equals(column)) return true;
        }
        return false;
    }
}
