-- A token waiting for a child process is live (#92): the live-token indexes must hold it, or the dry run of a migration,
-- the live counts on the diagram and the queue sweeps would not see a parent that waits on a call. Both partial
-- indexes are rebuilt with WAITING_CHILD in their predicate, under their own names:
--   idx_pre_token_live                 (tenant_id, definition_id, status, current_node_id)   V1
--   idx_pre_token_live_node_instance   (tenant_id, definition_id, current_node_id, instance_id)   V24
-- Each new index is built CONCURRENTLY under a temporary name, then the old one is dropped CONCURRENTLY and the new one is
-- renamed. Queries written for the old predicate (without WAITING_CHILD) are implied by the new one, so they use it, and
-- a replica of the previous release keeps working through the whole upgrade. Every statement may be repeated: a failed
-- build leaves an INVALID index under the temporary name, which is dropped and built again.
-- Additive only. Rollback: build the two old predicates again the same way.
--
-- pre_token is written by every step transition, so nothing here may hold a lock that blocks them (TR-OPS-01).
-- V44__pre_token_live_waiting_child.sql.conf sets executeInTransaction=false, so each statement commits alone.
SET lock_timeout = '5s';

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_token_live_next;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_token_live_node_instance_next;

CREATE INDEX CONCURRENTLY idx_pre_token_live_next
  ON pre_token (tenant_id, definition_id, status, current_node_id)
  WHERE status IN ('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT','WAITING_CHILD');

CREATE INDEX CONCURRENTLY idx_pre_token_live_node_instance_next
  ON pre_token (tenant_id, definition_id, current_node_id, instance_id)
  WHERE status IN ('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT','WAITING_CHILD');

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_token_live;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_token_live_node_instance;

ALTER INDEX idx_pre_token_live_next RENAME TO idx_pre_token_live;
ALTER INDEX idx_pre_token_live_node_instance_next RENAME TO idx_pre_token_live_node_instance;

RESET lock_timeout;
