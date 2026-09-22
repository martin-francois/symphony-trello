SELECT
    multiIf(
        latest.1 IS NULL, 'unknown',
        toInt(latest.1) = 0, '0',
        toInt(latest.1) = 1, '1',
        toInt(latest.1) <= 5, '2-5',
        '6+') AS boards,
    count() AS installations
FROM (
    SELECT
        splitByChar('.', distinct_id)[1] AS installation_id,
        argMax(tuple(properties.connected_board_count), tuple(timestamp, uuid)) AS latest
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 30 day
    GROUP BY installation_id
)
GROUP BY boards
ORDER BY boards
