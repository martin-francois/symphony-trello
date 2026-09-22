SELECT
    coalesce(latest.1, 'unknown') AS app_version,
    count() AS installations
FROM (
    SELECT
        distinct_id,
        argMax(tuple(properties.app_version), tuple(timestamp, uuid)) AS latest
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 30 day
    GROUP BY distinct_id
)
GROUP BY app_version
ORDER BY installations DESC
