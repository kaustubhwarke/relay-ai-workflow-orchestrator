package com.relay.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.domain.WorkflowRecord;
import com.relay.domain.WorkflowVersionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    public WorkflowRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private WorkflowRecord mapWorkflow(ResultSet rs, int row) throws SQLException {
        return new WorkflowRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                json.readColumn(rs, "definition"),
                rs.getString("webhook_secret_hash"),
                rs.getInt("current_version"),
                JsonCodec.instant(rs, "created_at"),
                JsonCodec.instant(rs, "updated_at"),
                JsonCodec.instant(rs, "published_at"));
    }

    private WorkflowVersionRecord mapVersion(ResultSet rs, int row) throws SQLException {
        return new WorkflowVersionRecord(
                rs.getString("workflow_id"),
                rs.getInt("version"),
                json.readColumn(rs, "definition"),
                rs.getString("webhook_secret_hash"),
                JsonCodec.instant(rs, "published_at"));
    }

    public boolean exists(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM workflows WHERE id = :id").param("id", id).query(Integer.class).single() > 0;
    }

    public void insert(String id, String name, String description, JsonNode definition, String secretHash) {
        jdbc.sql("""
                INSERT INTO workflows (id, name, description, status, definition, webhook_secret_hash)
                VALUES (:id, :name, :description, 'draft', CAST(:definition AS jsonb), :hash)
                """)
                .param("id", id).param("name", name).param("description", description)
                .param("definition", json.write(definition)).param("hash", secretHash)
                .update();
    }

    /** Updates the working copy; the workflow returns to {@code draft} until it is published again. */
    public int updateWorkingCopy(String id, String name, String description, JsonNode definition, String secretHash) {
        return jdbc.sql("""
                UPDATE workflows SET name = :name, description = :description, status = 'draft',
                       definition = CAST(:definition AS jsonb), webhook_secret_hash = :hash, updated_at = now()
                WHERE id = :id
                """)
                .param("id", id).param("name", name).param("description", description)
                .param("definition", json.write(definition)).param("hash", secretHash)
                .update();
    }

    public Optional<WorkflowRecord> findById(String id) {
        return jdbc.sql("SELECT * FROM workflows WHERE id = :id").param("id", id).query(this::mapWorkflow).optional();
    }

    /** Locks the workflow row for the duration of the surrounding transaction (serialises publishes). */
    public Optional<WorkflowRecord> findByIdForUpdate(String id) {
        return jdbc.sql("SELECT * FROM workflows WHERE id = :id FOR UPDATE").param("id", id).query(this::mapWorkflow).optional();
    }

    public List<WorkflowRecord> findAll() {
        return jdbc.sql("SELECT * FROM workflows ORDER BY created_at, id").query(this::mapWorkflow).list();
    }

    public void markPublished(String id, int version) {
        jdbc.sql("""
                UPDATE workflows SET status = 'published', current_version = :v, published_at = now(), updated_at = now()
                WHERE id = :id
                """).param("id", id).param("v", version).update();
    }

    public void insertVersion(String workflowId, int version, JsonNode definition, String secretHash) {
        jdbc.sql("""
                INSERT INTO workflow_versions (workflow_id, version, definition, webhook_secret_hash)
                VALUES (:id, :v, CAST(:definition AS jsonb), :hash)
                """)
                .param("id", workflowId).param("v", version)
                .param("definition", json.write(definition)).param("hash", secretHash)
                .update();
    }

    public Optional<WorkflowVersionRecord> findLatestVersion(String workflowId) {
        return jdbc.sql("SELECT * FROM workflow_versions WHERE workflow_id = :id ORDER BY version DESC LIMIT 1")
                .param("id", workflowId).query(this::mapVersion).optional();
    }

    public Optional<WorkflowVersionRecord> findVersion(String workflowId, int version) {
        return jdbc.sql("SELECT * FROM workflow_versions WHERE workflow_id = :id AND version = :v")
                .param("id", workflowId).param("v", version).query(this::mapVersion).optional();
    }

    public List<WorkflowVersionRecord> listVersions(String workflowId) {
        return jdbc.sql("SELECT * FROM workflow_versions WHERE workflow_id = :id ORDER BY version DESC")
                .param("id", workflowId).query(this::mapVersion).list();
    }
}
