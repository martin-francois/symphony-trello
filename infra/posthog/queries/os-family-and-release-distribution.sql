SELECT
    latest.1 AS os_family,
    latest.2 AS os_release,
    coalesce(latest.3, '-') AS linux_distribution,
    count() AS installations
FROM (
    SELECT
        splitByChar('.', distinct_id)[1] AS installation_id,
        argMax(tuple(properties.os_family, properties.os_release, properties.linux_distribution), tuple(timestamp, uuid)) AS latest
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 30 day
    GROUP BY installation_id
)
GROUP BY os_family, os_release, linux_distribution
ORDER BY installations DESC
