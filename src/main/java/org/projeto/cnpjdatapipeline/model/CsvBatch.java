package org.projeto.cnpjdatapipeline.model;

import java.util.List;

public record CsvBatch(FileType fileType, List<String[]> rows) {

    public int size() {
        return rows.size();
    }
}
