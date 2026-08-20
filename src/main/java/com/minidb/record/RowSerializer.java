package com.minidb.record;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class RowSerializer {

    // Layout: [nameLength:4][id:4][nameBytes:nameLength][age:4]
    private static final int HEADER_SIZE = Integer.BYTES * 2; // nameLength + id

    public static byte[] serialize(Row row) {
        byte[] nameBytes = row.getName().getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + nameBytes.length + Integer.BYTES);
        buffer.putInt(nameBytes.length); // byte length, not character count
        buffer.putInt(row.getId());
        buffer.put(nameBytes);
        buffer.putInt(row.getAge());

        return buffer.array();
    }

    public static Row deserialize(byte[] data, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        int nameLength = buffer.getInt(offset);
        int id = buffer.getInt(offset + Integer.BYTES);

        byte[] nameBytes = new byte[nameLength];
        buffer.position(offset + HEADER_SIZE);
        buffer.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);

        int age = buffer.getInt(offset + HEADER_SIZE + nameLength);

        return new Row(id, name, age);
    }

    // How many bytes this row will occupy once serialized, so a caller can check
    // it fits in the remaining page space before writing it.
    public static int sizeOf(Row row) {
        return HEADER_SIZE + row.getName().getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
    }

    // How many bytes the record already sitting at `offset` occupies. Reads the
    // nameLength exactly the way deserialize() does, so the two always agree on
    // where the next record starts.
    public static int recordSize(byte[] data, int offset) {
        int nameLength = ByteBuffer.wrap(data).getInt(offset);
        return HEADER_SIZE + nameLength + Integer.BYTES;
    }
}
