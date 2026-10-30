// سرور بدون وابستگی خارجی (فقط Node 22.13+)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// --- بارگذاری .env ساده ---
const envFile = path.join(__dirname, '.env');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2];
  }
}
const PORT = Number(process.env.PORT || 3000);
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.ADMIN_PASS || '';
const JWT_SECRET = process.env.JWT_SECRET || '';
if (!ADMIN_PASS || !JWT_SECRET) {
  console.error('ADMIN_PASS و JWT_SECRET باید در .env تنظیم شوند.');
  process.exit(1);
}

// --- دیتابیس ---
const db = new DatabaseSync(process.env.DB_PATH || path.join(__dirname, 'data.db'));
db.exec(`
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL,
  pass_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL DEFAULT 0,
  disabled INTEGER NOT NULL DEFAULT 0,
  note TEXT DEFAULT ''
);
CREATE TABLE IF NOT EXISTS configs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('wireguard','v2ray')),
  content TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
`);
const DEFAULT_PLANS = {
  telegram_url: 'https://t.me/your_username',
  plans: [
    { title: 'یک ماهه', price: '۱۰۰,۰۰۰ تومان', desc: 'تانل مخصوص بازی' },
    { title: 'سه ماهه', price: '۲۵۰,۰۰۰ تومان', desc: 'تانل مخصوص بازی' }
  ]
};
if (!db.prepare('SELECT 1 FROM settings WHERE key=?').get('plans')) {
  db.prepare('INSERT INTO settings(key,value) VALUES(?,?)').run('plans', JSON.stringify(DEFAULT_PLANS));
}

// --- ابزارهای امنیتی ---
const b64u = (b) => Buffer.from(b).toString('base64url');
function hashPass(pw) {
  const salt = crypto.randomBytes(16);
  const h = crypto.scryptSync(pw, salt, 64);
  return `${salt.toString('hex')}:${h.toString('hex')}`;
}
function checkPass(pw, stored) {
  const [s, h] = stored.split(':');
  const calc = crypto.scryptSync(pw, Buffer.from(s, 'hex'), 64);
  const real = Buffer.from(h, 'hex');
  return real.length === calc.length && crypto.timingSafeEqual(calc, real);
}
function sign(payload, ttlSec) {
  const body = { ...payload, exp: Math.floor(Date.now() / 1000) + ttlSec };
  const head = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const data = `${head}.${b64u(JSON.stringify(body))}`;
  const sig = crypto.createHmac('sha256', JWT_SECRET).update(data).digest('base64url');
  return `${data}.${sig}`;
}
function verify(token) {
  try {
    const [h, p, s] = token.split('.');
    const sig = crypto.createHmac('sha256', JWT_SECRET).update(`${h}.${p}`).digest('base64url');
    const a = Buffer.from(s), b = Buffer.from(sig);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
    const body = JSON.parse(Buffer.from(p, 'base64url').toString());
    return body.exp > Date.now() / 1000 ? body : null;
  } catch { return null; }
}
const sha = (s) => crypto.createHash('sha256').update(String(s)).digest();

// محدودیت تلاش ورود
const attempts = new Map();
function tooMany(ip) {
  const now = Date.now();
  const a = (attempts.get(ip) || []).filter((t) => now - t < 10 * 60 * 1000);
  attempts.set(ip, a);
  return a.length >= 10;
}
const noteFail = (ip) => attempts.set(ip, [...(attempts.get(ip) || []), Date.now()]);

// --- کمک‌ها ---
const send = (res, code, obj) => {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
};
const readBody = (req) => new Promise((resolve, reject) => {
  let d = '';
  req.on('data', (c) => { d += c; if (d.length > 1e6) { reject(new Error('big')); req.destroy(); } });
  req.on('end', () => { try { resolve(d ? JSON.parse(d) : {}); } catch { reject(new Error('json')); } });
});
const authOf = (req) => {
  const m = (req.headers.authorization || '').match(/^Bearer (.+)$/);
  return m ? verify(m[1]) : null;
};
const nowSec = () => Math.floor(Date.now() / 1000);
const getPlans = () => JSON.parse(db.prepare('SELECT value FROM settings WHERE key=?').get('plans').value);

