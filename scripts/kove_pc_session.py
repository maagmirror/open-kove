"""Kove dash session from a Windows PC. See docs/08-KOVE-PROJECTION.md.

Usage: kove_pc_session.py <seconds> join <adb> <device>   (PC joins the phone group, used for the first picture)
       kove_pc_session.py <seconds> <FiveGigahertz|TwoPointFourGigahertz>   (Windows hotspot; the dash did not join)
"""
import asyncio, base64, json, os, queue, random, re, socket, struct, subprocess, sys, threading, time, uuid
from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
import imageio_ffmpeg

ADDR = "DD:0D:30:24:1D:B1"
CHAR = "0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc"
HOST_IP = "192.168.137.1"
PKG = "net.easyconn.carman.easyride"
DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 180
BAND = sys.argv[2] if len(sys.argv) > 2 else "FiveGigahertz"
LOGFILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "session.log")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
t0 = time.time()
logf = open(LOGFILE, "w", encoding="utf-8")
secret = []
llock = threading.Lock()


def log(*a):
    s = " ".join(str(x) for x in a)
    for sec in secret:
        s = s.replace(sec, "***")
    line = f"[{time.time() - t0:7.2f}] {s}"
    with llock:
        print(line, flush=True)
        logf.write(line + "\n")
        logf.flush()


# ---------- Mobile Hotspot ----------
PS_HEAD = r"""
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(60000) | Out-Null; $task.Result }
[void][Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]
[void][Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]
$p = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$m = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($p)
"""
PS_START = PS_HEAD + r"""
if ($m.TetheringOperationalState -ne 'On') { $r = Await ($m.StartTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult]); $st = "$($r.Status) $($r.AdditionalErrorMessage)" } else { $st = 'AlreadyOn' }
Start-Sleep -Seconds 3
$c = $m.GetCurrentAccessPointConfiguration()
$mac = ''; $hostIp = ''; $iface = ''
for ($i = 0; $i -lt 20 -and -not $hostIp; $i++) {
  foreach ($a in (Get-NetAdapter -IncludeHidden | Where-Object { $_.InterfaceDescription -like '*Wi-Fi Direct Virtual*' -and $_.Status -eq 'Up' })) {
    $x = Get-NetIPAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1
    if ($x) { $hostIp = $x.IPAddress; $mac = $a.MacAddress; $iface = $a.Name }
  }
  if (-not $hostIp) { Start-Sleep -Seconds 1 }
}
@{ status=$st; state="$($m.TetheringOperationalState)"; ssid=$c.Ssid; pwd=$c.Passphrase; band="$($c.Band)"; mac=$mac; ip=$hostIp; iface=$iface } | ConvertTo-Json -Compress
"""
PS_STOP = PS_HEAD + r"""
$r = Await ($m.StopTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult]); "$($r.Status)"
"""
PS_CLIENTS = PS_HEAD + r"""
"$($m.ClientCount)"; foreach ($x in $m.GetTetheringClients()) { "$($x.MacAddress) " + (($x.HostNames | ForEach-Object { $_.DisplayName }) -join ',') }
"""


PS_ACTION = r"""
$asAct = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]
"""
PS_GETCFG = PS_HEAD + r"""
$c = $m.GetCurrentAccessPointConfiguration()
@{ ssid=$c.Ssid; pwd=$c.Passphrase; band="$($c.Band)" } | ConvertTo-Json -Compress
"""
PS_SETCFG = PS_HEAD + PS_ACTION + r"""
if ($m.TetheringOperationalState -ne 'Off') { [void](Await ($m.StopTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])); Start-Sleep -Seconds 2 }
$c = $m.GetCurrentAccessPointConfiguration()
$c.Ssid = $env:KV_SSID; $c.Passphrase = $env:KV_PWD; $c.Band = $env:KV_BAND
$t = $asAct.Invoke($null, @($m.ConfigureAccessPointAsync($c))); $t.Wait(30000) | Out-Null
$n = $m.GetCurrentAccessPointConfiguration(); "OK ssid=$($n.Ssid) band=$($n.Band)"
"""
BACKUP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hotspot_backup.json")


def set_cfg(ssid, pwd, band):
    out, err = ps(PS_SETCFG, env={**os.environ, "KV_SSID": ssid, "KV_PWD": pwd, "KV_BAND": band})
    log("[hotspot] configure:", out[-80:], err[:300])
    return out.endswith(f"ssid={ssid} band={band}")


