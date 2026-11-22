package dev.zakocoder.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Test APK only: fixed share payloads, no access to user or application files. */
public final class SharedFileFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String id = uri.getLastPathSegment();
        if ("denied".equals(id)) throw new SecurityException("Fixture grant denied");
        MatrixCursor result = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if ("no-metadata".equals(id)) return result;
        String name = "messy-name".equals(id) ? "../private\\report\n\u202E.txt" : "long-name".equals(id) ? repeat("n", 150) + ".txt" : "source.kt";
        Long size;
        if ("archive-declared-large".equals(id)) size = Long.valueOf(33554433L);
        else if ("archive-undeclared-large".equals(id)) size = null;
        else if ("declared-large".equals(id)) size = Long.valueOf(1048577L);
        else if ("undeclared-large".equals(id)) size = null;
        else size = Long.valueOf(payload(id).length);
        result.addRow(new Object[]{name, size});
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        String id = uri.getLastPathSegment();
        if ("declared-large".equals(id) || "archive-declared-large".equals(id)) throw new FileNotFoundException("Oversize metadata must be rejected before opening");
        if ("missing".equals(id)) throw new FileNotFoundException("Missing fixture");
        final byte[] bytes = payload(id);
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException failure) { throw new FileNotFoundException("Fixture pipe unavailable"); }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if ("archive-undeclared-large".equals(id)) {
                    byte[] block = new byte[65536];
                    Arrays.fill(block, (byte) 'x');
                    for (int i = 0; i < 512; i++) output.write(block);
                    output.write('x');
                } else output.write(bytes);
            } catch (IOException ignored) {
                // A bounded or cancelled read intentionally closes its pipe.
            }
        }, "SharedFileFixture").start();
        return pipe[0];
    }

    private static String repeat(String text, int count) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < count; i++) output.append(text);
        return output.toString();
    }

    private static byte[] payload(String id) {
        if ("empty".equals(id)) return new byte[0];
        if ("whitespace".equals(id)) return "\n\t  \n".getBytes(StandardCharsets.UTF_8);
        if ("utf8".equals(id)) return (repeat("测试😀", 1700) + "尾部").getBytes(StandardCharsets.UTF_8);
        if ("surrogate-boundary".equals(id)) return (repeat("x", 5999) + "😀tail").getBytes(StandardCharsets.UTF_8);
        if ("invalid-utf8".equals(id)) return new byte[]{(byte) 0xC3, (byte) 0x28};
        if ("binary".equals(id)) return new byte[]{'a', 0, 'b'};
        if ("late-binary".equals(id)) return (repeat("x", 7000) + "\u0000").getBytes(StandardCharsets.UTF_8);
        if ("undeclared-large".equals(id) || "exact-limit".equals(id)) {
            byte[] bytes = new byte["exact-limit".equals(id) ? 1048576 : 1048578];
            Arrays.fill(bytes, (byte) 'x');
            return bytes;
        }
        return "fun shared() = \"你好\"\n".getBytes(StandardCharsets.UTF_8);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
