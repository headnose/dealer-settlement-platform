// Load test for POST /api/v1/wires (blueprint Workflow 3, wire generation).
//
// Open model: a fixed arrival rate, so the sequential and the parallel version get exactly
// the same load and only latency can differ. RATE is a test input (default 20 req/s, the low end
// of the blueprint's "20-50 req/s bursts"), not a measured result.
//
// Start wire-service from the packaged jar for every measured run (no IDE/plugin JIT flags):
//   java -jar wire-service/target/wire-service-0.1.0-SNAPSHOT.jar
// Warm-up (JIT, Hibernate query plans, pool connections), not reported:
//   k6 run -e RATE=5 -e DURATION=20s load-test/wire-generation.js
// Measured run:
//   k6 run -e RATE=20 -e DURATION=60s load-test/wire-generation.js
// Timeout runs (slow settlement-service through Toxiproxy): every request is EXPECTED to fail with
// 502, and many requests are in flight at once (rate x latency), so pre-allocate enough VUs:
//   k6 run -e RATE=20 -e DURATION=60s -e EXPECT_STATUS=502 -e PRE_VUS=150 load-test/wire-generation.js
//
// Every 201 creates a DRAFT submission + 4 recipients in local wire_db (decision D5); a 502 saves
// nothing.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const LOAN_ID = __ENV.LOAN_ID || 'LN-1001';
const RATE = parseInt(__ENV.RATE || '20', 10);
const DURATION = __ENV.DURATION || '60s';
// 201 for normal runs, 502 for runs where the settlement-service call is meant to time out.
const EXPECT_STATUS = parseInt(__ENV.EXPECT_STATUS || '201', 10);
// k6 starting VUs on the fly during a run can drop iterations, so slow runs pre-allocate them.
const PRE_VUS = parseInt(__ENV.PRE_VUS || '20', 10);