def ps(script, timeout=90, env=None):
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, timeout=timeout, env=env)
    return r.stdout.decode("utf-8", "replace").strip(), r.stderr.decode("utf-8", "replace").strip()


def hotspot_start():
    out, err = ps(PS_START)
    m = re.search(r"\{.*\}", out, re.S)
    if not m:
        log("[hotspot] start output unparseable:", out[:300], err[:300])
        return None
    info = json.loads(m.group(0))
    if info.get("pwd"):
        secret.append(info["pwd"])
    log(f"[hotspot] status={info['status']} state={info['state']} ssid={info['ssid']} band={info['band']} "
        f"mac={info['mac']} ip={info['ip']} iface={info['iface']}")
    if info["state"] != "On" or not info["ip"]:
        return info if False else {"_failed": True}
    global HOST_IP
    HOST_IP = info["ip"]
    return info


# ---------- BLE frames ----------
def frame(cmd, p=b""):
    l = len(p) + 4
    x = 0x24 ^ cmd ^ l
    for b in p:
        x ^= b
    return bytes([0x24, cmd, l]) + p + bytes([x, 0x0A])


ALPHA = "mLV6J0=uApH7gDztwaKlZ5/UQXqjfPShRoGiBnYy9cb2FC81E3kd4vMeO+sITxNrW"


def obf(data):
    out = []
    for i in range(0, len(data) - len(data) % 3, 3):
        b0, b1, b2 = data[i] ^ 0x66, data[i + 1], data[i + 2]
        out += [ALPHA[b0 >> 2], ALPHA[((b0 & 3) << 4) | (b1 >> 4)], ALPHA[((b1 & 15) << 2) | (b2 >> 6)], ALPHA[b2 & 63]]
    rem = len(data) % 3
    i = len(data) - rem
    if rem == 1:
        b0 = data[i] ^ 0x66
        out += [ALPHA[b0 >> 2], ALPHA[(b0 & 3) << 4], "W", "W"]
    elif rem == 2:
        b0, b1 = data[i] ^ 0x66, data[i + 1]
        out += [ALPHA[b0 >> 2], ALPHA[((b0 & 3) << 4) | (b1 >> 4)], ALPHA[(b1 & 15) << 2], "W"]
    return "".join(out).encode()


class BleFrames:
    def __init__(self):
        self.buf = b""
        self.q = asyncio.Queue()

    def feed(self, _, d):
        self.buf += bytes(d)
        while True:
            i = self.buf.find(b"$")
            if i < 0:
                self.buf = b""
                return
            self.buf = self.buf[i:]
            if len(self.buf) < 3:
                return
            cmd, ln = self.buf[1], self.buf[2]
            total = ln + 1
            if ln < 4:
                self.buf = self.buf[1:]
                continue
            if len(self.buf) < total:
                return
            p = self.buf[3:3 + ln - 4]
            x = 0x24 ^ cmd ^ ln
            for b in p:
                x ^= b
            if self.buf[total - 2] == x and self.buf[total - 1] == 0x0A:
                self.q.put_nowait((cmd, p))
                self.buf = self.buf[total:]
            else:
                self.buf = self.buf[1:]


