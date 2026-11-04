package com.babbur.waypointer.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

record V11Model(int[] probabilities, byte[] seen, int[] weights) {

    static final String RESOURCE = "/assets/waypointer/codec/v11-model.bin";
    private static final int MAGIC = 0x57504D31;

    static V11Model load(int contexts, int mixers) {
        try (InputStream in = V11Model.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            return read(in.readAllBytes(), contexts, mixers);
        } catch (IOException failure) {
            throw new IllegalStateException("v11 model is unreadable", failure);
        }
    }

    static V11Model read(byte[] file, int contexts, int mixers) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a v11 model");
            if (in.readInt() != contexts || in.readInt() != mixers) {
                throw new IOException("v11 model does not match the codec's context layout");
            }
            int[] probabilities = new int[contexts];
            byte[] seen = new byte[contexts];
            int entries = in.readInt();
            int index = -1;
            for (int i = 0; i < entries; i++) {
                index += readUvarint(in) + 1;
                probabilities[index] = in.readUnsignedShort();
                seen[index] = in.readByte();
            }
            int[] weights = new int[mixers * V11Coder.MAX_INPUTS];
            for (int i = 0; i < weights.length; i++) weights[i] = in.readInt();
            return new V11Model(probabilities, seen, weights);
        }
    }

    byte[] write(int contexts, int mixers) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
            out.writeInt(MAGIC);
            out.writeInt(contexts);
            out.writeInt(mixers);
            int entries = 0;
            for (byte n : seen) if (n != 0) entries++;
            out.writeInt(entries);
            int previous = -1;
            for (int i = 0; i < contexts; i++) {
                if (seen[i] == 0) continue;
                writeUvarint(out, i - previous - 1);
                out.writeShort(probabilities[i]);
                out.writeByte(seen[i]);
                previous = i;
            }
            for (int weight : weights) out.writeInt(weight);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static int readUvarint(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("invalid v11 model");
    }

    private static void writeUvarint(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }
}
