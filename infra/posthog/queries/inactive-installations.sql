SELECT
    count() AS inactive_installations
FROM (
    SELECT distinct_id, max(timestamp) AS last_seen
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 365 day
    GROUP BY distinct_id
)
WHERE last_seen < now() - interval 30 day
