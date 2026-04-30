# CNPJ Data Pipeline

ETL em Java Spring Boot que baixa os dados públicos de empresas da **Receita Federal do Brasil** via WebDAV e os carrega em um banco PostgreSQL.

---

## Diagrama de Arquitetura

```mermaid
flowchart TD
    subgraph Entrada
        RF[("Receita Federal\nWebDAV / Nextcloud")]
    end

    subgraph Pipeline["Pipeline (Spring Boot)"]
        direction TB
        CFG["PipelineConfig\n(application.properties / env vars)"]
        RUN["PipelineRunner\n(ApplicationRunner)"]
        DL["DownloaderService\nWebDAV PROPFIND\nDownload ZIP\nExtração CSV"]
        PROC["ProcessorService\nISO-8859-1 → UTF-8\nCSV parse (Commons CSV)\nTransformações\nValidações"]
        DB["DatabaseService\nPostgreSQL COPY\nUpsert / Replace"]
    end

    subgraph Saída
        PG[("PostgreSQL\n10 tabelas")]
    end

    RF -->|"ZIP files"| DL
    CFG --> RUN
    RUN -->|"Grupo 1 → 2 → 3\n(ordem de dependência)"| DL
    DL -->|"Arquivos CSV"| PROC
    PROC -->|"CsvBatch\n(500k linhas)"| DB
    DB -->|"COPY FROM STDIN"| PG
```

---

## Diagrama de Relacionamento entre Tabelas (ER)

```mermaid
erDiagram
    empresas {
        varchar cnpj_basico PK
        text razao_social
        varchar natureza_juridica FK
        varchar qualificacao_responsavel FK
        double capital_social
        varchar porte
        text ente_federativo_responsavel
    }

    estabelecimentos {
        varchar cnpj_basico PK,FK
        varchar cnpj_ordem PK
        varchar cnpj_dv PK
        int identificador_matriz_filial
        text nome_fantasia
        varchar situacao_cadastral
        date data_situacao_cadastral
        varchar motivo_situacao_cadastral FK
        varchar pais FK
        date data_inicio_atividade
        varchar cnae_fiscal_principal FK
        text cnae_fiscal_secundaria
        varchar uf
        varchar municipio FK
        text correio_eletronico
    }

    socios {
        varchar cnpj_basico PK,FK
        varchar identificador_de_socio PK
        varchar cnpj_cpf_do_socio PK
        text nome_socio
        varchar qualificacao_do_socio FK
        date data_entrada_sociedade
        varchar pais FK
        varchar qualificacao_do_representante_legal FK
        varchar faixa_etaria
    }

    dados_simples {
        varchar cnpj_basico PK,FK
        varchar opcao_pelo_simples
        date data_opcao_pelo_simples
        date data_exclusao_do_simples
        varchar opcao_pelo_mei
        date data_opcao_pelo_mei
        date data_exclusao_do_mei
    }

    naturezas_juridicas {
        varchar codigo PK
        text descricao
    }

    qualificacoes_socios {
        varchar codigo PK
        text descricao
    }

    motivos {
        varchar codigo PK
        text descricao
    }

    paises {
        varchar codigo PK
        text descricao
    }

    municipios {
        varchar codigo PK
        text descricao
    }

    cnaes {
        varchar codigo PK
        text descricao
    }

    empresas }o--|| naturezas_juridicas : "natureza_juridica"
    empresas }o--|| qualificacoes_socios : "qualificacao_responsavel"
    estabelecimentos }o--|| empresas : "cnpj_basico"
    estabelecimentos }o--o| motivos : "motivo_situacao_cadastral"
    estabelecimentos }o--o| paises : "pais"
    estabelecimentos }o--o| municipios : "municipio"
    estabelecimentos }o--o| cnaes : "cnae_fiscal_principal"
    socios }o--|| empresas : "cnpj_basico"
    socios }o--o| qualificacoes_socios : "qualificacao_do_socio"
    socios }o--o| qualificacoes_socios : "qualificacao_do_representante_legal"
    socios }o--o| paises : "pais"
    dados_simples ||--|| empresas : "cnpj_basico"
```

---

## Pré-requisitos

- Java 17+
- Maven 3.8+ (ou use o `mvnw` incluído)
- PostgreSQL 14+ **ou** Docker + Docker Compose

---

## Configuração rápida com Docker

A forma mais simples de rodar é com Docker Compose, que sobe o PostgreSQL automaticamente:

```bash
# 1. Build do JAR
./mvnw clean package -DskipTests

# 2. Subir apenas o banco de dados
docker compose up -d postgres

# 3. Listar meses disponíveis
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar --list

# 4. Processar o mês mais recente
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar

# 5. Ou rodar tudo via Docker (pipeline + banco)
docker compose run --rm pipeline
docker compose run --rm pipeline --list
docker compose run --rm pipeline --month=2024-11
```

