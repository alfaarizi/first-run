-- vector(1024) pins the column to the embedding model's width, so a model
-- change is a new migration plus a full reindex. Embeddings arrive
-- unit-length, so the HNSW index uses inner product and retrieval must query
-- with <#> to hit it. The index spans all tenants while row-level security
-- filters after the scan, so retrieval enables iterative scans to keep
-- recall for small tenants. crawl_id lets a re-crawl insert incrementally and
-- sweep other generations in one delete. Foreign-key checks bypass
-- row-level security, so composite keys pin every reference inside one
-- tenant.

CREATE TABLE doc_source (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    app_id uuid NOT NULL,
    url text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED')),
    -- The last completed generation. The next crawl's start sweeps chunks
    -- outside it, so a killed agent never strands partial rows.
    live_crawl_id uuid,
    last_indexed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT doc_source_app_fkey
        FOREIGN KEY (app_id, tenant_id) REFERENCES app (id, tenant_id),
    CONSTRAINT doc_source_app_id_url_key UNIQUE (app_id, url),
    CONSTRAINT doc_source_id_tenant_id_key UNIQUE (id, tenant_id)
);

CREATE TABLE doc_chunk (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    source_id uuid NOT NULL,
    crawl_id uuid NOT NULL,
    source_url text NOT NULL,
    heading_path text[] NOT NULL,
    content text NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT doc_chunk_source_fkey
        FOREIGN KEY (source_id, tenant_id) REFERENCES doc_source (id, tenant_id)
);

CREATE INDEX doc_chunk_source_id_idx ON doc_chunk (source_id);

CREATE INDEX doc_chunk_embedding_idx ON doc_chunk
    USING hnsw (embedding vector_ip_ops);

ALTER TABLE doc_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE doc_source FORCE ROW LEVEL SECURITY;
CREATE POLICY doc_source_tenant_isolation ON doc_source
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE doc_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE doc_chunk FORCE ROW LEVEL SECURITY;
CREATE POLICY doc_chunk_tenant_isolation ON doc_chunk
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
