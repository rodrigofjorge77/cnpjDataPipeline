package org.projeto.cnpjdatapipeline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "pipeline")
public class PipelineConfig {

    private int batchSize = 500000;
    private String tempDir = "./temp";
    private int downloadWorkers = 4;
    private int processWorkers = 1;
    private int retryAttempts = 3;
    private int retryDelaySeconds = 5;
    private boolean keepDownloadedFiles = false;
    private String loadingStrategy = "upsert";
    private String outputFormat = "postgres";
    private String parquetOutputDir = "./parquet";
    private String baseUrl = "https://arquivos.receitafederal.gov.br/index.php/s/YggdBLfdninEJX9";
    private String shareToken = "YggdBLfdninEJX9";

    public boolean isUpsertStrategy() {
        return "upsert".equalsIgnoreCase(loadingStrategy);
    }

    public boolean isParquetOutput() {
        return "parquet".equalsIgnoreCase(outputFormat);
    }
}
