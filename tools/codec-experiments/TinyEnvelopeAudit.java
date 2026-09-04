package com.babbur.waypointer.codec;

import static com.babbur.waypointer.codec.EnvelopeAudit.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Deliberately NEW wire experiment: version 11 means bare/direct; header high nibble stores
 * count-1.
 */
public final class TinyEnvelopeAudit {
  static byte[] frame(byte[] directSemantic, int count) {
    if (count < 1 || count > 16 || (directSemantic[1] & 255) != count)
      throw new IllegalArgumentException();
    byte[] payload = new byte[directSemantic.length + 1]; // -count byte +two CRC bytes
    payload[0] = (byte) (((count - 1) << 4) | 11);
    System.arraycopy(directSemantic, 2, payload, 1, directSemantic.length - 2);
    int crc = V10Transport.checksum(payload[0], payload, 1, payload.length - 3);
    payload[payload.length - 2] = (byte) (crc >>> 8);
    payload[payload.length - 1] = (byte) crc;
    return payload;
  }

  static int[][] decode(String transport) throws IOException {
    byte[] p = V10Transport.decode(transport).payload(); // text layer remains V10-compatible
    if (p.length < 6 || (p[0] & 15) != 11) throw new IOException("bad tiny header");
    int count = ((p[0] & 255) >>> 4) + 1;
    int crc = V10Transport.checksum(p[0], p, 1, p.length - 3);
    int stored = ((p[p.length - 2] & 255) << 8) | (p[p.length - 1] & 255);
    if (crc != stored) throw new IOException("bad CRC");
    byte[] restored = new byte[p.length - 1];
    restored[0] = 0x2a;
    restored[1] = (byte) count;
    System.arraycopy(p, 1, restored, 2, p.length - 3);
    return V10BareRouteCodec.coordinatesOf(
        V10BareRouteCodec.decode(new V10Transport.CheckedFrame(0, restored)));
  }

  static void row(PrintWriter out, String set, int id, int[][] coords) throws Exception {
    int count = coords.length;
    String baseline = V10BareRouteCodec.encodeCandidate(group(coords)).transport();
    String tiny = V10Transport.encode(frame(V10BareRouteCodec.encodeRiceSemantic(coords), count));
    if (count > 1) {
      String q = V10Transport.encode(frame(V10BareEntropyCodec.encodeQuotient(coords), count));
      if (q.length() < tiny.length()) tiny = q;
    }
    if (!Arrays.deepEquals(coords, decode(tiny))) throw new AssertionError("tiny roundtrip");
    // Every single-bit binary mutation must fail either CRC or structure; reject unchanged codes.
    byte[] bytes = V10Transport.decode(tiny).payload();
    for (int bit = 0; bit < bytes.length * 8; bit++) {
      bytes[bit / 8] ^= (byte) (1 << (bit % 8));
      boolean rejected = false;
      try {
        decode(V10Transport.encode(bytes));
      } catch (IOException | IllegalArgumentException e) {
        rejected = true;
      }
      if (!rejected) throw new AssertionError("bit corruption accepted");
      bytes[bit / 8] ^= (byte) (1 << (bit % 8));
    }
    out.printf(
        Locale.ROOT,
        "%s,%d,%d,%d,%d,%d%n",
        set,
        id,
        count,
        baseline.length() + 3,
        tiny.length() + 3,
        Math.min(baseline.length(), tiny.length()) + 3);
  }

  public static void main(String[] args) throws Exception {
    var routes = CodecRouteCorpus.load(Path.of(args[0])).routes();
    try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
      out.println("set,index,points,current,tiny11_DIRECT_NEW_WIRE,currentPlusTiny11_NEW_WIRE");
      for (int i = 0; i < routes.size(); i++) {
        int[][] coords = V10BareRouteCodec.coordinatesOf(routes.get(i).group());
        if (coords.length <= 16) row(out, "full_tiny", i, coords);
        for (int size : new int[] {1, 2, 3, 5, 10})
          if (coords.length >= size) row(out, "prefix" + size, i, Arrays.copyOf(coords, size));
      }
    }
  }
}