// --- مسیرها ---
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  const ip = req.socket.remoteAddress || '';
  try {
    if (req.method === 'GET' && (p === '/admin' || p === '/admin/')) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(fs.readFileSync(path.join(__dirname, 'public', 'admin.html')));
    }

    // ورود کاربر اپ
    if (req.method === 'POST' && p === '/api/login') {
      if (tooMany(ip)) return send(res, 429, { error: 'تلاش بیش از حد، بعدا امتحان کنید' });
      const { username, password } = await readBody(req);
      const u = db.prepare('SELECT * FROM users WHERE username=?').get(String(username || ''));
      if (!u || !checkPass(String(password || ''), u.pass_hash)) {
        noteFail(ip);
        return send(res, 401, { error: 'نام کاربری یا رمز اشتباه است' });
      }
      if (u.disabled) return send(res, 403, { error: 'حساب غیرفعال است' });
      return send(res, 200, { token: sign({ role: 'user', uid: u.id }, 30 * 86400) });
    }

    // اطلاعات حساب + کانفیگ‌ها (فقط اگر اشتراک معتبر باشد)
    if (req.method === 'GET' && p === '/api/me') {
      const a = authOf(req);
      if (!a || a.role !== 'user') return send(res, 401, { error: 'unauthorized' });
      const u = db.prepare('SELECT * FROM users WHERE id=?').get(a.uid);
      if (!u || u.disabled) return send(res, 403, { error: 'حساب غیرفعال است' });
      const active = u.expires_at > nowSec();
      const configs = active
        ? db.prepare('SELECT id,name,type,content FROM configs WHERE user_id=?').all(u.id)
        : [];
      return send(res, 200, {
        username: u.username,
        expires_at: u.expires_at,
        active,
        days_left: active ? Math.ceil((u.expires_at - nowSec()) / 86400) : 0,
        configs
      });
    }

    if (req.method === 'GET' && p === '/api/plans') return send(res, 200, getPlans());

    // ورود ادمین
    if (req.method === 'POST' && p === '/api/admin/login') {
      if (tooMany(ip)) return send(res, 429, { error: 'تلاش بیش از حد' });
      const { username, password } = await readBody(req);
      const ok = crypto.timingSafeEqual(sha(username), sha(ADMIN_USER)) &&
                 crypto.timingSafeEqual(sha(password), sha(ADMIN_PASS));
      if (!ok) { noteFail(ip); return send(res, 401, { error: 'اطلاعات اشتباه است' }); }
      return send(res, 200, { token: sign({ role: 'admin' }, 12 * 3600) });
    }

    // --- فقط ادمین ---
    if (p.startsWith('/api/admin/')) {
      const a = authOf(req);
      if (!a || a.role !== 'admin') return send(res, 401, { error: 'unauthorized' });

      if (req.method === 'GET' && p === '/api/admin/users') {
        return send(res, 200, db.prepare(`SELECT u.id,u.username,u.expires_at,u.disabled,u.note,
          (SELECT COUNT(*) FROM configs c WHERE c.user_id=u.id) AS config_count
          FROM users u ORDER BY u.id DESC`).all());
      }
      if (req.method === 'POST' && p === '/api/admin/users') {
        const { username, password, days = 0, note = '' } = await readBody(req);
        if (!username || !password || String(password).length < 6)
          return send(res, 400, { error: 'نام کاربری و رمز (حداقل ۶ حرف) لازم است' });
        try {
          const exp = nowSec() + Number(days) * 86400;
          const r = db.prepare('INSERT INTO users(username,pass_hash,expires_at,note) VALUES(?,?,?,?)')
            .run(String(username), hashPass(String(password)), exp, String(note));
          return send(res, 200, { id: Number(r.lastInsertRowid) });
        } catch { return send(res, 409, { error: 'این نام کاربری وجود دارد' }); }
      }
      let m;
      if ((m = p.match(/^\/api\/admin\/users\/(\d+)$/))) {
        const id = Number(m[1]);
        if (req.method === 'DELETE') {
          db.prepare('DELETE FROM configs WHERE user_id=?').run(id);
          db.prepare('DELETE FROM users WHERE id=?').run(id);
          return send(res, 200, { ok: true });
        }
        if (req.method === 'PATCH') {
          const b = await readBody(req);
          const u = db.prepare('SELECT * FROM users WHERE id=?').get(id);
          if (!u) return send(res, 404, { error: 'not found' });
          if (b.add_days !== undefined) {
            // عدد منفی = کم کردن روز؛ اگر منقضی شده، از الان حساب می‌شود
            const exp = Math.max(0, Math.max(u.expires_at, nowSec()) + Number(b.add_days) * 86400);
            db.prepare('UPDATE users SET expires_at=? WHERE id=?').run(exp, id);
          }
          if (b.disabled !== undefined) db.prepare('UPDATE users SET disabled=? WHERE id=?').run(b.disabled ? 1 : 0, id);
          if (b.password) {
            if (String(b.password).length < 6) return send(res, 400, { error: 'رمز حداقل ۶ حرف' });
            db.prepare('UPDATE users SET pass_hash=? WHERE id=?').run(hashPass(String(b.password)), id);
          }
          if (b.note !== undefined) db.prepare('UPDATE users SET note=? WHERE id=?').run(String(b.note), id);
          return send(res, 200, { ok: true });
        }
      }
      if ((m = p.match(/^\/api\/admin\/users\/(\d+)\/configs$/))) {
        const id = Number(m[1]);
        if (req.method === 'GET') return send(res, 200, db.prepare('SELECT * FROM configs WHERE user_id=?').all(id));
        if (req.method === 'POST') {
          const { name, type, content } = await readBody(req);
          if (!name || !['wireguard', 'v2ray'].includes(type) || !content)
            return send(res, 400, { error: 'نام، نوع و محتوا لازم است' });
          if (!db.prepare('SELECT 1 FROM users WHERE id=?').get(id)) return send(res, 404, { error: 'کاربر پیدا نشد' });
          const r = db.prepare('INSERT INTO configs(user_id,name,type,content) VALUES(?,?,?,?)')
            .run(id, String(name), type, String(content).trim());
          return send(res, 200, { id: Number(r.lastInsertRowid) });
        }
      }
      if ((m = p.match(/^\/api\/admin\/configs\/(\d+)$/)) && req.method === 'DELETE') {
        db.prepare('DELETE FROM configs WHERE id=?').run(Number(m[1]));
        return send(res, 200, { ok: true });
      }
      if (p === '/api/admin/plans') {
        if (req.method === 'GET') return send(res, 200, getPlans());
        if (req.method === 'PUT') {
          const b = await readBody(req);
          if (!Array.isArray(b.plans) || typeof b.telegram_url !== 'string')
            return send(res, 400, { error: 'فرمت نادرست' });
          db.prepare('UPDATE settings SET value=? WHERE key=?').run(JSON.stringify(b), 'plans');
          return send(res, 200, { ok: true });
        }
      }
    }
    send(res, 404, { error: 'not found' });
  } catch {
    send(res, 400, { error: 'bad request' });
  }
});
server.listen(PORT, () => console.log(`server on :${PORT}  |  admin panel: /admin`));