> **Sem Docker:** certifique-se de ter um PostgreSQL rodando e crie o banco `cnpj` antes de executar.
> ```sql
> CREATE DATABASE cnpj;
> ```

---

## Configuração

Todas as opções são configuradas via variáveis de ambiente (ou `application.properties`):

| Variável | Padrão | Descrição |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/cnpj` | URL JDBC do banco |
| `DB_USER` | `postgres` | Usuário do banco |
| `DB_PASSWORD` | `postgres` | Senha do banco |
| `LOADING_STRATEGY` | `upsert` | `upsert` (incremental) ou `replace` (truncate + insert) |
| `OUTPUT_FORMAT` | `postgres` | `postgres` ou `parquet` |
| `BATCH_SIZE` | `500000` | Linhas por lote de carga |
| `DOWNLOAD_WORKERS` | `4` | Downloads paralelos |
| `PROCESS_WORKERS` | `1` | Workers paralelos dentro de cada grupo |
| `RETRY_ATTEMPTS` | `3` | Tentativas em caso de falha de download |
| `RETRY_DELAY` | `5` | Segundos entre tentativas |
| `TEMP_DIR` | `./temp` | Diretório temporário para ZIPs e CSVs |
| `KEEP_DOWNLOADED_FILES` | `false` | Manter arquivos temporários após carga |
| `PARQUET_OUTPUT_DIR` | `./parquet` | Diretório de saída Parquet |
| `BASE_URL` | URL Receita Federal | Endpoint WebDAV |

---

## Como executar

### Build

```bash
./mvnw clean package -DskipTests
```

### Executar

```bash
# Listar meses disponíveis
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar --list

# Processar o mês mais recente
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar

# Processar um mês específico
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar --month=2024-11

# Forçar reprocessamento (ignora tracking de arquivos já processados)
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar --month=2024-11 --force
```

### Com variáveis de ambiente

```bash
DATABASE_URL=jdbc:postgresql://myhost:5432/cnpj \
DB_USER=myuser \
DB_PASSWORD=mypassword \
LOADING_STRATEGY=replace \
BATCH_SIZE=1000000 \
java -jar target/cnpjDataPipeline-0.0.1-SNAPSHOT.jar
```

---

## Estratégias de Carga

### Upsert (padrão)
- Usa tabela temporária + `INSERT ... ON CONFLICT DO UPDATE`
- Mantém disponibilidade durante a carga
- Ideal para atualizações incrementais mensais
- Rastreia arquivos processados em `processed_files` (permite retomada)

### Replace
- Executa `TRUNCATE` seguido de `COPY` direto
- Mais rápido para carga inicial completa
- Não há tracking de progresso (recomeça do zero se interrompido)

---

## Ordem de Processamento

Os arquivos são processados em grupos de dependência para garantir integridade referencial:

| Grupo | Tabelas | Descrição |
|---|---|---|
| 1 | `cnaes`, `motivos`, `municipios`, `naturezas_juridicas`, `paises`, `qualificacoes_socios` | Tabelas de referência (sem dependências) |
| 2 | `empresas` | Depende do Grupo 1 |
| 3 | `estabelecimentos`, `socios`, `dados_simples` | Dependem do Grupo 2 |

Arquivos dentro do mesmo grupo podem ser processados em paralelo (configurável via `PROCESS_WORKERS`).

---

## Schema do Banco

O schema é criado automaticamente na primeira execução a partir de `src/main/resources/initial.sql`.

**Tabelas principais:**
- `empresas` — Dados cadastrais das empresas (CNPJ raiz)
- `estabelecimentos` — Filiais e matrizes (endereço, situação, CNAE)
- `socios` — Quadro societário
- `dados_simples` — Opção pelo Simples Nacional / MEI

**Tabelas de referência:**
- `cnaes` — Classificação Nacional de Atividades Econômicas
- `motivos` — Motivos de situação cadastral
- `municipios` — Códigos de municípios
- `naturezas_juridicas` — Naturezas jurídicas
- `paises` — Países
- `qualificacoes_socios` — Qualificações de sócios

**Tracking:**
- `processed_files` — Controle de arquivos já carregados (evita reprocessamento)

---

## Fonte dos Dados

Os dados são disponibilizados mensalmente pela Receita Federal do Brasil:
- Portal: [dados.gov.br](https://dados.gov.br/dados/conjuntos-dados/cadastro-nacional-da-pessoa-juridica---cnpj)
- Tamanho aproximado: ~85 GB (CSV) / ~6 GB (Parquet comprimido)
- Encoding: ISO-8859-1
- Separador: `;`
- Atualização: Mensal
