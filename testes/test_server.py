"""Testes de integridade e proteção de dados; não utilizam fotografias pessoais."""
import concurrent.futures
import hashlib
import http.client
import io
import json
from pathlib import Path
import ssl
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'servidor'))
from server import Store, Server, APIError, RESERVE
from PIL import Image


def sample(color='red'):
    out = io.BytesIO()
    Image.new('RGB', (128, 80), color).save(out, 'JPEG')
    return out.getvalue()


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store({'data_dir': self.tmp.name, 'quota_bytes': 100_000_000})
        self.server = Server(('127.0.0.1', 0), self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        (self.store.root / 'pair.request').touch()
        self.store.activate_pairing()
        code = self.store.pair_code
        status, response = self.request('POST', '/pair', {'code': code})
        self.assertEqual(status, 200)
        self.token = response['token']

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.store.lock_file.close()
        self.tmp.cleanup()

    def request(self, method, path, body=None, auth=True, headers=None):
        context = ssl._create_unverified_context()
        conn = http.client.HTTPSConnection('127.0.0.1', self.server.server_port, context=context, timeout=5)
        conn.connect()
        self.assertEqual(hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest(), self.store.fingerprint)
        h = headers or {}
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            h['Content-Type'] = 'application/json'
        if auth and hasattr(self, 'token'):
            h['Authorization'] = 'Bearer ' + self.token
        conn.request(method, path, body=body, headers=h)
        response = conn.getresponse()
        data = response.read()
        code = response.status
        conn.close()
        try:
            data = json.loads(data)
        except (ValueError, UnicodeDecodeError):
            pass
        return code, data

    def upload(self, data=None, digest=None):
        data = data if data is not None else sample()
        digest = digest or hashlib.sha256(data).hexdigest()
        return self.request('POST', '/upload', data, headers={
            'X-SHA256': digest, 'X-Filename': 'f%C3%A9rias.jpg', 'Content-Type': 'image/jpeg'})

    def test_upload_download_and_second_disk_verification(self):
        data = sample()
        status, result = self.upload(data)
        self.assertEqual(status, 201)
        self.assertTrue(result['verified'])
        asset_id = result['id']
        status, original = self.request('GET', f'/assets/{asset_id}/original')
        self.assertEqual(status, 200)
        self.assertEqual(original, data)
        status, proof = self.request('POST', f'/assets/{asset_id}/verify', {})
        self.assertTrue(proof['verified'])
        self.assertEqual(proof['size'], len(data))
        status, listing = self.request('GET', '/assets')
        self.assertEqual(listing['items'][0]['name'], 'férias.jpg')
        self.assertEqual(listing['items'][0]['thumb'], 1)

    def test_deduplication_keeps_one_original(self):
        self.upload()
        _, result = self.upload()
        self.assertTrue(result['duplicate'])
        self.assertEqual(self.store.stats()['count'], 1)
        self.assertEqual(len(list((self.store.root / 'originals').iterdir())), 1)

    def test_corruption_prevents_receipt_and_deletion_proof(self):
        status, result = self.upload(digest='a' * 64)
        self.assertEqual(status, 422)
        self.assertNotIn('verified', result)
        self.assertEqual(self.store.stats()['count'], 0)
        self.assertEqual(list((self.store.root / 'incoming').iterdir()), [])
        _, receipt = self.upload()
        path = self.store.path(self.store.asset(receipt['id']))
        path.write_bytes(b'corrupted')
        status, result = self.request('POST', f"/assets/{receipt['id']}/verify", {})
        self.assertEqual(status, 409)
        self.assertNotIn('verified', result)
        self.assertEqual(self.upload()[0], 409)

    def test_quota_and_concurrent_uploads(self):
        red, blue = sample('red'), sample('blue')
        # Espaço para um original, sem espaço para a miniatura ou para o segundo.
        self.store.limit = self.store.usage() + RESERVE + max(len(red), len(blue)) + 30
        def send(data):
            try:
                self.store.upload(io.BytesIO(data), len(data), hashlib.sha256(data).hexdigest(), 'x.jpg', 'image/jpeg')
                return 201
            except APIError as e:
                return e.status
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results = list(pool.map(send, [red, blue]))
        self.assertCountEqual(results, [201, 507])
        self.assertEqual(self.store.stats()['count'], 1)
        self.assertLessEqual(self.store.usage(), self.store.limit)

    def test_interrupted_upload_cleans_partial_without_asset(self):
        with self.assertRaises(APIError):
            self.store.upload(io.BytesIO(b'partial'), 100, 'a' * 64, 'x.jpg', 'image/jpeg')
        self.assertEqual(list((self.store.root / 'incoming').iterdir()), [])
        self.assertEqual(self.store.stats()['count'], 0)

    def test_auth_pair_code_single_use_expiry_and_throttling(self):
        for path in ['/stats', '/assets', '/assets/' + 'a'*64 + '/original']:
            self.assertEqual(self.request('GET', path, auth=False)[0], 401)
        self.assertEqual(self.request('POST', '/pair', {'code': 'wrong'})[0], 403)
        self.store.pair_code = '12345678'
        self.store.pair_until = time.time() - 1
        self.assertEqual(self.request('POST', '/pair', {'code': '12345678'})[0], 403)
        for _ in range(6):
            status, _ = self.request('POST', '/pair', {'code': 'wrong'})
        self.assertEqual(status, 429)

    def test_album_and_no_path_traversal(self):
        _, receipt = self.upload()
        asset_id = receipt['id']
        self.assertEqual(self.request('POST', f'/assets/{asset_id}/album', {'album': 'Família'})[0], 200)
        self.assertEqual(self.store.asset(asset_id)['album'], 'Família')
        self.assertEqual(self.request('GET', '/assets/../../private-key.pem/original')[0], 404)
        self.assertEqual(self.request('GET', '/private-key.pem')[0], 404)

    def test_reopen_preserves_files_and_removes_only_partials(self):
        self.upload()
        (self.store.root / 'incoming' / 'interrupted.part').write_bytes(b'partial')
        with self.assertRaises(OSError):
            Store({'data_dir': self.tmp.name})
        self.store.lock_file.close()
        recovered = Store({'data_dir': self.tmp.name})
        try:
            self.assertEqual(recovered.stats()['count'], 1)
            self.assertEqual(list((recovered.root / 'incoming').iterdir()), [])
            self.assertTrue(recovered.authorized(self.token))
        finally:
            recovered.lock_file.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
