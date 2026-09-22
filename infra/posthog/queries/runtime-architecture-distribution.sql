SELECT
    latest.1 AS runtime_arch,
    count() AS installations
FROM (
    SELECT
        splitByChar('.', distinct_id)[1] AS installation_id,
        argMax(tuple(properties.runtime_arch), tuple(timestamp, uuid)) AS latest
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 30 day
    GROUP BY installation_id
)
GROUP BY runtime_arch
ORDER BY installations DESC
