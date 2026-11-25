// Load test for POST /api/v1/imports (blueprint Workflow 1, loan import) with a slow loan-service.
//
// Every request imports LOAN_COUNT loanIds that loan-service does not know (LN-LT-01 ... LN-LT-06).
// Each one still makes a real GET to loan-service (404 -> NOT_FOUND; no negative caching, C5) and
// nothing is written to MongoDB, so runs can be repeated without cleanup. A 404 is a SUCCESSFUL call
// for the loanService breaker, so only the Toxiproxy toxic decides whether calls fail.
//
// settlement-service from the packaged jar, calling loan-service THROUGH Toxiproxy (18081 -> 8081):
//   java -jar settlement-service/target/settlement-service-0.1.0-SNAPSHOT.jar --clients.loan-service.base-url=http://localhost:18081
// Warm-up (no toxic), not reported:
//   k6 run -e RATE=2 -e DURATION=20s load-test/loan-import.js
// Measured run:
//   k6 run -e RATE=2 -e DURATION=60s load-test/loan-import.js
// Toxic = latency on loan-service's responses (no jitter):
//   curl -s -X POST localhost:8474/proxies/loan-service/toxics -H 'Content-Type: application/json' -d '{"name":"loan_latency","type":"latency","stream":"downstream","attributes":{"latency":4000,"jitter":0}}'
// Remove it:
//   curl -s -X DELETE localhost:8474/proxies/loan-service/toxics/loan_latency
//
// Since the rejection fix (B2), a full import pool no longer fails the request: the loans that found no
// free slot get IMPORT_CAPACITY_EXCEEDED inside a normal 200. So every response must be 200; a 500 now
// means a bug. (Runs I1-I4 and I2b were made BEFORE the fix, when a rejection returned 500.)
// Mixed per-loan outcomes are still expected under a toxic: the summary COUNTS them.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082';
const RATE = parseInt(__ENV.RATE || '2', 10);
const DURATION = __ENV.DURATION || '60s';
const LOAN_COUNT = parseInt(__ENV.LOAN_COUNT || '6', 10);
const LOAN_PREFIX = __ENV.LOAN_PREFIX || 'LN-LT-';
// Slow runs keep requests open for many seconds (rate x latency in flight): pre-allocate VUs, or k6 drops
// iterations and the real rate is lower than asked.
const PRE_VUS = parseInt(__ENV.PRE_VUS || '60', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '300', 10);

const LOAN_IDS = Array.from({ length: LOAN_COUNT }, (_, i) => `${LOAN_PREFIX}${String(i + 1).padStart(2, '0')}`);

