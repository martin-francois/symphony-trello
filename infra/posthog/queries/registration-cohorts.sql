SELECT
    toStartOfMonth(toDateTime(toString(latest.1))) AS cohort_month,
    count() AS installations
FROM (
    SELECT
        distinct_id,
        argMax(tuple(properties.registered_on), tuple(timestamp, uuid)) AS latest
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 365 day
    GROUP BY distinct_id
)
GROUP BY cohort_month
ORDER BY cohort_month
