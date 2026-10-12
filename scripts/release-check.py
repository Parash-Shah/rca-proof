"""Live release smoke check. Creates three synthetic incidents; never certifies or publishes."""
import json
import math
import os
from pathlib import Path
import sys
import time
from datetime import datetime, timezone, timedelta
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from uuid import uuid4

API = os.environ.get("RCAPROOF_CHECK_API", "http://localhost:8084")
PROM = os.environ.get("RCAPROOF_CHECK_PROMETHEUS", "http://localhost:9091")
GRAFANA = os.environ.get("RCAPROOF_CHECK_GRAFANA", "http://localhost:3001")
OUTPUT = Path(os.environ.get("RCAPROOF_CHECK_OUTPUT", "/workspace/artifacts"))


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(base, path, body=None, text=False):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=data, headers={"Content-Type": "application/json"}), timeout=20) as response:
        value = response.read().decode()
    return value if text else json.loads(value)


def wait_for(check, seconds=180):
    deadline, last = time.monotonic() + seconds, None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except Exception as error:
            last = str(error)
        time.sleep(2)
    raise TimeoutError(f"Readiness or measurement deadline exceeded: {last}")


def metrics():
    values = {}
    for line in request(API, "/v1/metrics", text=True).splitlines():
        if line and not line.startswith("#"):
            name, value = line.split()
            values[name] = float(value)
    return values


def prom_query(base, expression, proxy=False):
    path = "/api/datasources/proxy/uid/rcaproof-prometheus" if proxy else ""
    result = request(base, path + "/api/v1/query?" + urlencode({"query": expression}))
    require(result["status"] == "success", f"PromQL failed: {expression}")
    require(result["data"]["resultType"] == "vector", "Unexpected query result type")
    return result["data"]["result"]


def create_outcome(signature, category, action, state):
    started = datetime.now(timezone.utc) - timedelta(minutes=1)
    order = "release-" + uuid4().hex
    body = dict(title=f"Release smoke: synthetic {signature}", startedAt=started.isoformat(), failureSignature=signature)
    if signature == "DUPLICATE_PAYMENT":
        body["order"] = dict(orderId=order, customerId="synthetic-release-customer", amount=129.95)
    incident = request(API, "/v1/incidents", body)
    path = "/v1/incidents/" + incident["id"]
    events = []
    if signature == "DUPLICATE_PAYMENT":
        for index, kind in enumerate(["ORDER_CREATED", "MESSAGE_RECEIVED", "PAYMENT_SUCCEEDED", "WORKER_CRASHED", "MESSAGE_RECEIVED", "PAYMENT_SUCCEEDED"]):
            events.append(dict(eventId=str(uuid4()), timestamp=(started + timedelta(seconds=index)).isoformat(), service="synthetic-release-lab",
                               eventType=kind, orderId=order, traceId=order, messageId=order, result="SUCCESS",
                               metadata={"chargeId": f"synthetic-charge-{index}"} if kind == "PAYMENT_SUCCEEDED" else {}))
    else:
        denial = signature == "ACCESS_DENIED"
        events.append(dict(eventId=str(uuid4()), timestamp=started.isoformat(), service="synthetic-release-lab",
                           eventType="ACCESS_DENIED" if denial else "REQUEST_TIMEOUT", requestId=order,
                           resource="synthetic-resource", operation="GET", result="DENIED" if denial else "TIMEOUT",
                           metadata=dict(serviceHealthy="true", resourceExists="true", policyVersion="demo-read-policy-v1", hasReadPermission="false") if denial else {}))
    request(API, path + "/evidence", dict(events=events))
    request(API, path + "/investigate", dict(repetitions=2))
    wait_for(lambda: request(API, path + "/investigation")["status"] == "COMPLETED", 960)
    proof = request(API, path + "/proof")
    classification = request(API, path + "/classification")
    plan = request(API, path + "/remediations", {})
    require(proof["report"]["state"] == state, f"{signature}: wrong proof outcome")
    require(classification["decision"]["category"] == category and classification["decision"]["action"] == action, f"{signature}: wrong decision")
    if signature == "DUPLICATE_PAYMENT":
        require(plan["plan"]["recommendedCandidate"] == "IDEMPOTENCY_LEDGER", "Missing architecture remediation plan")
    else:
        require(not plan["plan"]["candidates"], "No-change/abstention case proposed a fix")
    return dict(incident=incident, proof=proof, classification=classification, plan=plan)


