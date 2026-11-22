package com.winfg.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;

/** Minimal Storage Access Framework writer (no AndroidX dependency). */
final class SafHelper {
    private SafHelper() {}

    static Uri ensureDir(Context c, Uri tree, String name) throws IOException {
        ContentResolver r = c.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Uri found = find(r, tree, rootId, name);
        if (found != null) return found;
        Uri made = DocumentsContract.createDocument(r, root, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (made == null) throw new IOException("Could not create folder " + name);
        return made;
    }

    static void writeFile(Context c, Uri tree, Uri folder, String name, byte[] data, String mime)
            throws IOException {
        ContentResolver r = c.getContentResolver();
        String parentId = DocumentsContract.getDocumentId(folder);
        Uri doc = find(r, tree, parentId, name);
        if (doc == null) {
            doc = DocumentsContract.createDocument(r, folder, mime, name);
            if (doc == null) throw new IOException("Could not create " + name);
        }
        try (OutputStream out = open(r, doc)) {
            out.write(data);
        }
    }

    private static OutputStream open(ContentResolver r, Uri doc) throws IOException {
        try {
            return r.openOutputStream(doc, "wt");
        } catch (Exception ignored) {
            return r.openOutputStream(doc, "w");
        }
    }

    private static Uri find(ContentResolver r, Uri tree, String parentDocId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cur = r.query(children, projection, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                if (name.equals(cur.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
