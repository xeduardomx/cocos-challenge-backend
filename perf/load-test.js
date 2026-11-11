// Measures the response time of the five API functions with k6 (GEN-8, RFC 8.4).
// A constant, moderate load, not a capacity test: docs/mediciones/06-tiempo-de-respuesta.md has the
// method and the results. scripts/run-load-test.sh runs it against the volume data.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

function numberFromEnv(name, defaultValue) {
  return __ENV[name] === undefined ? defaultValue : Number(__ENV[name]);
}

// Each of these can be overridden with an environment variable of the same name.
const API_URL = __ENV.API_URL || 'http://api:8080';
const P95_LIMIT_MS = numberFromEnv('P95_LIMIT_MS', 100);
const REQUESTS_PER_SECOND = numberFromEnv('REQUESTS_PER_SECOND', 10);
const DURATION_SECONDS = numberFromEnv('DURATION_SECONDS', 60);
const WARMUP_ITERATIONS_PER_SECOND = numberFromEnv('WARMUP_ITERATIONS_PER_SECOND', 5);
const WARMUP_SECONDS = numberFromEnv('WARMUP_SECONDS', 30);
// By default, the users of perf/seed-volume.sql.
const TYPICAL_USER_MIN_ID = numberFromEnv('TYPICAL_USER_MIN_ID', 5);
const TYPICAL_USER_MAX_ID = numberFromEnv('TYPICAL_USER_MAX_ID', 10004);
const HEAVY_USER_ID = numberFromEnv('HEAVY_USER_ID', 10005);

// The pause lets the iterations of the warm-up finish before the measured scenarios start.
const WARMUP_PAUSE_SECONDS = 5;
const PREALLOCATED_VUS = 5;
const MAX_VUS = 50;

const STATUS_OK = 200;
const STATUS_CREATED = 201;
const ORDER_SIDE_BUY = 'BUY';
const ORDER_TYPE_MARKET = 'MARKET';
const ORDER_TYPE_LIMIT = 'LIMIT';
const ORDER_STATUS_NEW = 'NEW';
const ORDER_STATUS_FILLED = 'FILLED';
// METR: cheap enough for the money of user 1 to last the whole baseline on the original data.
const PURCHASE_INSTRUMENT_ID = 54;
// METR closed at 229.50: every amount from 230.00 to 429.99 buys exactly one share.
const PURCHASE_AMOUNT_CENTS = 23000;
const LIMIT_ORDER_QUANTITY = 1;
// Any limit order with enough cash stays NEW. These go from 10.00 to 209.99, below the last price.
const LIMIT_ORDER_PRICE_CENTS = 1000;
const CENTS_PER_PESO = 100;
// The API refuses an order equal to another one that its user got accepted in the last 5 seconds
// (RFC 5.5), so every order differs in the cents of its amount or of its limit price. Each scenario
// has this many numbers and uses two per iteration, so it repeats an order after 1,000 iterations:
// 100 seconds at 10 per second. From 200 per second on, it would repeat one inside the 5 seconds.
const ORDERS_PER_SCENARIO = 2000;
// The warm-up sends two orders of each kind in an iteration, one per kind of user.
const ORDERS_PER_ITERATION = 2;
const SEARCH_TERMS = ['pamp', 'banco', 'gas', 'ypf', 'holding'];

const PLACE_STEP = 'place';
const CANCEL_STEP = 'cancel';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// One scenario per function and per kind of user. Only the step named here counts for the limit:
// the cancellation scenarios first place the order that they cancel.
const MEASURED_SCENARIOS = {
  search: { exportedFunction: 'searchInstruments' },
  portfolio_typical: { exportedFunction: 'showPortfolioOfTypicalUser' },
  portfolio_heavy: { exportedFunction: 'showPortfolioOfHeavyUser' },
  submit_typical: { exportedFunction: 'submitPurchaseOfTypicalUser' },
  submit_heavy: { exportedFunction: 'submitPurchaseOfHeavyUser' },
  list_typical: { exportedFunction: 'listOrdersOfTypicalUser' },
  list_heavy: { exportedFunction: 'listOrdersOfHeavyUser' },
  cancel_typical: { exportedFunction: 'placeAndCancelOrderOfTypicalUser', step: CANCEL_STEP },
  cancel_heavy: { exportedFunction: 'placeAndCancelOrderOfHeavyUser', step: CANCEL_STEP },
};

function constantArrivalRate(exportedFunction, iterationsPerSecond, seconds) {
  return {
    executor: 'constant-arrival-rate',
    exec: exportedFunction,
    rate: iterationsPerSecond,
    timeUnit: '1s',
    duration: `${seconds}s`,
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
  };
}

const scenarios = {
  // A JVM that has just started is slow in its first requests. No threshold looks at this scenario.
  warmup: constantArrivalRate('warmUp', WARMUP_ITERATIONS_PER_SECOND, WARMUP_SECONDS),
};
const thresholds = {};

