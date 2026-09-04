package com.babbur.waypointer.codec;

import static com.babbur.waypointer.codec.EnvelopeAudit.*;

import com.babbur.waypointer.core.WaypointGroup;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Recompress current semantic winners; never change no-names canonical semantic choice. */
public final class EnvelopeRichAudit {
  record Sem(String label, byte[] bytes) {}

  static void row(
      PrintWriter out, String set, int id, WaypointGroup group, WaypointCodec.Options options)
      throws Exception {
    List<WaypointGroup> gs = List.of(group);
    V10Transport.CheckedFrame frame = V10Transport.probe(V10RouteCodec.encode(gs, options));
    Candidate baseline =
        new Candidate(
            "current",
            new V10Transport.Outbound(
                frame.mode(), V10Transport.decode(V10RouteCodec.encode(gs, options)).payload()));
    List<Sem> semantics = new ArrayList<>();
    semantics.add(
        new Sem(
            "general",
            V10Transport.probe(V10GeneralRouteCodec.encodeCandidate(gs, options).transport())
                .semantic()));
    if (V10CompactRouteCodec.canEncode(group, options))
      semantics.add(
          new Sem(
              "compact",
              V10Transport.probe(V10CompactRouteCodec.encodeCandidate(group, options).transport())
                  .semantic()));
    Candidate huff = baseline,
        l6 = baseline,
        l1 = baseline,
        l4filter = baseline,
        best = baseline,
        byteWinner = baseline;
    byte[] winnerSemantic = frame.semantic();
    for (Sem sem : semantics) {
      huff = shorter(huff, deflated(sem.bytes, 9, 2));
      l6 = shorter(l6, deflated(sem.bytes, 6, 0));
      l1 = shorter(l1, deflated(sem.bytes, 1, 0));
      l4filter = shorter(l4filter, deflated(sem.bytes, 4, 1));
      for (int level = 1; level <= 9; level++)
        for (int strategy = 0; strategy <= 2; strategy++) {
          Candidate c = deflated(sem.bytes, level, strategy);
          c = new Candidate(sem.label + ":" + c.label(), c.value());
          Candidate chosen = shorter(best, c);
          if (chosen == c) {
            best = c;
            winnerSemantic = sem.bytes;
          }
          if (c.value().payload().length < byteWinner.value().payload().length) byteWinner = c;
        }
    }
    Candidate padded = padding(best, winnerSemantic);
    if (!Arrays.equals(winnerSemantic, V10Transport.probe(padded.value().transport()).semantic()))
      throw new AssertionError("semantic changed");
    WaypointCodec.decode("WP:" + best.value().transport());
    WaypointCodec.decode("WP:" + padded.value().transport());
    byte[] p = baseline.value().payload();
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
    int escapes = baseline.value().transport().length() - AsciiStreamCodec.encode(p).length();
    out.printf(
        Locale.ROOT,
        "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d%n",
        set,
        id,
        group.size(),
        len(baseline),
        len(huff),
        len(l6),
        len(l1),
        len(l4filter),
        len(best),
        len(padded),
        best.label(),
        p.length,
        baseline.value().mode(),
        escapes,
        noCRC,
        noHeader,
        noFrame,
        len(byteWinner));
  }

  public static void main(String[] args) throws Exception {
    var corpus = CodecRouteCorpus.load(Path.of(args[0]));
    try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
      out.println(
          "set,index,points,current,huffman9,default6,default1,filtered4,all27,padding,best,payloadBytes,mode,escapes,noCRC_UNSAFE,noHeader_NEW_VERSION,noFrame_UNSAFE,byteWinner");
      for (var r : corpus.routes()) {
        row(out, "full_fidelity", r.index(), r.group(), WaypointCodec.Options.FULL_FIDELITY);
        row(out, "no_names", r.index(), r.group(), WaypointCodec.Options.NO_NAMES);
      }
    }
  }
}