def run(report):
    wait_for(lambda: request(API, "/actuator/health")["status"] == "UP")
    wait_for(lambda: request(GRAFANA, "/api/health")["database"] == "ok")
    require("RCAProof" in request(API, "/", text=True), "Landing page missing")
    before = metrics()
    report["outcomes"] = []
    for case in [("DUPLICATE_PAYMENT", "ARCHITECTURE_DEFECT", "PLAN_REMEDIATION", "PROVEN"),
                 ("ACCESS_DENIED", "EXPECTED_BEHAVIOR", "NO_CODE_CHANGE", "PROVEN"),
                 ("INTERMITTENT_TIMEOUT", "UNKNOWN", "COLLECT_MORE_EVIDENCE", "INCONCLUSIVE")]:
        print("Checking " + case[0], flush=True)
        report["outcomes"].append(create_outcome(*case))
    after = metrics()
    for metric, increase in [("rcaproof_incidents_total", 3), ("rcaproof_incidents_proven", 2), ("rcaproof_incidents_inconclusive", 1)]:
        require(after[metric] >= before[metric] + increase, f"Missing measured outcome count: {metric}")
    require(after["rcaproof_experiments_total"] > before["rcaproof_experiments_total"], "No measured experiments")
    for category in ("ARCHITECTURE_DEFECT", "EXPECTED_BEHAVIOR", "UNKNOWN"):
        metric = 'rcaproof_classifications{category="' + category + '"}'
        require(after[metric] >= before.get(metric, 0) + 1, "Missing classification count")
    report["metricsBefore"], report["metricsAfter"] = before, after

    def scraped():
        require(float(prom_query(PROM, 'up{job="rcaproof"}')[0]["value"][1]) == 1, "Scrape target is down")
        current = metrics()
        for metric in current:
            rows = prom_query(PROM, metric)
            require(len(rows) == 1 and math.isclose(float(rows[0]["value"][1]), current[metric], rel_tol=1e-8, abs_tol=1e-8), f"Prometheus has not scraped {metric}")
        return current
    report["scrapedMetrics"] = wait_for(scraped, 120)
    dashboard = request(GRAFANA, "/api/dashboards/uid/rcaproof")
    require(dashboard["dashboard"]["uid"] == "rcaproof" and dashboard["meta"]["provisioned"], "Dashboard was not provisioned")
    panels = dashboard["dashboard"]["panels"]
    require(len(panels) >= 7, "Missing dashboard panels")
    report["panels"] = []
    for panel in panels:
        require(panel["datasource"]["uid"] == "rcaproof-prometheus", "Unexpected panel datasource")
        for target in panel["targets"]:
            expression = target["expr"]
            rows = prom_query(GRAFANA, expression, proxy=True)
            require(rows and all(math.isfinite(float(row["value"][1])) for row in rows), f"Empty/non-finite panel: {panel['title']}")
            report["panels"].append(dict(title=panel["title"], expression=expression, result=rows))
    print("All three outcomes and dashboard queries passed", flush=True)


if __name__ == "__main__":
    report = dict(status="RUNNING", startedAt=datetime.now(timezone.utc).isoformat(), scope="Synthetic local lab release smoke; not production accuracy or certification")
    code = 0
    try:
        run(report)
        report["status"] = "PASS"
    except Exception as error:
        report.update(status="FAIL", error=str(error))
        print(str(error), file=sys.stderr)
        code = 1
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        OUTPUT.mkdir(parents=True, exist_ok=True)
        path = OUTPUT / ("release-check-" + str(uuid4()) + ".json")
        path.write_text(json.dumps(report, indent=2))
        print("Saved " + str(path), flush=True)
    sys.exit(code)
