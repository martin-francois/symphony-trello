SELECT
    count() AS inactive_installations
FROM (
    SELECT splitByChar('.', distinct_id)[1] AS installation_id, max(timestamp) AS last_seen
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 365 day
    GROUP BY installation_id
)
WHERE last_seen < now() - interval 30 day
