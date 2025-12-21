-- V1__Initial_schema.sql

-- Таблица Site
CREATE TABLE IF NOT EXISTS site (
    id SERIAL PRIMARY KEY,
    status VARCHAR(50) NOT NULL,
    status_time TIMESTAMP NOT NULL,
    last_error VARCHAR(255),
    url VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL
);

-- Таблица Page
CREATE TABLE IF NOT EXISTS page (
    id SERIAL PRIMARY KEY,
    site_id INTEGER NOT NULL,
    path VARCHAR(255) NOT NULL,
    response_code INTEGER NOT NULL,
    content TEXT NOT NULL,
    FOREIGN KEY (site_id) REFERENCES site(id),
    UNIQUE (path, site_id)
);

-- Таблица Index
CREATE TABLE IF NOT EXISTS search_index (
    id SERIAL PRIMARY KEY,
    page_id INTEGER NOT NULL,
    lemma_id INTEGER NOT NULL,
    rank_value INTEGER NOT NULL,
    FOREIGN KEY (page_id) REFERENCES page(id),
    FOREIGN KEY (lemma_id) REFERENCES lemma(id)
);

-- Таблица Lemma
CREATE TABLE IF NOT EXISTS lemma (
    id SERIAL PRIMARY KEY,
    site_id INTEGER NOT NULL,
    lemma_text VARCHAR(255) NOT NULL,
    frequency INTEGER NOT NULL,
    FOREIGN KEY (site_id) REFERENCES site(id),
    UNIQUE (lemma_text, site_id)
);

-- Индексы для оптимизации поиска
CREATE INDEX idx_page_site ON page(site_id);
CREATE INDEX idx_lemma_site ON lemma(site_id);
CREATE INDEX idx_index_page ON search_index(page_id);
CREATE INDEX idx_index_lemma ON search_index(lemma_id);