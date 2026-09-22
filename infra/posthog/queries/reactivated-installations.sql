SELECT count() AS reactivated_installations
FROM (
    SELECT
        splitByChar('.', distinct_id)[1] AS installation_id,
        arraySort(groupUniqArray(toDate(timestamp))) AS report_days
    FROM events
    WHERE event = 'installation_heartbeat'
      AND timestamp >= now() - interval 365 day
    GROUP BY installation_id
)
WHERE arrayExists(
    i -> i > 1 AND report_days[i] >= today() - 30 AND dateDiff('day', report_days[i - 1], report_days[i]) > 30,
    arrayEnumerate(report_days))
