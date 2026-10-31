package dev.zakocoder.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Read-only test APK document root. MANAGE_DOCUMENTS prevents direct app access without SAF. */
public final class CompatibilityDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "compatibility-root";
    private static final String FILE = "hello.py";
    private static final String CONTENT = "print('SAF compatibility fixture')\n";
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    };
    @Override public boolean onCreate() {
        Log.i("CompatibilitySaf", "provider created package=" + getContext().getPackageName());
        return true;
    }
    @Override public Cursor queryRoots(String[] projection) {
        Log.i("CompatibilitySaf", "queryRoots callerUid=" + android.os.Binder.getCallingUid());
        MatrixCursor cursor = new MatrixCursor(new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        });
        cursor.addRow(new Object[]{ROOT, ROOT, "Compatibility SAF Fixture",
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD, "text/x-python"});
        return cursor;
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        if (ROOT.equals(id)) cursor.addRow(new Object[]{ROOT, "Compatibility SAF Fixture",
            DocumentsContract.Document.MIME_TYPE_DIR, 0, 0});
        else if (FILE.equals(id)) cursor.addRow(new Object[]{FILE, FILE, "text/x-python", 0,
            CONTENT.getBytes(StandardCharsets.UTF_8).length});
        else throw new FileNotFoundException("Unknown fixture document");
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        Log.i("CompatibilitySaf", "queryDocument id=" + documentId);
        MatrixCursor cursor = new MatrixCursor(DOCUMENT_COLUMNS);
        row(cursor, documentId);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        Log.i("CompatibilitySaf", "queryChildDocuments parent=" + parentDocumentId);
        if (!ROOT.equals(parentDocumentId)) throw new FileNotFoundException("Not a fixture directory");
        MatrixCursor cursor = new MatrixCursor(DOCUMENT_COLUMNS);
        row(cursor, FILE);
        return cursor;
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return ROOT.equals(parentDocumentId) && (ROOT.equals(documentId) || FILE.equals(documentId));
    }
    /** Test control can only revoke this immutable fixture, never read, modify or grant documents. */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"revoke-compatibility-fixture".equals(method)) return super.call(method, arg, extras);
        if (!ROOT.equals(arg)) throw new SecurityException("Only the owned fixture root can be revoked");
        String testPackage = getContext().getPackageName();
        String targetPackage = testPackage.endsWith(".test")
            ? testPackage.substring(0, testPackage.length() - ".test".length()) : "";
        String[] callers = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        boolean authorized = Binder.getCallingUid() == android.os.Process.myUid();
        if (callers != null) for (String caller : callers) {
            if (!targetPackage.isEmpty() && targetPackage.equals(caller)) authorized = true;
        }
        if (!authorized) throw new SecurityException("Only the compatibility target can revoke its fixture grant");
        long identity = Binder.clearCallingIdentity();
        try {
            // Official DocumentsProvider API revokes ordinary and tree grants, including persisted modes.
            revokeDocumentPermission(ROOT);
            revokeDocumentPermission(FILE);
            // Invalidate DocumentsUI's per-authority cache when this test-only provider is
            // activated after APK installation. This grants no document access.
            getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(
                getContext().getPackageName() + ".compatibility-documents"), null);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        Log.i("CompatibilitySaf", "provider revoked owned document and tree grants");
        Bundle result = new Bundle();
        result.putBoolean("revoked", true);
        return result;
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!FILE.equals(documentId) || !"r".equals(mode)) throw new FileNotFoundException("Read-only fixture file");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(CONTENT.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // The importing caller may cancel and close its pipe.
                }
            }, "CompatibilitySafFixture").start();
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }
}
