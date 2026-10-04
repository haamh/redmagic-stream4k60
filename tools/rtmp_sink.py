"""Minimal RTMP publish sink for testing: accepts one publisher, answers connect / createStream / publish, and writes
every video and audio message to an FLV file (tags exactly as received). Usage: python rtmp_sink.py out.flv seconds"""
import socket, struct, sys, time, os

out_path = sys.argv[1]; duration = float(sys.argv[2]) if len(sys.argv) > 2 else 30

def amf_str(s): b = s.encode(); return b'\x02' + struct.pack('>H', len(b)) + b
def amf_num(n): return b'\x00' + struct.pack('>d', n)
def amf_obj(d):
    o = b'\x03'
    for k, v in d.items():
        kb = k.encode(); o += struct.pack('>H', len(kb)) + kb
        o += amf_str(v) if isinstance(v, str) else amf_num(v)
    return o + b'\x00\x00\x09'

def parse_amf(b, i=0):
    t = b[i]; i += 1
    if t == 0: return struct.unpack('>d', b[i:i+8])[0], i + 8
    if t == 1: return bool(b[i]), i + 1
    if t == 2: n = struct.unpack('>H', b[i:i+2])[0]; return b[i+2:i+2+n].decode('utf-8', 'replace'), i + 2 + n
    if t in (3, 8):
        if t == 8: i += 4
        d = {}
        while True:
            n = struct.unpack('>H', b[i:i+2])[0]; i += 2
            if n == 0 and b[i] == 9: return d, i + 1
            k = b[i:i+n].decode(); i += n
            v, i = parse_amf(b, i); d[k] = v
    if t == 5 or t == 6: return None, i
    raise ValueError('amf type %d' % t)

def send_msg(sock, csid, mtype, sid, payload, ts=0, chunk=128):
    hdr = bytes([csid & 0x3f]) + struct.pack('>I', ts)[1:] + struct.pack('>I', len(payload))[1:] + bytes([mtype]) + struct.pack('<I', sid)
    data = hdr + payload[:chunk]
    for k in range(chunk, len(payload), chunk): data += bytes([0xC0 | (csid & 0x3f)]) + payload[k:k+chunk]
    sock.sendall(data)

def recv_exact(c, n):
    b = b''
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); srv.bind(('127.0.0.1', 1935)); srv.listen(1)
print('listening', flush=True)
c, _ = srv.accept(); c.settimeout(10)
c0c1 = recv_exact(c, 1537)
c.sendall(b'\x03' + b'\x00' * 8 + os.urandom(1528) + c0c1[1:])
recv_exact(c, 1536)
out = open(out_path, 'wb'); out.write(b'FLV\x01\x05\x00\x00\x00\x09\x00\x00\x00\x00')
in_chunk = 128; prev = {}; partial = {}
start = time.time(); counts = {8: 0, 9: 0}; vbytes = 0
while time.time() - start < duration:
    try: b0 = recv_exact(c, 1)[0]
    except Exception: break
    fmt = b0 >> 6; csid = b0 & 0x3f
    if csid == 0: csid = 64 + recv_exact(c, 1)[0]
    elif csid == 1: x = recv_exact(c, 2); csid = 64 + x[0] + x[1] * 256
    p = prev.get(csid, {'ts': 0, 'len': 0, 'type': 0, 'sid': 0, 'delta': 0})
    if fmt == 0:
        h = recv_exact(c, 11); ts = int.from_bytes(h[0:3], 'big'); p = {'len': int.from_bytes(h[3:6], 'big'), 'type': h[6], 'sid': struct.unpack('<I', h[7:11])[0], 'delta': 0}
        if ts == 0xFFFFFF: ts = struct.unpack('>I', recv_exact(c, 4))[0]
        p['ts'] = ts
    elif fmt == 1:
        h = recv_exact(c, 7); d = int.from_bytes(h[0:3], 'big'); p['len'] = int.from_bytes(h[3:6], 'big'); p['type'] = h[6]
        if d == 0xFFFFFF: d = struct.unpack('>I', recv_exact(c, 4))[0]
        p['delta'] = d
        if csid not in partial: p['ts'] += d
    elif fmt == 2:
        h = recv_exact(c, 3); d = int.from_bytes(h, 'big')
        if d == 0xFFFFFF: d = struct.unpack('>I', recv_exact(c, 4))[0]
        p['delta'] = d
        if csid not in partial: p['ts'] += d
    else:
        if csid not in partial: p['ts'] += p.get('delta', 0)
    prev[csid] = p
    buf = partial.get(csid, b'')
    buf += recv_exact(c, min(in_chunk, p['len'] - len(buf)))
    if len(buf) < p['len']: partial[csid] = buf; continue
    partial.pop(csid, None)
    t = p['type']
    if t == 1: in_chunk = struct.unpack('>I', buf[:4])[0] & 0x7fffffff
    elif t == 20:
        name, i = parse_amf(buf); tid, i = parse_amf(buf, i)
        if name == 'connect':
            send_msg(c, 2, 5, 0, struct.pack('>I', 2500000)); send_msg(c, 2, 6, 0, struct.pack('>I', 2500000) + b'\x02')
            send_msg(c, 3, 20, 0, amf_str('_result') + amf_num(tid) + amf_obj({'fmsVer': 'FMS/3,0,1,123', 'capabilities': 31}) + amf_obj({'level': 'status', 'code': 'NetConnection.Connect.Success', 'description': 'ok'}))
        elif name == 'createStream':
            send_msg(c, 3, 20, 0, amf_str('_result') + amf_num(tid) + b'\x05' + amf_num(1))
        elif name == 'publish':
            send_msg(c, 5, 20, 1, amf_str('onStatus') + amf_num(0) + b'\x05' + amf_obj({'level': 'status', 'code': 'NetStream.Publish.Start', 'description': 'go'}))
        elif name in ('releaseStream', 'FCPublish'):
            send_msg(c, 3, 20, 0, amf_str('_result') + amf_num(tid) + b'\x05' + b'\x06')
        print('cmd', name, flush=True)
    elif t in (8, 9, 18):
        ts = p['ts'] & 0xFFFFFFFF
        tag = bytes([t]) + len(buf).to_bytes(3, 'big') + (ts & 0xFFFFFF).to_bytes(3, 'big') + bytes([(ts >> 24) & 0xFF]) + b'\x00\x00\x00'
        out.write(tag + buf + struct.pack('>I', 11 + len(buf)))
        if t in counts: counts[t] += 1
        if t == 9: vbytes += len(buf)
print('video', counts[9], 'audio', counts[8], 'video MB', round(vbytes / 1e6, 2), flush=True)
out.close(); c.close()
