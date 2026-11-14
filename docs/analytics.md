# Typed numeric analytics

Open any record workspace and select **Analytics**. Choose a configured integer/decimal field, optionally group by another field, and apply the same supported encoded query filters as the record API. Reports include all matching records the signed-in user may read, independently of workspace/API pagination. There are no saved reports, time-series snapshots or process-mining engine in this slice.

`GET /api/openservice/analytics/{table}?field=estimate&group_by=state&q=state%3Dworking` returns a local OpenService contract:

```json
{
  "result": {
    "field": "estimate",
    "group_by": "state",
    "rows": [{
      "group": "working", "count": "2", "value_count": "2",
      "sum": "0.3", "min": "0.1", "max": "0.2", "average": "0.15"
    }]
  }
}
```

`field` is required in the API and must have an integer or decimal rule in the table's configuration. `group_by` and `q` are optional; unknown or repeated parameters are rejected. Group keys are stored values sorted lexically. With no grouping, there is one row even for an empty selection; grouped empty selections return an empty list. Record and nonblank-value counts are separate. Blank/whitespace numeric values do not contribute to numeric statistics. Empty selections have sum `0` and null minimum/maximum/average.

Integer and decimal sums/minimums/maximums use exact decimal arithmetic, including values whose sums exceed a signed 64-bit integer. Averages use DECIMAL128 (34 significant digits, half-even rounding). Numbers are returned as strings without exponent notation or insignificant trailing zeros. The implementation streams the complete authorized SQL selection; grouping memory grows with the number of distinct groups. Production workload capacity remains unverified.

The existing `/api/now/stats/{table}` count contract is unchanged. ServiceNow's Australia [Aggregate API](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/api-reference/rest-apis/c_AggregateAPI.md), retrieved 2026-11-14, supplies the capability reference for sum/average/minimum/maximum. This endpoint does not claim its wire format, numeric formatting or null semantics are vendor-conformant. Native numeric Aggregate API parameters, historical KPIs and process mining remain separate gaps.

SQLite/PostgreSQL HTTP tests verify exact decimal results, negative and blank values, grouping/filtering, unauthorized and hidden-record behavior, empty selections and all 10,001 rows of a selection beyond the Table API page cap. Chromium verifies the workspace-to-report journey and a numeric result from a configured table.
