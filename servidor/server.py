"""CasaFotos 1.0: servidor privado de fotografias (Python 3.12+)."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import io
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import ssl
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from PIL import Image, ImageOps

GB = 1_000_000_000
RESERVE = 64 * 1024 * 1024  # margem para SQLite e alterações de metadados
HASH_RE = re.compile(r"^[a-f0-9]{64}$")


def atomic_json(path, value):
    path = Path(path)
    tmp = path.with_suffix('.new')
    with tmp.open('w', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def file_hash(path):
    with open(path, 'rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def make_certificate(root):
    certfile, keyfile = root / 'certificate.pem', root / 'private-key.pem'
    if not certfile.exists() or not keyfile.exists():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'CasaFotos')])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(days=1))
                .not_valid_after(now + timedelta(days=3650))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]), False)
                .sign(key, hashes.SHA256()))
        keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        os.chmod(keyfile, 0o600)
        certfile.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    cert = x509.load_pem_x509_certificate(certfile.read_bytes())
    return certfile, keyfile, cert.fingerprint(hashes.SHA256()).hex()


class Store:
    def __init__(self, config):
        self.root = Path(config['data_dir']).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.limit = min(int(config.get('quota_bytes', 30 * GB)), 30 * GB)
        self.lock = threading.RLock()
        self.pair_lock = threading.Lock()
        self.attempts = []
        self.pair_code = None
        self.pair_until = 0
        for name in ('originals', 'thumbs', 'incoming'):
            (self.root / name).mkdir(exist_ok=True)
        # Um único processo pode abrir este armazenamento.
        self.lock_file = (self.root / 'server.lock').open('a+b')
        if os.name == 'nt':
            import msvcrt
            self.lock_file.seek(0)
            if self.lock_file.read(1) == b'':
                self.lock_file.write(b'0')
                self.lock_file.flush()
            self.lock_file.seek(0)
            msvcrt.locking(self.lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for p in (self.root / 'incoming').glob('*.part'):
            p.unlink()  # transferências incompletas nunca são fotografias confirmadas
        with self.db() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS assets (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL,
                size INTEGER NOT NULL, uploaded INTEGER NOT NULL, album TEXT NOT NULL DEFAULT '',
                extension TEXT NOT NULL DEFAULT '', thumb INTEGER NOT NULL DEFAULT 0);
              CREATE TABLE IF NOT EXISTS devices (token_hash TEXT PRIMARY KEY, created INTEGER NOT NULL);
            ''')
        self.cert, self.key, self.fingerprint = make_certificate(self.root)

    def db(self):
        db = sqlite3.connect(self.root / 'library.sqlite3', timeout=30)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA synchronous=FULL')
        return db

    def usage(self):
        return sum(p.stat().st_size for p in self.root.rglob('*') if p.is_file())

    def stats(self):
        with self.lock, self.db() as db:
            count, originals = db.execute('SELECT count(*),coalesce(sum(size),0) FROM assets').fetchone()
            used = self.usage()
            available = max(0, min(self.limit - used - RESERVE,
                                   shutil.disk_usage(self.root).free - RESERVE))
            return dict(count=count, used_bytes=used, originals_bytes=originals,
                        quota_bytes=self.limit, available_bytes=available)

    def asset(self, asset_id):
        if not HASH_RE.fullmatch(asset_id):
            raise APIError(404, 'Fotografia não encontrada.')
        with self.db() as db:
            row = db.execute('SELECT * FROM assets WHERE id=?', (asset_id,)).fetchone()
        if not row:
            raise APIError(404, 'Fotografia não encontrada.')
        return dict(row)

    def path(self, row):
        return self.root / 'originals' / (row['id'] + row['extension'])

    def verify(self, asset_id):
        with self.lock:
            row = self.asset(asset_id)
            path = self.path(row)
            if not path.is_file() or path.stat().st_size != row['size'] or file_hash(path) != asset_id:
                raise APIError(409, 'A verificação do original falhou. Mantém a cópia no telemóvel.')
            return dict(id=asset_id, sha256=asset_id, size=row['size'], verified=True)

    def authorized(self, token):
        if len(token) != 64:
            return False
        digest = hashlib.sha256(token.encode()).hexdigest()
        with self.db() as db:
            return db.execute('SELECT 1 FROM devices WHERE token_hash=?', (digest,)).fetchone() is not None

    def activate_pairing(self):
        # Só um pedido criado no disco local pelo painel abre emparelhamento.
        request = self.root / 'pair.request'
        if request.exists():
            request.unlink()
            self.pair_code = f'{secrets.randbelow(100_000_000):08d}'
            self.pair_until = time.time() + 600
            atomic_json(self.root / 'pairing.json', {
                'code': self.pair_code, 'expires': self.pair_until,
                'fingerprint': self.fingerprint,
                'check': self.fingerprint[:16].upper()})

    def pair(self, code):
        with self.pair_lock:
            now = time.time()
            self.attempts = [t for t in self.attempts if now - t < 60]
            if len(self.attempts) >= 5:
                raise APIError(429, 'Demasiadas tentativas. Aguarda um minuto.')
            self.attempts.append(now)
            if not self.pair_code or now > self.pair_until or not hmac.compare_digest(code, self.pair_code):
                raise APIError(403, 'Código incorreto ou expirado. Abre o painel no PC.')
            token = secrets.token_hex(32)
            with self.db() as db:
                db.execute('INSERT INTO devices VALUES (?,?)',
                           (hashlib.sha256(token.encode()).hexdigest(), int(now)))
            self.pair_code = None
            (self.root / 'pairing.json').unlink(missing_ok=True)
            return {'token': token}

    def upload(self, stream, length, expected, name, mime):
        if not HASH_RE.fullmatch(expected):
            raise APIError(400, 'Assinatura do ficheiro inválida.')
        if length <= 0:
            raise APIError(400, 'O ficheiro está vazio.')
        if not (mime.startswith('image/') or mime.startswith('video/')):
            raise APIError(415, 'Escolhe uma fotografia ou um vídeo.')
        name = name.replace('\\', '/').split('/')[-1][:200] or 'fotografia'
        name = ''.join(c for c in name if ord(c) >= 32)
        extension = Path(name).suffix.lower()
        if not re.fullmatch(r'\.[a-z0-9]{1,8}', extension):
            extension = ''
        # Serializar uploads mantém a quota correta mesmo com dois telemóveis.
        with self.lock:
            with self.db() as db:
                existing = db.execute('SELECT * FROM assets WHERE id=?', (expected,)).fetchone()
            if not existing and length > self.stats()['available_bytes']:
                raise APIError(507, 'O espaço da aplicação está cheio. Nada foi removido do telemóvel.')
            tmp = self.root / 'incoming' / (secrets.token_hex(16) + '.part')
            try:
                digest = hashlib.sha256()
                remaining = length
                # Duplicados são lidos e verificados sem outra cópia no disco.
                with (tmp.open('wb') if not existing else open(os.devnull, 'wb')) as out:
                    while remaining:
                        chunk = stream.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise APIError(400, 'Transferência interrompida. Tenta novamente.')
                        digest.update(chunk)
                        out.write(chunk)
                        remaining -= len(chunk)
                    if not existing:
                        out.flush()
                        os.fsync(out.fileno())
                if not hmac.compare_digest(digest.hexdigest(), expected):
                    raise APIError(422, 'O ficheiro recebido não corresponde ao original.')
                if existing:
                    result = self.verify(expected)
                    result['duplicate'] = True
                    return result
                if file_hash(tmp) != expected:
                    raise APIError(422, 'A verificação no disco falhou.')
                final = self.root / 'originals' / (expected + extension)
                os.replace(tmp, final)
                thumb = 0
                # Falhar uma miniatura não invalida nem modifica o original.
                try:
                    if mime.startswith('image/'):
                        with Image.open(final) as source:
                            source.thumbnail((600, 600))
                            preview = ImageOps.exif_transpose(source).convert('RGB')
                            buf = io.BytesIO()
                            preview.save(buf, format='JPEG', quality=78)
                            if len(buf.getvalue()) < self.stats()['available_bytes']:
                                (self.root / 'thumbs' / (expected + '.jpg')).write_bytes(buf.getvalue())
                                thumb = 1
                except Exception:
                    pass
                with self.db() as db:
                    db.execute('INSERT INTO assets VALUES (?,?,?,?,?,?,?,?)',
                               (expected, name, mime, length, int(time.time()), '', extension, thumb))
                return dict(id=expected, sha256=expected, size=length, verified=True, duplicate=False)
            finally:
                tmp.unlink(missing_ok=True)


class APIError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    server_version = 'CasaFotos/1.0'

    def setup(self):
        super().setup()
        self.connection.settimeout(120)

    @property
    def store(self):
        return self.server.store

    def log_message(self, *args):
        pass  # não registar tokens, nomes de ficheiros nem códigos

    def json_response(self, value, status=200):
        payload = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def body(self):
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 < size <= 8192:
            raise APIError(400, 'Pedido inválido.')
        value = json.loads(self.rfile.read(size))
        if not isinstance(value, dict):
            raise APIError(400, 'Pedido inválido.')
        return value

    def dispatch(self):
        route = urlparse(self.path)
        path = route.path
        if self.command == 'GET' and path == '/hello':
            return self.json_response({'app': 'CasaFotos', 'version': 1})
        if self.command == 'POST' and path == '/pair':
            return self.json_response(self.store.pair(str(self.body().get('code', ''))))
        auth = self.headers.get('Authorization', '')
        if not auth.startswith('Bearer ') or not self.store.authorized(auth[7:]):
            raise APIError(401, 'Liga novamente a aplicação ao PC.')
        if self.command == 'GET' and path == '/stats':
            return self.json_response(self.store.stats())
        if self.command == 'GET' and path == '/assets':
            query = parse_qs(route.query)
            offset = max(0, int(query.get('offset', ['0'])[0]))
            with self.store.db() as db:
                rows = db.execute('SELECT * FROM assets ORDER BY uploaded DESC,id LIMIT 120 OFFSET ?',
                                  (offset,)).fetchall()
            return self.json_response({'items': [dict(r) for r in rows],
                                       'next_offset': offset + 120 if len(rows) == 120 else None})
        if self.command == 'POST' and path == '/upload':
            length = int(self.headers.get('Content-Length', '0'))
            name = unquote(self.headers.get('X-Filename', 'fotografia'))
            result = self.store.upload(self.rfile, length, self.headers.get('X-SHA256', ''),
                                       name, self.headers.get('Content-Type', '').split(';')[0])
            return self.json_response(result, 201)
        match = re.fullmatch(r'/assets/([a-f0-9]{64})/(verify|original|thumb|album)', path)
        if match:
            asset_id, action = match.groups()
            if self.command == 'POST' and action == 'verify':
                return self.json_response(self.store.verify(asset_id))
            if self.command == 'POST' and action == 'album':
                self.store.asset(asset_id)
                album = str(self.body().get('album', '')).strip()[:80]
                with self.store.lock, self.store.db() as db:
                    db.execute('UPDATE assets SET album=? WHERE id=?', (album, asset_id))
                return self.json_response({'ok': True})
            if self.command == 'GET' and action in ('original', 'thumb'):
                row = self.store.asset(asset_id)
                file = self.store.path(row) if action == 'original' else self.store.root / 'thumbs' / (asset_id + '.jpg')
                if not file.is_file():
                    raise APIError(404, 'Pré-visualização indisponível.' if action == 'thumb' else 'Original indisponível.')
                self.send_response(200)
                self.send_header('Content-Type', row['mime'] if action == 'original' else 'image/jpeg')
                self.send_header('Content-Length', str(file.stat().st_size))
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                with file.open('rb') as f:
                    shutil.copyfileobj(f, self.wfile, 1024 * 1024)
                return
        raise APIError(404, 'Pedido não encontrado.')

    def handle_request(self):
        try:
            self.dispatch()
        except APIError as e:
            self.json_response({'error': e.message}, e.status)
        except (BrokenPipeError, ConnectionResetError, TimeoutError, ssl.SSLError):
            pass
        except (ValueError, json.JSONDecodeError):
            self.json_response({'error': 'Pedido inválido.'}, 400)
        except Exception:
            logging.exception('Falha interna')
            try:
                self.json_response({'error': 'Não foi possível concluir. Mantém os originais no telemóvel.'}, 500)
            except OSError:
                pass

    do_GET = handle_request
    do_POST = handle_request


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, store):
        self.store = store
        self.slots = threading.BoundedSemaphore(12)
        super().__init__(address, Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(store.cert, store.key)
        self.context = context

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(15)
            request = self.context.wrap_socket(request, server_side=True)
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError):
            request.close()
        finally:
            self.slots.release()


def run(config_path):
    config_path = Path(config_path).resolve()
    config = json.loads(config_path.read_text(encoding='utf-8-sig'))
    # A reserva física tem de estar montada. Nunca escrever na pasta vazia de montagem.
    marker = config.get('volume_marker')
    if marker and not Path(marker).is_file():
        raise RuntimeError('O disco reservado não está montado. Executa INICIAR no PC.')
    store = Store(config)
    state_dir = Path(config.get('state_dir', str(config_path.parent)))
    state_dir.mkdir(parents=True, exist_ok=True)
    log = RotatingFileHandler(state_dir / 'server.log', maxBytes=512_000, backupCount=2, encoding='utf-8')
    logging.basicConfig(level=logging.WARNING, handlers=[log])
    server = Server((config.get('host', '0.0.0.0'), int(config.get('port', 47831))), store)
    def monitor():
        while True:
            try:
                with store.pair_lock:
                    store.activate_pairing()
                if (store.root / 'revoke.request').exists():
                    (store.root / 'revoke.request').unlink()
                    with store.db() as db:
                        db.execute('DELETE FROM devices')
                atomic_json(state_dir / 'status.json',
                            {'heartbeat': time.time(), 'fingerprint': store.fingerprint,
                             'port': server.server_port})
            except Exception:
                logging.exception('Falha no estado do servidor')
            time.sleep(3)
    threading.Thread(target=monitor, daemon=True).start()
    server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    run(parser.parse_args().config)
