package com.evoworld.uckitkatwebview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/*
 * Small stand-in for androidx.core.content.FileProvider, no need real
 * dependency for this. This project try avoid external library
 * everywhere (only pure reflection over UC engine, no bundle library),
 * add androidx.core just for one class not worth new Gradle dependency.
 * Also already confirm missing from this project real build (compile
 * error: "package androidx.core.content does not exist").
 *
 * Only do one job: serve file under getCacheDir()/uploads/ (see
 * MainActivity.copyToLocalFileProviderUri()), address like
 * content://<authority>/uploads/<filename>. Use for give UC engine URI
 * it can really read across multi-process sandbox boundary (see note
 * on that method). android:exported="false" plus manifest level
 * android:grantUriPermissions="true" is same standard pattern
 * FileProvider self also use. MainActivity still call
 * grantUriPermission() explicit per-URI on top too.
 */
public class SimpleFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.evoworld.uckitkatwebview.fileprovider";

    public static Uri getUriForFile(File file) {
        return new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("uploads")
            .appendPath(file.getName())
            .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileForUri(uri);
        if (f == null || !f.exists()) {
            throw new FileNotFoundException("No such upload file for " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileForUri(uri);
        if (f == null || !f.exists()) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{f.getName(), f.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        File f = fileForUri(uri);
        if (f == null) return "application/octet-stream";
        String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(f).toString());
        String mime = ext != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase()) : null;
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("SimpleFileProvider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("SimpleFileProvider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("SimpleFileProvider is read-only");
    }

    /*
     * Only need resolve URI that this provider self already give out
     * (see getUriForFile()/MainActivity.copyToLocalFileProviderUri()).
     * Filename strip any path separator as basic defense for path
     * traversal, even if in practice always one we generate ourself.
     */
    private File fileForUri(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) return null;
        String filename = segments.get(segments.size() - 1);
        if (filename == null) return null;
        filename = filename.replace("/", "").replace("\\", "");
        Context ctx = getContext();
        if (ctx == null) return null;
        File uploadsDir = new File(ctx.getCacheDir(), "uploads");
        return new File(uploadsDir, filename);
    }
}