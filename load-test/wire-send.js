// Load test for POST /api/v1/wires/{submissionId}/send (blueprint Workflow 4, send path):
// S3 upload of the statement, DRAFT -> QUEUED claim in MySQL, then 4 email-delivery events to Kafka.
// See README "Timeouts under load: the send path".
//
// Every send needs its own DRAFT (a wire can be sent once, one per loan), so the drafts are seeded in MySQL
// first, one batch per run id: load-test/seed-send-drafts.sql. Iteration i sends SUB-LT-<RUN>-<i>.
// Seed at least RATE x DURATION drafts (the script checks COUNT against that before it starts).
//
// Start wire-service from the packaged jar, with S3 and Kafka through Toxiproxy:
//   java -jar wire-service/target/wire-service-0.1.0-SNAPSHOT.jar \
//     --storage.endpoint=http://localhost:18333 --spring.kafka.bootstrap-servers=localhost:19092
// Run (ADMIN token: sending needs SettlementAdmin):
//   k6 run -e TOKEN="$ADMIN" -e RUN=S1 -e COUNT=600 -e RATE=10 -e DURATION=60s load-test/wire-send.js
// Runs where every send is meant to fail (S3 or Kafka too slow): add -e EXPECT_STATUS=503.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const RUN = __ENV.RUN;
const COUNT = parseInt(__ENV.COUNT || '0', 10);
const RATE = parseInt(__ENV.RATE || '10', 10);
const DURATION = __ENV.DURATION || '60s';
// 202 for normal runs, 503 for runs where S3 or Kafka is meant to time out.
const EXPECT_STATUS = parseInt(__ENV.EXPECT_STATUS || '202', 10);
const PRE_VUS = parseInt(__ENV.PRE_VUS || '50', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '400', 10);
// User token with role SettlementAdmin (maker-checker: only an admin sends). From the project root:
//   ADMIN=$(java dev-tools/AlbDevToken.java dev-admin SettlementAdmin 60)
const TOKEN = __ENV.TOKEN;

if (!TOKEN) {
    throw new Error('TOKEN is not set: run with -e TOKEN="$ADMIN" (SettlementAdmin)');
}
if (!RUN) {
    throw new Error('RUN is not set: the run id used to seed the drafts (load-test/seed-send-drafts.sql)');
}
const seconds = parseInt(DURATION, 10) * (DURATION.endsWith('m') ? 60 : 1);
if (COUNT < RATE * seconds) {
    throw new Error(`COUNT=${COUNT} drafts seeded, but RATE x DURATION = ${RATE * seconds} sends: seed more`);
}

export const options = {
    scenarios: {
        send: {
            executor: 'constant-arrival-rate',
            exec: 'send',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: PRE_VUS,
            maxVUs: MAX_VUS,
        },
        // Samples wire-service's gauges once per second (see observe()).
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
        'checks{scenario:send}': ['rate==1'],
        'http_req_failed{scenario:send}': ['rate==0'],
        dropped_iterations: ['count==0'],
        'http_req_duration{scenario:send}': ['max>=0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const SEND_URI = '/api/v1/wires/{submissionId}/send';
const GAUGES = [
    ['server_tomcat_threads_busy', 'tomcat.threads.busy', []],
    ['server_hikari_connections_active', 'hikaricp.connections.active', []],
    ['server_hikari_connections_pending', 'hikaricp.connections.pending', []],
];
const gaugeTrends = {};
for (const [k6Name] of GAUGES) {
    gaugeTrends[k6Name] = new Trend(k6Name);
}

function metricUrl(name, tags) {
    const query = tags.map((t) => `tag=${encodeURIComponent(t)}`).join('&');
    return `${BASE_URL}/actuator/metrics/${name}${query ? `?${query}` : ''}`;
}

/** Server-side timer of the send endpoint, per status: { status: {count, total} }. */
function sendTimer() {
    const result = {};
    for (const status of ['202', '503', '409', '500']) {
        const res = http.get(metricUrl('http.server.requests', [`uri:${SEND_URI}`, 'method:POST', `status:${status}`]),
            { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            continue; // 404 = no request with that status yet
        }
        const stats = {};
        for (const m of res.json().measurements) {
            stats[m.statistic] = m.value;
        }
        result[status] = { count: stats.COUNT || 0, total: stats.TOTAL_TIME || 0 };
    }
    return result;
}

export function setup() {
    console.log(`Run ${RUN}: ${RATE * seconds} sends at ${RATE}/s, expecting ${EXPECT_STATUS}`);
    return sendTimer();
}

const params = {
    headers: { 'x-amzn-oidc-data': TOKEN },
    tags: { name: 'POST /api/v1/wires/{submissionId}/send' },
    responseCallback: http.expectedStatuses(EXPECT_STATUS),
};

export function send() {
    // iterationInTest is unique across all VUs: 0, 1, 2, ... -> drafts 1..N, each sent exactly once.
    const n = exec.scenario.iterationInTest + 1;
    if (n > COUNT) {
        return; // constant-arrival-rate can start one iteration more than rate x duration; no draft for it
    }
    const submissionId = `SUB-LT-${RUN}-${n}`;
    const res = http.post(`${BASE_URL}/api/v1/wires/${submissionId}/send`, null, params);
    check(res, { [`status is ${EXPECT_STATUS}`]: (r) => r.status === EXPECT_STATUS });
}

export function observe() {
    for (const [k6Name, name, tags] of GAUGES) {
        const res = http.get(metricUrl(name, tags), { tags: { name: 'actuator' } });
        if (res.status !== 200) {
            continue;
        }
        const value = res.json().measurements.find((m) => m.statistic === 'VALUE');
        if (value) {
            gaugeTrends[k6Name].add(value.value);
        }
    }
}

export function teardown(before) {
    const after = sendTimer();
    console.log('Server-side timer of the send endpoint for THIS run (http.server.requests, after minus before):');
    for (const status of Object.keys(after)) {
        const b = before[status] || { count: 0, total: 0 };
        const count = after[status].count - b.count;
        if (count > 0) {
            const mean = ((after[status].total - b.total) / count) * 1000;
            console.log(`  ${status}: ${count} requests, mean ${mean.toFixed(1)} ms`);
        }
    }
    console.log(`Check MySQL: SELECT status, COUNT(*) FROM wire_submission WHERE submission_id LIKE 'SUB-LT-${RUN}-%' GROUP BY status;`);
}