# ---------- PXC ----------
class Pxc:
    def __init__(self):
        self.locks = {}
        self.car_data = None
        self.car_ctrl = None
        self.running = True
        self.hb_started = False
        self.live = 0
        self.ever = False
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=1024)
        self.pub_b64 = base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        self.phone_uuid = str(uuid.uuid4())
        self.w, self.h = 800, 352
        self.video = None
        self.frames = 0
        self.switch_sent = False
        self.cmd_seen = {}
        self.servers = []

    def sign(self, huid):
        nums = self.key.private_numbers()
        n, d = self.key.public_key().public_numbers().n, nums.d
        k = (n.bit_length() + 7) // 8
        m = huid.encode()
        em = b"\x00\x01" + b"\xff" * (k - len(m) - 3) + b"\x00" + m
        return base64.b64encode(pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")).decode()

    def send(self, sock, cmd, body=b""):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, separators=(",", ":")).encode()
        total = 16 + len(body)
        hdr = struct.pack("<IIII", cmd & 0xFFFFFFFF, total, (cmd ^ total) & 0xFFFFFFFF, 0)
        lock = self.locks.setdefault(sock, threading.Lock())
        with lock:
            sock.sendall(hdr + body)

    def media_send(self, sock, cmd, body=b""):
        lock = self.locks.setdefault(sock, threading.Lock())
        with lock:
            sock.sendall(struct.pack("<hHi", cmd, len(body), 0) + body)

    def listen(self):
        for port in (10922, 10921, 10920):
            s = socket.socket()
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((HOST_IP, port))
            s.listen(50)
            self.servers.append(s)
            threading.Thread(target=self.accept, args=(s, port), daemon=True).start()
        log("[pxc] listening", HOST_IP, "10920-10922")

    def accept(self, srv, port):
        while self.running:
            try:
                c, addr = srv.accept()
            except OSError:
                return
            log(f"[:{port}] <<< bike connected from {addr}")
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.live += 1
            self.ever = True
            threading.Thread(target=self.conn, args=(c, port), daemon=True).start()

    def conn(self, c, port):
        try:
            if port == 10922:
                self.ctrl_loop(c)
            else:
                self.media_loop(c, port)
        except Exception as e:
            log(f"[:{port}] closed: {e!r}")
        finally:
            self.live -= 1
            try:
                c.close()
            except OSError:
                pass

    @staticmethod
    def rd(c, n):
        b = b""
        while len(b) < n:
            d = c.recv(n - len(b))
            if not d:
                raise ConnectionError("peer closed")
            b += d
        return b

    def ctrl_loop(self, c):
        while self.running:
            h = self.rd(c, 16)
            cmd, total, magic, _ = struct.unpack("<IIII", h)
            if cmd ^ total != magic:
                raise ValueError(f"bad magic cmd={cmd:#x} total={total}")
            p = self.rd(c, total - 16) if total > 16 else b""
            self.seen("PXC", cmd, p)
            self.ctrl(c, cmd, p)

    def seen(self, plane, cmd, p):
        k = (plane, cmd)
        n = self.cmd_seen[k] = self.cmd_seen.get(k, 0) + 1
        if n <= 3 or n % 100 == 0:
            log(f"[rx {plane}] cmd={cmd:#x} #{n} len={len(p)} {p[:700].decode('utf-8', 'replace')!r}")

    def ctrl(self, c, cmd, p):
        if cmd == 0x10000:
            self.send(c, 0x10001)
            self.car_ctrl = c
        elif cmd == 0x20000:
            self.send(c, 0x20001)
            self.car_data = c
            self.burst(c)
        elif cmd == 0x10010:
            self.client_info(c, p)
        elif cmd == 0x10690:
            self.send(c, 0x10691)
        elif cmd == 0x10410:
            self.send(c, 0x10411, {"status": True})
        elif cmd == 0x103E0:
            self.send(c, 0x103E1)
            result = {"isOk": True, "errCode": 0, "errMsg": "", "id": "", "client_set": ""}
            threading.Thread(target=self.sn_result, args=(result,), daemon=True).start()
        elif cmd == 0x70000000:
            self.send(c, 0x70000001)
        elif cmd in (0x70000001, 0x201C1):
            pass
        elif cmd == 0x80000010:
            log("[pxc] dash UNSUPPORT")
        elif cmd & 1:
            pass
        else:
            self.send(c, cmd + 1)

    def sn_result(self, result):
        time.sleep(1.6)
        s = self.car_data or self.car_ctrl
        log("[pxc] -> CHECK_SN_RESULT on", "P2C" if s is self.car_data else "C2P", result)
        self.send(s, 0x201C0, result)
        time.sleep(0.02)
        self.background = True
        log("[pxc] APPSTATUS now BACKGROUND 0x20030 (vendor sequence)")

    def client_info(self, c, p):
        info = json.loads(p.decode("utf-8", "replace"))
        huid = info.get("HUID") or info.get("huid") or ""
        reply = {
            "pxcVersion": "1.0.2", "phoneUUID": self.phone_uuid, "phoneBrand": "Nothing", "phoneModel": "A065",
            "phoneOsVersion": "35", "phoneOs": "Android", "package": PKG, "versionCode": 38, "token": 0,
            "pubkey": self.pub_b64, "encryptedHUID": self.sign(huid) if huid else "",
            "bluetoothName": "OpenCfMoto", "supportH264IFrame": True, "supportFunction": 8192,
            "appVersionFingerPrint": "V:5.2.0(38)-fcb9ca2f99-ONLINE", "speechEngineType": 2,
        }
        log("[pxc] -> CLIENT_INFO reply (identity:", PKG, ")")
        self.send(c, 0x10011, reply)

    def appstatus(self, c):
        self.send(c, 0x20030 if getattr(self, "background", False) else 0x20020, {"displayRotation": 0, "width": 1080, "height": 2412,
                               "enableAccessibility": False, "enableAOAHid": True, "mode": 2})

    def burst(self, c):
        log("[pxc] -> opening burst on P2C")
        self.send(c, 0x20490, {"status": 0})
        self.send(c, 0x20310, {"ismute": False, "isLogin": False})
        self.send(c, 0x20050)
        self.appstatus(c)
        self.appstatus(c)
        self.send(c, 0x204B0)
        if not self.hb_started:
            self.hb_started = True
            threading.Thread(target=self.hb, daemon=True).start()

    def hb(self):
        n = 0
        while self.running:
            time.sleep(1)
            s = self.car_data
            if s is None:
                continue
            try:
                self.appstatus(s)
                if n >= 8:
                    self.send(s, 0x205A0, {"isMaskVisible": False})
                n += 1
            except OSError as e:
                log("[hb] stopped:", repr(e))
                self.car_data = None

    def media_loop(self, c, port):
        while self.running:
            h = self.rd(c, 8)
            cmd, ln, tok = struct.unpack("<hHi", h)
            p = self.rd(c, ln) if ln else b""
            if cmd not in (114, 64):
                self.seen(f"M{port}", cmd, p)
            else:
                k = (f"M{port}", cmd)
                self.cmd_seen[k] = self.cmd_seen.get(k, 0) + 1
            self.media(c, port, cmd, p)

    def media(self, c, port, cmd, p):
        if cmd == 16:
            w, h = struct.unpack_from("<HH", p, 0)
            fps = struct.unpack_from("<i", p, 4)[0] if len(p) >= 8 else 0
            enc = struct.unpack_from("<i", p, 8)[0] if len(p) >= 12 else 2
            ext = p[29] if len(p) >= 30 else 0
            self.w, self.h = w & 0xFFF0, h & 0xFFF0
            log(f"[media] CONFIG_CAPTURE w={w} h={h} fps={fps} enc={enc} ext={ext} hex={p.hex()}")
            body = struct.pack("<iHH", enc or 2, self.w, self.h) + (bytes([ext]) if ext else b"")
            self.media_send(c, 17, body)
        elif cmd == 48:
            self.media_send(c, 49, struct.pack("<ii", 5002, 1))
        elif cmd == 64:
            self.media_send(c, 65)
        elif cmd == 96:
            try:
                req = json.loads(p.decode())
            except Exception:
                req = {}
            rep = {}
            if "viewAreaConfig" in req:
                rep["viewAreaConfig"] = {"state": 0}
            rep["supportFunction"] = req.get("supportFunction", 0)
            self.media_send(c, 97, json.dumps(rep, separators=(",", ":")).encode())
        elif cmd == 128:
            self.media_send(c, 129)
        elif cmd == 112:
            log(f"[media] *** DATA_START *** {self.w}x{self.h}")
            self.start_video()
            if self.car_data:
                self.send(self.car_data, 0x20050)
            self.media_send(c, 113)
            if self.car_data:
                self.appstatus(self.car_data)
            if not self.switch_sent:
                self.switch_sent = True
                threading.Thread(target=self.switch_front, daemon=True).start()
        elif cmd == 114:
            au = None
            if self.video:
                try:
                    au = self.video.q.get(timeout=1.5)
                except queue.Empty:
                    pass
            data = au or b""
            lock = self.locks.setdefault(c, threading.Lock())
            with lock:
                c.sendall(struct.pack("<i", len(data)) + data)
            if au:
                self.frames += 1
                if self.frames <= 5 or self.frames % 200 == 0:
                    log(f"[media] frame #{self.frames} {len(au)}b nals={nal_types(au)}")
            else:
                log("[media] empty frame")
        elif cmd == 32:
            log("[media] touch", p[:8].hex())
        else:
            log(f"[media] unhandled cmd={cmd} len={len(p)} {p[:64].hex()}")

    def switch_front(self):
        time.sleep(10)
        if self.car_data:
            log("[pxc] -> SWITCH_FRONT")
            self.send(self.car_data, 0x201D0)

    def start_video(self):
        if self.video:
            self.video.stop()
        self.video = Video(self.w, self.h)

    def probe(self, car):
        body = json.dumps({"phoneType": "Android", "packageName": PKG}, separators=(",", ":")).encode()
        for i in range(5):
            try:
                s = socket.socket()
                s.settimeout(5)
                s.bind((HOST_IP, 0))
                s.connect((car, 10930))
                self.send(s, 0x70000010, body)
                h = self.rd(s, 16)
                cmd, total, _, _ = struct.unpack("<IIII", h)
                p = self.rd(s, total - 16) if total > 16 else b""
                s.close()
                log(f"[probe] #{i + 1} <- {cmd:#x} {p.decode('utf-8', 'replace')}")
                if cmd == 0x70000011 and b"true" in p:
                    return True
            except Exception as e:
                log(f"[probe] #{i + 1} failed: {e!r}")
            time.sleep(2)
        return False

    def stop(self):
        self.running = False
        if self.video:
            self.video.stop()
        for s in self.servers:
            try:
                s.close()
            except OSError:
                pass


