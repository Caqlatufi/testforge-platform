ALTER TABLE project_catalog_environment
    MODIFY COLUMN project_id BINARY(16) NULL;

ALTER TABLE project_catalog_environment
    MODIFY COLUMN target_id BINARY(16) NULL;
