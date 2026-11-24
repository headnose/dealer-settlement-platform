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
//
// Every request creates a DRAFT submission + 4 recipients in local wire_db (decision D5).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const LOAN_ID = __ENV.LOAN_ID || 'LN-1001';
const RATE = parseInt(__ENV.RATE || '20', 10);
const DURATION = __ENV.DURATION || '60s';

export const options = {
    scenarios: {
        generate: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 20,
            maxVUs: 200,
        },
    },
    thresholds: {
        // Only the scenario's requests count, not the actuator calls in setup/teardown.
        'checks{scenario:generate}': ['rate==1'],
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
};

export default function () {
    const res = http.post(`${BASE_URL}/api/v1/wires`, payload, params);
    check(res, {
        'status is 201': (r) => r.status === 201,
        // Short-circuit: only parse the body when the status is right.
        'body has a SUB- id': (r) => r.status === 201 && String(r.json('submissionId')).startsWith('SUB-'),
    });
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