def nal_types(au):
    return [au[m.end()] & 0x1F for m in re.finditer(b"\x00\x00\x01", au) if m.end() < len(au)]


class Video:
    """ffmpeg libx264 test card -> Annex-B AUs holding only slice/IDR/SPS/PPS NALs, like the vendor stream."""

    def __init__(self, w, h):
        self.q = queue.Queue(maxsize=8)
        font = "C\\:/Windows/Fonts/arialbd.ttf"
        vf = (f"drawtext=fontfile='{font}':text='OPENCFMOTO DESDE LA PC':fontcolor=white:fontsize=44:"
              f"x=(w-tw)/2:y=(h-th)/2:box=1:boxcolor=black@0.75:boxborderw=12")
        cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi",
               "-i", f"testsrc2=size={w}x{h}:rate=36", "-vf", vf, "-pix_fmt", "yuv420p", "-c:v", "libx264",
               "-preset", "veryfast", "-tune", "zerolatency", "-profile:v", "high", "-level", "3.0",
               "-threads", "1", "-g", "108", "-bf", "0", "-b:v", "4194k", "-maxrate", "4194k", "-bufsize", "2000k",
               "-x264-params", "slices=1:aud=0:repeat-headers=1", "-f", "h264", "-"]
        self.p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        threading.Thread(target=self.read, daemon=True).start()
        threading.Thread(target=self.err, daemon=True).start()
        log(f"[video] ffmpeg started {w}x{h}@36 High@3.0 4194kbps")

    def err(self):
        for line in self.p.stderr:
            log("[ffmpeg]", line.decode("utf-8", "replace").strip())

    def read(self):
        buf = b""
        cur = b""
        sc = re.compile(b"\x00\x00\x01")
        while True:
            d = self.p.stdout.read1(65536)
            if not d:
                return
            buf += d
            idx = [m.start() for m in sc.finditer(buf)]
            for a, b in zip(idx, idx[1:]):
                end = b - 1 if buf[b - 1] == 0 else b
                nal = b"\x00" + buf[a:end]
                t = nal[4] & 0x1F
                if t in (7, 8):
                    cur += nal
                elif t in (1, 5):
                    au = cur + nal
                    cur = b""
                    try:
                        self.q.put_nowait(au)
                    except queue.Full:
                        if t == 5:
                            self.q.get_nowait()
                            self.q.put_nowait(au)
            if idx:
                buf = buf[idx[-1]:]

    def stop(self):
        try:
            self.p.kill()
        except OSError:
            pass


