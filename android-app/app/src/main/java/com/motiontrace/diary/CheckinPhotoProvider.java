package com.motiontrace.diary;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 给系统相机提供临时写入入口，照片最终仍保存在应用私有目录，避免额外存储权限和第三方依赖。
 */
public final class CheckinPhotoProvider extends ContentProvider {
    private static final String AUTHORITY_SUFFIX = ".checkin-photo";
    private static final String PHOTO_DIR = "checkin_photos";

    static File createPhotoFile(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), PHOTO_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create photo directory");
        }
        String name = "camera_" + System.currentTimeMillis() + "_" + Long.toHexString(System.nanoTime()) + ".jpg";
        return new File(dir, name);
    }

    static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolvePhotoFile(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                    values[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(columns[i])) {
                    values[i] = file.exists() ? file.length() : 0L;
                } else {
                    values[i] = null;
                }
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException ignored) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolvePhotoFile(uri);
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.contains("w")) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    private File resolvePhotoFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        String name = uri.getLastPathSegment();
        if (context == null
                || name == null
                || name.contains("/")
                || name.contains("\\")
                || !name.startsWith("camera_")
                || !name.endsWith(".jpg")) {
            throw new FileNotFoundException("Invalid photo uri");
        }

        File dir = new File(context.getFilesDir(), PHOTO_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new FileNotFoundException("Cannot create photo directory");
        }
        return new File(dir, name);
    }
}
