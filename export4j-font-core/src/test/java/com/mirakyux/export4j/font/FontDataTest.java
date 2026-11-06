// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ReadOnlyBufferException;
import java.util.List;
import org.junit.jupiter.api.Test;

class FontDataTest {
    @Test
    void inputAndReturnedArraysCannotMutateStoredBytesExistingViewsOrIdentity() {
        byte[] input = {'a', 'b', 'c'};
        var data = new FontData(input, true);
        var view = data.readOnlyBuffer();
        input[0] = 'x';
        byte[] returned = data.bytes();
        returned[1] = 'y';
        byte[] visible = new byte[view.remaining()];
        view.get(visible);

        assertArrayEquals(new byte[] {'a', 'b', 'c'}, visible);
        assertArrayEquals(new byte[] {'a', 'b', 'c'}, data.bytes());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", data.id());
        assertEquals(3, data.byteSize());
        assertEquals((byte) 'a', data.readOnlyBuffer().get());
    }

    @Test
    void eachViewStartsAtTheCompleteProgramAndOwnsItsCursor() {
        var data = new FontData(new byte[] {0, 1, 2, 3, 4, 5, 6}, false);
        var first = data.readOnlyBuffer();
        assertEquals(0, first.position());
        assertEquals(data.byteSize(), first.limit());
        assertEquals(data.byteSize(), first.capacity());
        first.position(3).limit(5);

        var second = data.readOnlyBuffer();

        assertNotSame(first, second);
        assertEquals(0, second.position());
        assertEquals(data.byteSize(), second.limit());
        assertEquals((byte) 0, second.get());
        assertEquals(3, first.position());
        assertEquals(5, first.limit());
        assertEquals((byte) 3, first.get());
        assertEquals(1, second.position());
        second.limit(2);
        var fresh = data.readOnlyBuffer();
        assertEquals(0, fresh.position());
        assertEquals(data.byteSize(), fresh.limit());
        assertEquals(data.byteSize(), fresh.remaining());
    }

    @Test
    void slicesAndDuplicatesStayReadOnlyAndCannotExposeTheBackingArray() {
        var data = new FontData(new byte[] {10, 20, 30, 40}, true);
        var parent = data.readOnlyBuffer();
        parent.position(1).limit(3);
        var slice = parent.slice();
        var duplicate = parent.duplicate();
        assertEquals((byte) 20, slice.get(0));
        assertEquals((byte) 20, duplicate.get(1));

        for (var view : List.of(parent, slice, duplicate)) {
            int position = view.position();
            assertTrue(view.isReadOnly());
            assertFalse(view.hasArray());
            assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 99));
            assertThrows(ReadOnlyBufferException.class, () -> view.put(0, (byte) 99));
            assertThrows(ReadOnlyBufferException.class, () -> view.put(new byte[] {99}));
            assertThrows(ReadOnlyBufferException.class, view::compact);
            assertThrows(ReadOnlyBufferException.class, view::array);
            assertThrows(ReadOnlyBufferException.class, view::arrayOffset);
            assertEquals(position, view.position());
        }
        slice.position(1);
        duplicate.position(2).limit(2);
        assertEquals(1, parent.position());
        assertEquals(3, parent.limit());
        var fresh = data.readOnlyBuffer();
        assertEquals(0, fresh.position());
        assertEquals(data.byteSize(), fresh.limit());
        byte[] visible = new byte[fresh.remaining()];
        fresh.get(visible);
        assertArrayEquals(new byte[] {10, 20, 30, 40}, visible);
    }
}
