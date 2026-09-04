// Independent, deliberately envelope-free compression controls, not named mod formats.
// Usage: node tools/codec-compression-controls.mjs corpus.json output.json
import fs from 'node:fs';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import assert from 'node:assert/strict';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Usage: node tools/codec-compression-controls.mjs corpus.json output.json');
const source = fs.readFileSync(input);
const corpus = JSON.parse(source);
assert(Array.isArray(corpus) && corpus.length > 0, 'Expected nonempty route array');
const variants = ['gzip9_coordinate_json', 'brotli11_coordinate_json',
  'brotli11_delta_json', 'brotli11_delta_int32le'];
const totals = Object.fromEntries(variants.map(name => [name, 0]));
const rows = [];
for (const [index, route] of corpus.entries()) {
  assert(Array.isArray(route.waypoints) && route.waypoints.length > 0);
  const coords = route.waypoints.map(point => ['x', 'y', 'z'].map(axis => {
    const value = point[axis];
    assert(Number.isInteger(value) && value >= -134217728 && value <= 134217727,
      `Route ${index}: invalid coordinate`);
    return value;
  }));
  const deltas = coords.map((point, i) => point.map((value, axis) =>
    i === 0 ? value : value - coords[i - 1][axis]));
  const binary = Buffer.alloc(deltas.length * 12);
  deltas.forEach((point, i) => point.forEach((value, axis) => binary.writeInt32LE(value, i * 12 + axis * 4)));
  const json = Buffer.from(JSON.stringify(coords));
  const deltaJson = Buffer.from(JSON.stringify(deltas));
  const measures = {};
  for (const variant of variants) {
    const body = variant.endsWith('int32le') ? binary : variant.includes('delta') ? deltaJson : json;
    const gzip = variant.startsWith('gzip');
    const compressed = gzip ? zlib.gzipSync(body, { level: 9 }) : zlib.brotliCompressSync(body, {
      params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11 },
    });
    const code = compressed.toString('base64');
    const decoded = gzip ? zlib.gunzipSync(Buffer.from(code, 'base64'))
      : zlib.brotliDecompressSync(Buffer.from(code, 'base64'));
    assert.deepEqual(decoded, body, `Route ${index}: compression round trip failed`);
    let restored;
    if (variant.endsWith('int32le')) {
      restored = Array.from({ length: decoded.length / 12 }, (_, i) =>
        [0, 1, 2].map(axis => decoded.readInt32LE(i * 12 + axis * 4)));
    } else restored = JSON.parse(decoded.toString('utf8'));
    if (variant.includes('delta')) {
      for (let i = 1; i < restored.length; i++) {
        for (let axis = 0; axis < 3; axis++) restored[i][axis] += restored[i - 1][axis];
      }
    }
    assert.deepEqual(restored, coords, `Route ${index}: coordinate round trip failed`);
    measures[variant] = Buffer.byteLength(code, 'utf8');
    totals[variant] += measures[variant];
  }
  rows.push({ index, points: coords.length, ...measures });
}
const report = {
  description: 'Generic compression controls; these are not mod share formats. All contain only ordered integer coordinates. Base64 included; no version, prefix, point count, or extra checksum. Brotli controls deliberately receive this envelope advantage. Gzip contains its standard header and checksum. Every row is decoded and checked.',
  sourceSha256: crypto.createHash('sha256').update(source).digest('hex'),
  nodeVersion: process.version,
  zlibVersion: process.versions.zlib,
  brotliVersion: process.versions.brotli,
  routes: rows.length,
  points: rows.reduce((sum, row) => sum + row.points, 0),
  unit: 'complete Base64 UTF-8 bytes (also ASCII characters)',
  totals,
  rows,
};
fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({ routes: report.routes, points: report.points, totals }, null, 2));
