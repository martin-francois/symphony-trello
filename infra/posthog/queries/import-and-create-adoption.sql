SELECT
    multiIf(
        toInt(latest.1) > 0 AND toInt(latest.2) > 0, 'both',
        toInt(latest.1) > 0, 'import only',
        toInt(latest.2) > 0, 'create only',
        'neither') AS adoption,
    count() AS installations
FROM (
    SELECT
        splitByChar('.', distinct_id)[1] AS installation_id,
        argMax(tuple(properties.board_imports_total, properties.board_creations_total), tuple(timestamp, uuid)) AS latest
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 30 day
    GROUP BY installation_id
)
GROUP BY adoption
ORDER BY installations DESC