for (const [name, { exportedFunction, step }] of Object.entries(MEASURED_SCENARIOS)) {
  scenarios[name] = {
    ...constantArrivalRate(exportedFunction, REQUESTS_PER_SECOND, DURATION_SECONDS),
    startTime: `${WARMUP_SECONDS + WARMUP_PAUSE_SECONDS}s`,
  };
  const measuredRequests = step === undefined ? `scenario:${name}` : `scenario:${name},step:${step}`;
  thresholds[`http_req_duration{${measuredRequests}}`] = [`p(95)<${P95_LIMIT_MS}`];
  // A threshold whose filter matches no sample passes silently: this makes that run fail.
  thresholds[`http_reqs{${measuredRequests}}`] = ['count>0'];
  thresholds[`checks{scenario:${name}}`] = ['rate==1'];
  // Fewer requests than planned would mean that the API did not get the load the number claims.
  thresholds[`dropped_iterations{scenario:${name}}`] = ['count==0'];
}

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ['count', 'med', 'p(95)', 'p(99)', 'max'],
};

const SCENARIO_NAMES = Object.keys(scenarios);

// A number no other order of the run has while it matters. Each scenario has a range of its own:
// in the baseline one user gets the orders of all of them at the same time.
function orderNumber(positionInIteration) {
  const numberInScenario =
    (exec.scenario.iterationInTest * ORDERS_PER_ITERATION + positionInIteration) % ORDERS_PER_SCENARIO;
  return SCENARIO_NAMES.indexOf(exec.scenario.name) * ORDERS_PER_SCENARIO + numberInScenario;
}

function typicalUserId() {
  const usersCount = TYPICAL_USER_MAX_ID - TYPICAL_USER_MIN_ID + 1;
  return TYPICAL_USER_MIN_ID + Math.floor(Math.random() * usersCount);
}

function expectStatus(response, expected) {
  return check(response, { [`status is ${expected}`]: (r) => r.status === expected });
}

// An order that the API records always answers 201, even when it is REJECTED (RFC 6.1).
function orderEndedAs(expected) {
  return (r) => r.status === STATUS_CREATED && r.json('status') === expected;
}

function showPortfolio(userId) {
  expectStatus(http.get(http.url`${API_URL}/users/${userId}/portfolio`), STATUS_OK);
}

function listOrders(userId) {
  // Half of the time the pending orders: the costliest listing query (RFC 3.5).
  const filter = exec.scenario.iterationInInstance % 2 === 0 ? '' : `?status=${ORDER_STATUS_NEW}`;
  expectStatus(http.get(http.url`${API_URL}/users/${userId}/orders${filter}`), STATUS_OK);
}

function submitOrder(userId, order, tags) {
  const body = JSON.stringify({
    clientOrderId: crypto.randomUUID(),
    instrumentId: PURCHASE_INSTRUMENT_ID,
    side: ORDER_SIDE_BUY,
    ...order,
  });
  return http.post(http.url`${API_URL}/users/${userId}/orders`, body, { headers: JSON_HEADERS, tags });
}

// Buys one share of METR by amount: the cents change from one purchase to the next.
function submitPurchase(userId, positionInIteration = 0) {
  const amount = (PURCHASE_AMOUNT_CENTS + orderNumber(positionInIteration)) / CENTS_PER_PESO;
  const purchase = submitOrder(userId, { type: ORDER_TYPE_MARKET, amount }, {});
  check(purchase, { 'the purchase is FILLED': orderEndedAs(ORDER_STATUS_FILLED) });
}

function placeAndCancelOrder(userId, positionInIteration = 0) {
  const price = (LIMIT_ORDER_PRICE_CENTS + orderNumber(positionInIteration)) / CENTS_PER_PESO;
  const order = { type: ORDER_TYPE_LIMIT, quantity: LIMIT_ORDER_QUANTITY, price };
  const placed = submitOrder(userId, order, { step: PLACE_STEP });
  const isPending = check(placed, { 'the limit order stays NEW': orderEndedAs(ORDER_STATUS_NEW) });
  if (!isPending) {
    return;
  }
  const cancelUrl = http.url`${API_URL}/users/${userId}/orders/${placed.json('id')}/cancel`;
  expectStatus(http.post(cancelUrl, null, { tags: { step: CANCEL_STEP } }), STATUS_OK);
}

// One function per measured scenario: k6 names a scenario's function, and cannot pass it arguments.

export function searchInstruments() {
  const term = SEARCH_TERMS[exec.scenario.iterationInInstance % SEARCH_TERMS.length];
  expectStatus(http.get(http.url`${API_URL}/instruments?query=${term}`), STATUS_OK);
}

export function showPortfolioOfTypicalUser() {
  showPortfolio(typicalUserId());
}

export function showPortfolioOfHeavyUser() {
  showPortfolio(HEAVY_USER_ID);
}

export function submitPurchaseOfTypicalUser() {
  submitPurchase(typicalUserId());
}

export function submitPurchaseOfHeavyUser() {
  submitPurchase(HEAVY_USER_ID);
}

export function listOrdersOfTypicalUser() {
  listOrders(typicalUserId());
}

export function listOrdersOfHeavyUser() {
  listOrders(HEAVY_USER_ID);
}

export function placeAndCancelOrderOfTypicalUser() {
  placeAndCancelOrder(typicalUserId());
}

export function placeAndCancelOrderOfHeavyUser() {
  placeAndCancelOrder(HEAVY_USER_ID);
}

export function warmUp() {
  searchInstruments();
  [typicalUserId(), HEAVY_USER_ID].forEach((userId, position) => {
    showPortfolio(userId);
    submitPurchase(userId, position);
    listOrders(userId);
    placeAndCancelOrder(userId, position);
  });
}
