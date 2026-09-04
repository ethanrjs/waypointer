package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Experiment only: evaluate wire-compatible transport portfolios with production codec. */
public final class EnvelopeAudit {
  record Candidate(String label, V10Transport.Outbound value) {}

  static int len(Candidate c) {
    return 3 + c.value.transport().length();
  }

  static Candidate shorter(Candidate a, Candidate b) {
    return b.value.compareTo(a.value) < 0 ? b : a;
  }

  static byte[] deflate(byte[] input, int level, int strategy) throws IOException {
    Deflater d = new Deflater(level, true);
    d.setStrategy(strategy);
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    try (DeflaterOutputStream s = new DeflaterOutputStream(o, d)) {
      s.write(input);
    } finally {
      d.end();
    }
    return o.toByteArray();
  }

  static Candidate deflated(byte[] semantic, int level, int strategy) throws IOException {
    byte[] b = Arrays.copyOfRange(semantic, 1, semantic.length);
    return new Candidate(
        "L" + level + "S" + strategy,
        new V10Transport.Outbound(
            1, V10Transport.sealCompressed(semantic, deflate(b, level, strategy))));
  }

  static Candidate padding(Candidate source, byte[] semantic) throws IOException {
    if (source.value.mode() == 0) return source;
    byte[] compressed =
        Arrays.copyOfRange(source.value.payload(), 1, source.value.payload().length - 2);
    byte[] body = Arrays.copyOfRange(semantic, 1, semantic.length);
    Candidate best = source;
    for (int last = 0; last < 256; last++) {
      compressed[compressed.length - 1] = (byte) last;
      try {
        if (!Arrays.equals(body, V10Transport.inflate(compressed))) continue;
        Candidate c =
            new Candidate(
                source.label + "+tail",
                new V10Transport.Outbound(1, V10Transport.sealCompressed(semantic, compressed)));
        best = shorter(best, c);
      } catch (IOException rejected) {
      }
    }
    return best;
  }

  static WaypointGroup group(int[][] coords) {
    WaypointGroup g = WaypointGroup.create("", "unknown");
    g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
    for (int[] c : coords) g.add(Waypoint.at(c[0], c[1], c[2]));
    return g;
  }

  static void verify(Candidate c, int[][] coords) throws IOException {
    WaypointGroup got = V10BareRouteCodec.decode(c.value.transport());
    if (!Arrays.deepEquals(coords, V10BareRouteCodec.coordinatesOf(got)))
      throw new AssertionError("roundtrip");
    String wire = "WP:" + c.value.transport();
    if (wire.length() != wire.getBytes(StandardCharsets.UTF_8).length)
      throw new AssertionError("not ASCII");
  }

  static void row(PrintWriter out, String set, int id, int[][] coords) throws Exception {
    WaypointGroup g = group(coords);
    Candidate baseline = new Candidate("current", V10BareRouteCodec.encodeCandidate(g));
    byte[] semantic = V10BareRouteCodec.encodeDeltaSemantic(coords);
    Candidate huff = shorter(baseline, deflated(semantic, 9, 2));
    Candidate best = baseline;
    Candidate l6 = shorter(baseline, deflated(semantic, 6, 0));
    Candidate l1 = shorter(baseline, deflated(semantic, 1, 0));
    Candidate byteWinner = baseline;
    for (int level = 1; level <= 9; level++)
      for (int strategy = 0; strategy <= 2; strategy++) {
        Candidate c = deflated(semantic, level, strategy);
        best = shorter(best, c);
        if (c.value.payload().length < byteWinner.value.payload().length) byteWinner = c;
      }
    Candidate padded = padding(best, semantic);
    verify(baseline, coords);
    verify(best, coords);
    verify(padded, coords);
    byte[] p = baseline.value.payload();
    int noCRC =
        3
            + V10Transport.escapeContextual(AsciiStreamCodec.encode(Arrays.copyOf(p, p.length - 2)))
                .length();
    int noHeader =
        3
            + V10Transport.escapeContextual(
                    AsciiStreamCodec.encode(Arrays.copyOfRange(p, 1, p.length)))
                .length();
    int noFrame =
        3
            + V10Transport.escapeContextual(
                    AsciiStreamCodec.encode(Arrays.copyOfRange(p, 1, p.length - 2)))
                .length();
    int escapes = baseline.value.transport().length() - AsciiStreamCodec.encode(p).length();
    out.printf(
        Locale.ROOT,
        "%s,%d,%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d%n",
        set,
        id,
        coords.length,
        len(baseline),
        len(huff),
        len(l6),
        len(l1),
        len(best),
        len(padded),
        best.label,
        p.length,
        baseline.value.mode(),
        escapes,
        noCRC,
        noHeader,
        noFrame,
        len(byteWinner),
        semantic.length);
  }

  public static void main(String[] args) throws Exception {
    var routes = CodecRouteCorpus.load(Path.of(args[0])).routes();
    try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
      out.println(
          "set,index,points,current,huffman9,default6,default1,all27,padding,best,payloadBytes,mode,escapes,noCRC_UNSAFE,noHeader_NEW_VERSION,noFrame_UNSAFE,byteWinner,deltaSemanticBytes");
      for (int i = 0; i < routes.size(); i++) {
        int[][] coords = V10BareRouteCodec.coordinatesOf(routes.get(i).group());
        row(out, "full", i, coords);
        for (int size : new int[] {1, 2, 3, 5, 10})
          if (coords.length >= size) row(out, "prefix" + size, i, Arrays.copyOf(coords, size));
      }
      for (int size : new int[] {0, 1, 2, 3, 5, 10}) {
        int[][] zero = new int[size][3], straight = new int[size][3];
        for (int j = 0; j < size; j++) straight[j] = new int[] {j, 64, 0};
        row(out, "zeros", size, zero);
        row(out, "line", size, straight);
      }
    }
  }
}
