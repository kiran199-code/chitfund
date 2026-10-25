#!/usr/bin/env python3
"""Line-JSON bridge between the Java harness and PostgreSQL (psycopg2). One connection, one open transaction at a time.
Requests : {"op":"query"|"update"|"execute"|"commit"|"rollback", "sql":..., "args":[tagged values]}
Responses: {"cols":[...], "rows":[[tagged values]]} | {"count":n} | {"ok":true} | {"error":..., "pgcode":...}
Tagged values: null | {"t":"str|int|dec|bool|date|ts","v":text}"""
import sys, json, decimal, datetime
import psycopg2

conn = psycopg2.connect(host=sys.argv[2], port=int(sys.argv[3]), user='postgres', dbname=sys.argv[1], options='-c search_path=public')
conn.autocommit = False

def dec(a):
    if a is None:
        return None
    t, v = a['t'], a['v']
    if t == 'str': return v
    if t == 'int': return int(v)
    if t == 'dec': return decimal.Decimal(v)
    if t == 'bool': return v == 'true'
    if t == 'date': return datetime.date.fromisoformat(v)
    if t == 'ts': return datetime.datetime.fromisoformat(v)
    raise ValueError(t)

def enc(v):
    if v is None: return None
    if isinstance(v, bool): return {'t': 'bool', 'v': 'true' if v else 'false'}
    if isinstance(v, int): return {'t': 'int', 'v': str(v)}
    if isinstance(v, decimal.Decimal): return {'t': 'dec', 'v': str(v)}
    if isinstance(v, datetime.datetime): return {'t': 'ts', 'v': v.isoformat()}
    if isinstance(v, datetime.date): return {'t': 'date', 'v': v.isoformat()}
    return {'t': 'str', 'v': str(v)}

def qmarks(sql):
    out, q = [], False
    for ch in sql:
        if ch == "'":
            q = not q
        if ch == '%':
            out.append('%%')
        elif ch == '?' and not q:
            out.append('%s')
        else:
            out.append(ch)
    return ''.join(out)

for line in sys.stdin:
    req = json.loads(line)
    try:
        op = req['op']
        if op == 'commit':
            conn.commit(); resp = {'ok': True}
        elif op == 'rollback':
            conn.rollback(); resp = {'ok': True}
        else:
            cur = conn.cursor()
            cur.execute(qmarks(req['sql']), [dec(a) for a in req.get('args', [])])
            if cur.description:
                resp = {'cols': [d[0] for d in cur.description], 'rows': [[enc(c) for c in r] for r in cur.fetchall()]}
            else:
                resp = {'count': cur.rowcount}
            cur.close()
    except Exception as e:
        resp = {'error': str(e).strip(), 'pgcode': getattr(e, 'pgcode', None)}
        try:
            conn.rollback()
        except Exception:
            pass
    sys.stdout.write(json.dumps(resp) + '\n'); sys.stdout.flush()
