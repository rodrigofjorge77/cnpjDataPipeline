package org.projeto.cnpjdatapipeline.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.projeto.cnpjdatapipeline.config.PipelineConfig;
import org.projeto.cnpjdatapipeline.model.CsvBatch;
import org.projeto.cnpjdatapipeline.model.FileType;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class ProcessorService {

    private static final Charset SOURCE_CHARSET = Charset.forName("ISO-8859-1");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final Set<String> VALID_UF = Set.of(
            "AC","AL","AM","AP","BA","CE","DF","ES","GO","MA","MG","MS","MT",
            "PA","PB","PE","PI","PR","RJ","RN","RO","RR","RS","SC","SE","SP","TO","EX"
    );

    private static final Pattern DIGITS_8 = Pattern.compile("^\\d{8}$");
    private static final Pattern DIGITS_4 = Pattern.compile("^\\d{4}$");
    private static final Pattern DIGITS_2 = Pattern.compile("^\\d{2}$");
    private static final Pattern DIGITS_7 = Pattern.compile("^\\d{7}$");

    private final PipelineConfig config;

    public ProcessorService(PipelineConfig config) {
        this.config = config;
    }

    public void processCsvFile(Path csvPath, FileType fileType, Consumer<CsvBatch> batchConsumer) throws Exception {
        System.out.printf("[PROCESS] %s → %s%n", csvPath.getFileName(), fileType.getTableName());
        readAndBatch(csvPath, fileType, batchConsumer);
    }

    private void readAndBatch(Path csvPath, FileType fileType, Consumer<CsvBatch> consumer) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        // Read ISO-8859-1 directly — Java's InputStreamReader converts to Unicode
        // in streaming fashion with no intermediate file or full-file allocation
        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(
                             new BufferedInputStream(Files.newInputStream(csvPath), 1024 * 1024),
                             SOURCE_CHARSET));
             CSVParser parser = format.parse(reader)) {

            List<String[]> batch = new ArrayList<>(config.getBatchSize());

            for (CSVRecord record : parser) {
                String[] raw = new String[record.size()];
                for (int i = 0; i < record.size(); i++) {
                    raw[i] = record.get(i);
                }
                String[] transformed = transform(raw, fileType);
                if (transformed != null) {
                    batch.add(transformed);
                }
                if (batch.size() >= config.getBatchSize()) {
                    consumer.accept(new CsvBatch(fileType, new ArrayList<>(batch)));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                consumer.accept(new CsvBatch(fileType, batch));
            }
        }
    }

    private String[] transform(String[] row, FileType fileType) {
        String[] cols = fileType.getColumns();
        if (row.length < cols.length) {
            return null;
        }

        String[] out = Arrays.copyOf(row, cols.length);

        switch (fileType) {
            case EMPRECSV -> transformEmpresa(out);
            case ESTABELE -> transformEstabelecimento(out);
            case SOCIOCSV -> transformSocio(out);
            case SIMPLESCSV -> transformSimples(out);
            default -> { /* reference tables need no transformation */ }
        }

        validate(out, fileType);
        return out;
    }

    private void transformEmpresa(String[] row) {
        // capital_social index = 4: "1.234.567,89" → "1234567.89"
        row[4] = transformCapitalSocial(row[4]);
    }

    private void transformEstabelecimento(String[] row) {
        // data_situacao_cadastral = index 6
        row[6] = nullifyDate(row[6]);
        // data_inicio_atividade = index 10
        row[10] = nullifyDate(row[10]);
        // pais (country code) = index 9 → zero-pad to 3 digits
        row[9] = padCountryCode(row[9]);
        // data_situacao_especial = index 29
        if (row.length > 29) row[29] = nullifyDate(row[29]);
    }

    private void transformSocio(String[] row) {
        // data_entrada_sociedade = index 5
        row[5] = nullifyDate(row[5]);
        // pais = index 6
        row[6] = padCountryCode(row[6]);
        // cnpj_cpf_do_socio = index 3: null → zeros
        if (isNullOrEmpty(row[3])) {
            row[3] = "00000000000000";
        }
    }

    private void transformSimples(String[] row) {
        // data_opcao_pelo_simples = index 2
        row[2] = nullifyDate(row[2]);
        // data_exclusao_do_simples = index 3
        row[3] = nullifyDate(row[3]);
        // data_opcao_pelo_mei = index 5
        row[5] = nullifyDate(row[5]);
        // data_exclusao_do_mei = index 6
        row[6] = nullifyDate(row[6]);
    }

    private String transformCapitalSocial(String value) {
        if (isNullOrEmpty(value)) return null;
        try {
            // Remove thousand separators (dots), replace decimal comma with dot
            String normalized = value.replace(".", "").replace(",", ".");
            double d = Double.parseDouble(normalized);
            if (d < 0) return null;
            return String.valueOf(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nullifyDate(String value) {
        if (isNullOrEmpty(value) || value.equals("0") || value.equals("00000000")) return null;
        return value;
    }

    private String padCountryCode(String value) {
        if (isNullOrEmpty(value)) return value;
        try {
            int code = Integer.parseInt(value.trim());
            return String.format("%03d", code);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private boolean isNullOrEmpty(String s) {
        return s == null || s.isBlank();
    }

    private void validate(String[] row, FileType fileType) {
        switch (fileType) {
            case EMPRECSV -> {
                warnIfNotMatch(row[0], DIGITS_8, "cnpj_basico");
                warnIfNotMatch(row[2], DIGITS_4, "natureza_juridica");
                warnIfNotMatch(row[3], DIGITS_2, "qualificacao_responsavel");
                warnIfNotIn(row[5], Set.of("00","01","03","05"), "porte");
            }
            case ESTABELE -> {
                warnIfNotMatch(row[0], DIGITS_8, "cnpj_basico");
                warnIfNotMatch(row[1], DIGITS_4, "cnpj_ordem");
                warnIfNotMatch(row[2], DIGITS_2, "cnpj_dv");
                warnIfNotIn(row[5], Set.of("01","02","03","04","08"), "situacao_cadastral");
                warnIfNotMatch(row[18], Pattern.compile("^\\d{8}$"), "cep");
                warnIfNotMatch(row[11], DIGITS_7, "cnae_fiscal_principal");
                if (!isNullOrEmpty(row[19])) warnIfNotIn(row[19], VALID_UF, "uf");
                validateDate(row[6], "data_situacao_cadastral");
                validateDate(row[10], "data_inicio_atividade");
            }
            case SOCIOCSV -> {
                warnIfNotMatch(row[0], DIGITS_8, "cnpj_basico");
                warnIfNotIn(row[1], Set.of("1","2","3"), "identificador_de_socio");
                warnIfNotMatch(row[10], Pattern.compile("^\\d$"), "faixa_etaria");
                validateDate(row[5], "data_entrada_sociedade");
            }
            case SIMPLESCSV -> {
                warnIfNotMatch(row[0], DIGITS_8, "cnpj_basico");
                warnIfNotIn(row[1], Set.of("S","N",""), "opcao_pelo_simples");
                warnIfNotIn(row[4], Set.of("S","N",""), "opcao_pelo_mei");
            }
            default -> {}
        }
    }

    private void warnIfNotMatch(String value, Pattern pattern, String field) {
        if (!isNullOrEmpty(value) && !pattern.matcher(value).matches()) {
            System.err.printf("[WARN] Invalid %s: '%s'%n", field, value);
        }
    }

    private void warnIfNotIn(String value, Set<String> valid, String field) {
        if (!isNullOrEmpty(value) && !valid.contains(value)) {
            System.err.printf("[WARN] Invalid %s: '%s'%n", field, value);
        }
    }

    private void validateDate(String value, String field) {
        if (isNullOrEmpty(value) || value.equals("0") || value.equals("00000000")) return;
        try {
            LocalDate date = LocalDate.parse(value, DATE_FMT);
            if (date.isBefore(MIN_DATE) || date.isAfter(LocalDate.now())) {
                System.err.printf("[WARN] Date out of range %s: '%s'%n", field, value);
            }
        } catch (DateTimeParseException e) {
            System.err.printf("[WARN] Invalid date %s: '%s'%n", field, value);
        }
    }
}