# ---------- Session ----------
async def session():
    pxc = Pxc()
    hotspot_on = False
    original = None
    try:
        if os.path.exists(BACKUP):
            original = json.load(open(BACKUP, encoding="utf-8"))
        else:
            out, err = await asyncio.to_thread(ps, PS_GETCFG)
            original = json.loads(re.search(r"\{.*\}", out, re.S).group(0))
            json.dump(original, open(BACKUP, "w", encoding="utf-8"))
        if original:
            secret.append(original["pwd"])
        d = await BleakScanner.find_device_by_address(ADDR, timeout=30)
        if not d:
            log("[ble] dash not found")
            return
        rx = BleFrames()
        async with BleakClient(d, timeout=30) as c:
            ch = c.services.get_characteristic(CHAR)
            mx = 10  # vendor writes ~10-byte chunks

            async def tx(name, f):
                log("[ble] ->", name)
                for i in range(0, len(f), mx):
                    await c.write_gatt_char(ch, f[i:i + mx], response=True)

            await c.start_notify(ch, rx.feed)
            log("[ble] connected, subscribed")
            await asyncio.sleep(3)
            await tx("CLIENT_INFO", frame(0x30, json.dumps(
                {"phoneType": 0, "phoneID": str(uuid.uuid4())}, separators=(",", ":")).encode()))
            cmd, p = await asyncio.wait_for(rx.q.get(), 10)
            log(f"[ble] handshake cmd={cmd:#x} len={len(p)}")
            pass

            hotspot_on = True
            ap_name = "Easyconn_AP-" + "".join(random.choice("1234567890") for _ in range(4))
            ap_pass = "".join(random.choice("abcdefghijklmnopqrstuvwxyz1234567890") for _ in range(12))
            secret.append(ap_pass)
            if not await asyncio.to_thread(set_cfg, ap_name, ap_pass, BAND):
              log("[hotspot] could not apply vendor-style configuration; aborting")
              return
            info = await asyncio.to_thread(hotspot_start)
            if not info or info.get("_failed"):
                log("[hotspot] did not come up with an IP; aborting")
                return
            pxc.listen()

            name = b"OpenCfMoto".ljust(32, b"\0")
            mask = socket.inet_aton("255.255.255.0")
            await tx("EXTEND_CLIENT_INFO", frame(0x54, name + b"\x01" + mask + b"\0" * 4))
            await asyncio.sleep(0.5)

            ap = json.dumps({"ssid": info["ssid"], "pwd": info["pwd"], "auth": "WPA2",
                             "mac": ""},
                            separators=(",", ":")).encode()
            car = None
            sent = 0
            next_req = 0
            next_ap = None
            next_clients = 0
            deadline = time.time() + 70
            while time.time() < deadline and not car:
                now = time.time()
                if next_ap is None and now >= next_req:
                    await tx("REQUEST_BUILD_NET", frame(0x50))
                    next_req = now + 3
                if now >= next_clients:
                    next_clients = now + 10
                    out, _ = await asyncio.to_thread(ps, PS_CLIENTS)
                    log("[hotspot] clients:", " | ".join(out.splitlines()))
                if next_ap is not None and now >= next_ap and sent < 1:
                    ob = sent % 2 == 1
                    sent += 1
                    await tx(f"NOTIFY_AP_INFO try {sent} {'obfuscated' if ob else 'plain'}",
                             frame(0x52, obf(ap) if ob else ap))
                    next_ap = now + 50
                try:
                    cmd, p = await asyncio.wait_for(rx.q.get(), 1)
                except asyncio.TimeoutError:
                    continue
                txt = p.decode("utf-8", "replace")
                log(f"[ble] rx cmd={cmd:#x} {txt[:200]!r}")
                if cmd == 0x50:
                    m = re.search(r'"status"\s*:\s*(-?\d+)', txt)
                    st = int(m.group(1)) if m else None
                    if st in (1, 2) and next_ap is None:
                        next_ap = 0
                elif cmd == 0x53:
                    try:
                        car = json.loads(txt)["data"][0]["ip"]
                    except Exception as e:
                        log("[ble] bad car-net:", repr(e))
            if not car:
                log("[ble] dash never reported an IP")
                return
            log("[ble] *** dash joined, ip =", car)
            ok = await asyncio.to_thread(pxc.probe, car)
            log("[probe] accepted" if ok else "[probe] NOT accepted")
            end = time.time() + DURATION
            last = 0
            reprobes = 0
            while time.time() < end:
                await asyncio.sleep(1)
                if time.time() - last >= 10:
                    last = time.time()
                    census = {f"{k[0]}:{k[1]:#x}": v for k, v in pxc.cmd_seen.items() if k[0].startswith("M")}
                    log(f"[status] live={pxc.live} frames={pxc.frames} ble={c.is_connected} media={census}")
                if pxc.ever and pxc.live == 0 and reprobes < 5:
                    reprobes += 1
                    log(f"[pxc] all sockets closed, re-probing ({reprobes})")
                    await asyncio.sleep(2)
                    await asyncio.to_thread(pxc.probe, car)
                    await asyncio.sleep(3)
    except Exception as e:
        log("[session] ERROR", repr(e))
    finally:
        log(f"[session] end frames={pxc.frames}")
        pxc.stop()
        if hotspot_on:
            out, err = await asyncio.to_thread(ps, PS_STOP)
            log("[hotspot] stop:", out[-60:], err[:200])
        if original and await asyncio.to_thread(set_cfg, original["ssid"], original["pwd"], original["band"]):
            os.remove(BACKUP)
            log("[hotspot] original configuration restored")
        elif original:
            log("[hotspot] !! RESTORE FAILED, backup kept at", BACKUP)



