#!/usr/bin/env python3
"""Independent acceptance oracle; takes the API base URL."""
import json
import sys
from urllib.request import urlopen

base = sys.argv[1].rstrip('/')
def get(path):
    with urlopen(base + path, timeout=5) as response:
        return json.load(response)
runs = get('/api/runs')['items']
assert [(r['id'], r['issues_count']) for r in runs] == [('RUN-002', 7), ('RUN-001', 6)]
s = get('/api/kkd/summary?run_id=RUN-002&unit_id=HQ')
assert s['delta'] == {'new': 3, 'persisting': 4, 'resolved': 2}
assert [m['value'] for m in s['metrics']] == [70.8, 29.2, 3]
assert [m['previous'] for m in s['metrics']] == [75.0, 25.0, 3]
assert [m['delta'] for m in s['metrics']] == [-4.2, 4.2, 0]
assert [v['current'] for v in s['issues_by_severity']] == [3, 1, 3]
first = get('/api/kkd/summary?run_id=RUN-001&unit_id=HQ')
assert first['compared_to'] is None
assert all(m['previous'] is None and m['delta'] is None and m['trend'] is None for m in first['metrics'])
assert first['delta'] == {'new': None, 'persisting': None, 'resolved': None}
assert get('/api/kkd/issues?run_id=RUN-002&unit_id=UNIT-A')['total'] == 1
assert get('/api/kkd/issues?run_id=RUN-002&unit_id=UNIT-B')['total'] == 0
print('Demo oracle passed: 24 goals, runs 6/7 issues, metrics and delta match.')
