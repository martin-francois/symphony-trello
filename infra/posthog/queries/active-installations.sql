SELECT
    uniqIf(splitByChar('.', distinct_id)[1], timestamp >= now() - interval 1 day) AS active_1d,
    uniqIf(splitByChar('.', distinct_id)[1], timestamp >= now() - interval 7 day) AS active_7d,
    uniqIf(splitByChar('.', distinct_id)[1], timestamp >= now() - interval 30 day) AS active_30d
FROM events
WHERE event = 'installation_heartbeat'
  AND timestamp >= now() - interval 30 day