def join_phone_group():
    """PC joins the phone's Wi-Fi Direct group as a legacy client and drives PXC directly."""
    global HOST_IP
    adb, dev = sys.argv[3], sys.argv[4]
    xml = subprocess.run([adb, "-s", dev, "exec-out", "run-as", "dev.zanderp.opencfmoto", "cat",
                          "shared_prefs/opencfmoto.hotspot.xml"], capture_output=True).stdout.decode()
    ssid = re.search(r'name="ap_ssid">([^<]+)', xml)
    pw = re.search(r'name="ap_pass">([^<]+)', xml)
    if not ssid or not pw:
        log("[join] could not read group credentials from the app")
        return
    ssid, pw = ssid[1], pw[1]
    secret.append(pw)
    log("[join] phone group:", ssid)
    prof = os.path.join(os.path.dirname(LOGFILE), "kove_profile.xml")
    with open(prof, "w", encoding="utf-8") as f:
        f.write(f"""<?xml version="1.0"?><WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1"><name>{ssid}</name><SSIDConfig><SSID><name>{ssid}</name></SSID></SSIDConfig><connectionType>ESS</connectionType><connectionMode>manual</connectionMode><MSM><security><authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption><sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{pw}</keyMaterial></sharedKey></security></MSM></WLANProfile>""")
    pxc = Pxc()
    try:
        r = subprocess.run(["netsh", "wlan", "add", "profile", f"filename={prof}", "user=current"], capture_output=True)
        log("[join] add profile rc", r.returncode)
        os.remove(prof)
        for attempt in range(6):
            subprocess.run(["netsh", "wlan", "connect", f"name={ssid}", f"ssid={ssid}", "interface=Wi-Fi"], capture_output=True)
            for _ in range(12):
                time.sleep(2)
                out, _e = ps("(Get-NetIPAddress -InterfaceAlias 'Wi-Fi' -AddressFamily IPv4 -ErrorAction SilentlyContinue).IPAddress", 30)
                if out.startswith("192.168.49."):
                    HOST_IP = out.strip()
                    break
            if HOST_IP.startswith("192.168.49."):
                break
            log("[join] no address yet, retry", attempt + 1)
        if not HOST_IP.startswith("192.168.49."):
            log("[join] PC never joined the phone group")
            return
        log("[join] PC on phone group, ip =", HOST_IP)
        pxc.listen()
        car = "192.168.49.139"
        ok = pxc.probe(car)
        log("[probe] accepted" if ok else "[probe] NOT accepted")
        end = time.time() + DURATION
        last = 0
        reprobes = 0
        while time.time() < end:
            time.sleep(1)
            if time.time() - last >= 10:
                last = time.time()
                census = {f"{k[0]}:{k[1]:#x}": v for k, v in pxc.cmd_seen.items() if k[0].startswith("M")}
                log(f"[status] live={pxc.live} frames={pxc.frames} media={census}")
            if pxc.ever and pxc.live == 0 and reprobes < 5:
                reprobes += 1
                log(f"[pxc] all sockets closed, re-probing ({reprobes})")
                time.sleep(2)
                pxc.probe(car)
                time.sleep(3)
    except Exception as e:
        log("[join] ERROR", repr(e))
    finally:
        log(f"[session] end frames={pxc.frames}")
        pxc.stop()
        subprocess.run(["netsh", "wlan", "disconnect", "interface=Wi-Fi"], capture_output=True)
        r = subprocess.run(["netsh", "wlan", "delete", "profile", f"name={ssid}"], capture_output=True)
        log("[join] Wi-Fi disconnected, temporary profile removed rc", r.returncode)


if BAND == "join":
    join_phone_group()
else:
    asyncio.run(session())