export const options = {
    scenarios: {
        generate: {
            executor: 'constant-arrival-rate',
            exec: 'generate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: PRE_VUS,
            maxVUs: 300,
        },
        // Samples wire-service's gauges once per second during the run (see observe()).
        observe: {
            executor: 'constant-arrival-rate',
            exec: 'observe',
            rate: 1,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 2,
            maxVUs: 5,
        },
    },
    thresholds: {
        // Only the scenario's requests count, not the actuator calls.
        'checks{scenario:generate}': ['rate==1'],
        // "Failed" = any status other than EXPECT_STATUS (see responseCallback below).
        'http_req_failed{scenario:generate}': ['rate==0'],
        // k6 ran out of VUs = the requested rate was not really delivered.
        dropped_iterations: ['count==0'],
        // Always passes; only makes the summary show the measured requests on their own line.
        'http_req_duration{scenario:generate}': ['max>=0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Server-side timers read from Actuator before and after the run: [label, metric, tags].
const WHOLE = 'whole request (server)';
const SERVER_METRICS = [
    [WHOLE, 'http.server.requests', ['uri:/api/v1/wires', 'method:POST']],
    ['LSC call (client)', 'http.client.requests',
        ['uri:/api/v1/loans/{loanId}/settlements', 'method:GET']],
    ['template lookup', 'spring.data.repository.invocations',
        ['repository:WireTemplateRepository', 'method:findFirstByWireTypeOrderByVersionDesc']],
    ['distributions findAll', 'spring.data.repository.invocations',
        ['repository:NotificationDistributionRepository', 'method:findAll']],
    // persist() only: Hibernate sends the INSERTs at commit, outside this timer. Transaction round trips
    // (autocommit off/on, COMMIT, read-only flag) are outside ALL repository timers (MySQL general log, P6).
    ['save (persist only)', 'spring.data.repository.invocations',
        ['repository:WireSubmissionRepository', 'method:save']],
    ['DB connection acquire', 'hikaricp.connections.acquire', []],
];
// Parts of one request; the rest of the whole request is not covered by any timer.
const PARTS = ['LSC call (client)', 'template lookup', 'distributions findAll', 'save (persist only)'];

// Gauges sampled once per second: [k6 metric name, actuator metric]. The summary's max is the peak
// seen during the run. tomcat.threads.busy needs server.tomcat.mbeanregistry.enabled: true.
const GAUGES = [
    ['server_tomcat_threads_busy', 'tomcat.threads.busy'],
    ['server_hikari_connections_active', 'hikaricp.connections.active'],
    ['server_hikari_connections_pending', 'hikaricp.connections.pending'],
];
const gaugeTrends = {};
for (const [k6Name] of GAUGES) {
    gaugeTrends[k6Name] = new Trend(k6Name);
}

function snapshot() {
    const result = {};
    for (const [label, name, tags] of SERVER_METRICS) {
        const query = tags.map((t) => `tag=${encodeURIComponent(t)}`).join('&');
        const url = `${BASE_URL}/actuator/metrics/${name}${query ? `?${query}` : ''}`;
        const res = http.get(url, { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            result[label] = null; // 404 = no data recorded yet for these tags
            continue;
        }
        const stats = {};
        for (const m of res.json().measurements) {
            stats[m.statistic] = m.value;
        }
        result[label] = { count: stats.COUNT || 0, total: stats.TOTAL_TIME || 0, max: stats.MAX || 0 };
    }
    return result;
}

export function setup() {
    return snapshot();
}

const payload = JSON.stringify({ loanId: LOAN_ID, wireType: 'SETTLEMENT' });
const params = {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/v1/wires' },
    // Only EXPECT_STATUS counts as success in http_req_failed (k6 treats 4xx/5xx as failed by default).
    responseCallback: http.expectedStatuses(EXPECT_STATUS),
};

export function generate() {
    const res = http.post(`${BASE_URL}/api/v1/wires`, payload, params);
    const checks = { [`status is ${EXPECT_STATUS}`]: (r) => r.status === EXPECT_STATUS };
    if (EXPECT_STATUS === 201) {
        // Short-circuit: only parse the body when the status is right.
        checks['body has a SUB- id'] = (r) => r.status === 201 && String(r.json('submissionId')).startsWith('SUB-');
    }
    check(res, checks);
}

export function observe() {
    for (const [k6Name, name] of GAUGES) {
        const res = http.get(`${BASE_URL}/actuator/metrics/${name}`, { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            continue; // 404 = gauge not registered (e.g. Tomcat metrics switched off)
        }
        // Several pools/connectors are summed; locally there is one of each.
        const value = res.json().measurements.find((m) => m.statistic === 'VALUE');
        if (value) {
            gaugeTrends[k6Name].add(value.value);
        }
    }
}

/** Mean in ms for this run only, or null without data. */
function meanMs(before, after, label) {
    const b = before[label] || { count: 0, total: 0 };
    const a = after[label];
    if (!a) {
        return null;
    }
    const count = a.count - b.count;
    return { count, mean: count > 0 ? ((a.total - b.total) / count) * 1000 : 0, max: a.max * 1000 };
}

export function teardown(before) {
    const after = snapshot();
    console.log('Server-side timings for THIS run (after minus before):');
    for (const [label] of SERVER_METRICS) {
        const m = meanMs(before, after, label);
        if (!m) {
            console.log(`  ${label}: no data`);
            continue;
        }
        // Micrometer's MAX is a decaying max over the last ~2 minutes, not strictly this run.
        console.log(`  ${label}: ${m.count} calls, mean ${m.mean.toFixed(1)} ms, max ${m.max.toFixed(1)} ms (last ~2 min)`);
    }
    const whole = meanMs(before, after, WHOLE);
    const lsc = meanMs(before, after, PARTS[0]);
    const parts = PARTS.map((label) => meanMs(before, after, label));
    if (whole && parts.every((p) => p)) {
        // Only meaningful for the SEQUENTIAL version: in the parallel one (P1) the LSC call and the reads
        // overlap, so subtracting both counts the overlap twice.
        const rest = whole.mean - parts.reduce((sum, p) => sum + p.mean, 0);
        console.log(`  rest, sequential version only (whole minus all parts): mean ${rest.toFixed(1)} ms per request`);
    }
    if (whole && lsc) {
        // Parallel version: after LSC answers, only merge + save + framework remain on the critical path.
        // Note: repository timers exclude transaction round trips (begin/commit, read-only flag).
        console.log(`  whole minus LSC (parallel critical path after LSC): mean ${(whole.mean - lsc.mean).toFixed(1)} ms per request`);
    }
}