package org.projeto.cnpjdatapipeline.model;

import java.util.Arrays;
import java.util.List;

public enum FileType {

    CNAECSV("cnaes", 1, new String[]{"codigo", "descricao"}),
    MOTICSV("motivos", 1, new String[]{"codigo", "descricao"}),
    MUNICCSV("municipios", 1, new String[]{"codigo", "descricao"}),
    NATJUCSV("naturezas_juridicas", 1, new String[]{"codigo", "descricao"}),
    PAISCSV("paises", 1, new String[]{"codigo", "descricao"}),
    QUALSCSV("qualificacoes_socios", 1, new String[]{"codigo", "descricao"}),
    EMPRECSV("empresas", 2, new String[]{
            "cnpj_basico", "razao_social", "natureza_juridica",
            "qualificacao_responsavel", "capital_social", "porte",
            "ente_federativo_responsavel"
    }),
    ESTABELE("estabelecimentos", 3, new String[]{
            "cnpj_basico", "cnpj_ordem", "cnpj_dv",
            "identificador_matriz_filial", "nome_fantasia", "situacao_cadastral",
            "data_situacao_cadastral", "motivo_situacao_cadastral",
            "nome_cidade_exterior", "pais", "data_inicio_atividade",
            "cnae_fiscal_principal", "cnae_fiscal_secundaria",
            "tipo_logradouro", "logradouro", "numero", "complemento", "bairro",
            "cep", "uf", "municipio",
            "ddd_1", "telefone_1", "ddd_2", "telefone_2",
            "ddd_fax", "fax", "correio_eletronico",
            "situacao_especial", "data_situacao_especial"
    }),
    SOCIOCSV("socios", 3, new String[]{
            "cnpj_basico", "identificador_de_socio", "nome_socio",
            "cnpj_cpf_do_socio", "qualificacao_do_socio",
            "data_entrada_sociedade", "pais", "representante_legal",
            "nome_do_representante", "qualificacao_do_representante_legal",
            "faixa_etaria"
    }),
    SIMPLESCSV("dados_simples", 3, new String[]{
            "cnpj_basico", "opcao_pelo_simples", "data_opcao_pelo_simples",
            "data_exclusao_do_simples", "opcao_pelo_mei",
            "data_opcao_pelo_mei", "data_exclusao_do_mei"
    });

    private final String tableName;
    private final int dependencyGroup;
    private final String[] columns;

    FileType(String tableName, int dependencyGroup, String[] columns) {
        this.tableName = tableName;
        this.dependencyGroup = dependencyGroup;
        this.columns = columns;
    }

    public String getTableName() { return tableName; }
    public int getDependencyGroup() { return dependencyGroup; }
    public String[] getColumns() { return columns; }

    public static FileType fromFilename(String filename) {
        String upper = filename.toUpperCase();
        return Arrays.stream(values())
                .filter(ft -> upper.contains(ft.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown file type: " + filename));
    }

    public static List<FileType> byGroup(int group) {
        return Arrays.stream(values())
                .filter(ft -> ft.dependencyGroup == group)
                .toList();
    }
}
