package dev.zakocoder.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Test APK only. Java keeps its separate provider process independent of the target's Kotlin runtime. */
public final class ImportFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor result = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        String part = uri.getLastPathSegment();
        result.addRow(new Object[]{"invalid".equals(part) ? "../outside.txt" : "slow".equals(part) ? "slow.txt" : "imported.txt"});
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read-only fixture");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if ("slow".equals(uri.getLastPathSegment())) {
                    for (int i = 0; i < 64; i++) {
                        output.write(new byte[16384]); output.flush(); Thread.sleep(50);
                    }
                } else output.write("ZAKO_FILE_IMPORT_OK\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Cancellation closes the read end.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "ImportFixture").start();
        return pipe[0];
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