export const options = {
    scenarios: {
        imports: {
            executor: 'constant-arrival-rate',
            exec: 'importBatch',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: PRE_VUS,
            maxVUs: MAX_VUS,
        },
        // Samples settlement-service's gauges once per second during the run (see observe()).
        observe: {
            executor: 'constant-arrival-rate',
            exec: 'observe',
            // Offset from the import arrivals (every 0.5 s from t=0): sampling exactly when a batch
            // arrives always showed 4 active / 2 queued, even when idle (I1).
            startTime: '250ms',
            rate: 1,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 2,
            maxVUs: 5,
        },
    },
    thresholds: {
        // Only 200 is expected; anything else (or a malformed 200 body) fails the run.
        'checks{scenario:imports}': ['rate==1'],
        // k6 ran out of VUs = the requested rate was not really delivered.
        dropped_iterations: ['count==0'],
        // Always pass; they only make the summary show these lines (latency split by status).
        'http_req_duration{scenario:imports}': ['max>=0'],
        'http_req_duration{scenario:imports,status:200}': ['max>=0'],
        'http_req_duration{scenario:imports,status:500}': ['max>=0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Status and per-loan outcome counters (only the ones that occur show up in the summary).
const statusCounters = { 200: new Counter('status_200'), 500: new Counter('status_500') };
const statusOther = new Counter('status_other');
const OUTCOMES = ['IMPORTED', 'ALREADY_IMPORTED', 'NOT_FOUND', 'NOT_CLOSED', 'LOAN_SERVICE_UNAVAILABLE',
    'IMPORT_CAPACITY_EXCEEDED', 'FAILED'];
const outcomeCounters = {};
for (const outcome of OUTCOMES) {
    outcomeCounters[outcome] = new Counter(`outcome_${outcome.toLowerCase()}`);
}
const outcomeOther = new Counter('outcome_other');
// Requests (not loans) by how much of the batch the pool accepted: some loans rejected, or all of them.
const requestsPartlyRejected = new Counter('requests_partly_rejected');
const requestsFullyRejected = new Counter('requests_fully_rejected');

// Gauges sampled once per second: [k6 metric name, actuator metric, tags]. The summary's max is the peak
// seen during the run. The breaker gauge is 1 while loanService is OPEN, else 0 (avg = share of samples open).
const EXECUTOR = 'name:loanImportExecutor';
const GAUGES = [
    ['server_tomcat_threads_busy', 'tomcat.threads.busy', []],
    ['server_executor_active', 'executor.active', [EXECUTOR]],
    ['server_executor_pool_size', 'executor.pool.size', [EXECUTOR]],
    ['server_executor_queued', 'executor.queued', [EXECUTOR]],
    ['server_breaker_open', 'resilience4j.circuitbreaker.state', ['name:loanService', 'state:open']],
];
const gaugeTrends = {};
for (const [k6Name] of GAUGES) {
    gaugeTrends[k6Name] = new Trend(k6Name);
}

// Read before and after the run; teardown prints this run's deltas: [label, actuator metric, tags].
// Timers (TOTAL_TIME present) also get a mean. loan-service calls are counted by the breaker's timer:
// the client is built without Boot's builder, so there is no http.client.requests for it.
const METRICS = [
    ['whole request (server)', 'http.server.requests', ['uri:/api/v1/imports', 'method:POST']],
    ['loan-service calls, successful (incl. 404)', 'resilience4j.circuitbreaker.calls',
        ['name:loanService', 'kind:successful']],
    ['loan-service calls, failed', 'resilience4j.circuitbreaker.calls', ['name:loanService', 'kind:failed']],
    ['breaker: calls not permitted (failed fast)', 'resilience4j.circuitbreaker.not.permitted.calls',
        ['name:loanService']],
    ['executor tasks completed', 'executor.completed', [EXECUTOR]],
];

function metricUrl(name, tags) {
    const query = tags.map((t) => `tag=${encodeURIComponent(t)}`).join('&');
    return `${BASE_URL}/actuator/metrics/${name}${query ? `?${query}` : ''}`;
}

function snapshot() {
    const result = {};
    for (const [label, name, tags] of METRICS) {
        const res = http.get(metricUrl(name, tags), { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            result[label] = null; // 404 = nothing recorded yet for these tags
            continue;
        }
        const stats = {};
        for (const m of res.json().measurements) {
            stats[m.statistic] = m.value;
        }
        result[label] = {
            count: stats.COUNT || 0,
            total: stats.TOTAL_TIME || 0,
            max: stats.MAX || 0,
            isTimer: stats.TOTAL_TIME !== undefined,
        };
    }
    return result;
}

export function setup() {
    // A misspelled metric or tag would silently leave a gauge empty: say so before the run starts.
    for (const [k6Name, name, tags] of GAUGES) {
        const res = http.get(metricUrl(name, tags), { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            console.warn(`gauge ${k6Name} (${name} ${tags.join(' ')}) not found: HTTP ${res.status}`);
        }
    }
    return snapshot();
}

const payload = JSON.stringify({ loanIds: LOAN_IDS });
const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/v1/imports' },
    // http_req_failed = share of non-200 responses; should be 0 since B2 (the check enforces it).
    responseCallback: http.expectedStatuses(200),
};

export function importBatch() {
    const res = http.post(`${BASE_URL}/api/v1/imports`, payload, params);
    (statusCounters[res.status] || statusOther).add(1);

    let results = null;
    if (res.status === 200) {
        try {
            results = res.json();
        } catch (e) {
            results = null;
        }
        if (Array.isArray(results)) {
            let rejected = 0;
            for (const r of results) {
                (outcomeCounters[r.outcome] || outcomeOther).add(1);
                if (r.outcome === 'IMPORT_CAPACITY_EXCEEDED') {
                    rejected++;
                }
            }
            if (rejected === results.length) {
                requestsFullyRejected.add(1);
            } else if (rejected > 0) {
                requestsPartlyRejected.add(1);
            }
        }
    }

    check(res, {
        'status is 200': (r) => r.status === 200,
        // ImportController returns one result per loan, in input order.
        'a 200 has one result per loan, in order': (r) => r.status !== 200
            || (Array.isArray(results) && results.length === LOAN_COUNT
                && results.every((x, i) => x.loanId === LOAN_IDS[i])),
    });
}

export function observe() {
    for (const [k6Name, name, tags] of GAUGES) {
        const res = http.get(metricUrl(name, tags), { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            continue; // reported once in setup()
        }
        const value = res.json().measurements.find((m) => m.statistic === 'VALUE');
        if (value) {
            gaugeTrends[k6Name].add(value.value);
        }
    }
}

export function teardown(before) {
    const after = snapshot();
    console.log('Server-side numbers for THIS run (after minus before):');
    for (const [label] of METRICS) {
        const b = before[label];
        const a = after[label];
        if (!a) {
            console.log(`  ${label}: no data`);
            continue;
        }
        const count = a.count - (b ? b.count : 0);
        let line = `  ${label}: ${count}`;
        if (a.isTimer) {
            const total = a.total - (b ? b.total : 0);
            const mean = count > 0 ? (total / count) * 1000 : 0;
            // Micrometer's MAX is a decaying max over the last ~2 minutes, not strictly this run.
            line += `, mean ${mean.toFixed(1)} ms, max ${(a.max * 1000).toFixed(1)} ms (last ~2 min)`;
        }
        console.log(line);
    }
    // Since B2 a request waits for every task it started, so nothing should still be running now.
    // Check executor.queued / executor.active right after the run: both 0 = no orphaned imports.
    console.log('  (since B2: executor.queued and executor.active should be 0 now = no orphaned imports)');
}