// SPDX-License-Identifier: GPL-3.0-or-later
package dev.appmatrix.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;

/** Bounded imports; private normalized copies never retain EXIF/GPS metadata. */
public final class MediaFiles {
  public static final int MAX_IMPORT_BYTES = 25 * 1024 * 1024;
  public static final long MAX_IMPORT_PIXELS = 40_000_000;

  private MediaFiles() {}

  public static Bitmap readBitmap(Context context, Uri uri, int maxDimension) throws IOException {
    if (maxDimension < 1 || maxDimension > 4096)
      throw new IllegalArgumentException("Maximum dimension must be 1–4096");
    byte[] data;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      if (in == null) throw new IOException("Cannot open this image");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (out.size() + n > MAX_IMPORT_BYTES)
          throw new IOException("Choose an image smaller than 25 MB");
        out.write(buffer, 0, n);
      }
      data = out.toByteArray();
    }
    BitmapFactory.Options info = new BitmapFactory.Options();
    info.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, info);
    if (info.outWidth <= 0 || info.outHeight <= 0)
      throw new IOException("This file is not a supported image");
    if ((long) info.outWidth * info.outHeight > MAX_IMPORT_PIXELS)
      throw new IOException("Choose an image below 40 megapixels");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    options.inSampleSize = 1;
    while (Math.max(info.outWidth, info.outHeight) / options.inSampleSize > maxDimension)
      options.inSampleSize *= 2;
    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (bitmap == null) throw new IOException("This image could not be decoded");
    int orientation = ExifInterface.ORIENTATION_NORMAL;
    try {
      orientation =
          new ExifInterface(new ByteArrayInputStream(data))
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    } catch (IOException ignored) {
      /* Formats without EXIF retain decoded orientation. */
    }
    Matrix matrix = new Matrix();
    switch (orientation) {
      case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
        matrix.setScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_180:
        matrix.setRotate(180);
        break;
      case ExifInterface.ORIENTATION_FLIP_VERTICAL:
        matrix.setScale(1, -1);
        break;
      case ExifInterface.ORIENTATION_TRANSPOSE:
        matrix.setRotate(90);
        matrix.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_90:
        matrix.setRotate(90);
        break;
      case ExifInterface.ORIENTATION_TRANSVERSE:
        matrix.setRotate(-90);
        matrix.postScale(-1, 1);
        break;
      case ExifInterface.ORIENTATION_ROTATE_270:
        matrix.setRotate(-90);
        break;
      default:
        break;
    }
    Bitmap upright =
        matrix.isIdentity()
            ? bitmap
            : Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    Bitmap scaled = BitmapTransforms.resize(upright, maxDimension);
    if (bitmap != upright) bitmap.recycle();
    if (upright != scaled) upright.recycle();
    return scaled;
  }

  public static String saveBitmap(Context context, String filename, Bitmap bitmap)
      throws IOException {
    return save(context, filename, bitmap, false);
  }

  public static String savePng(Context context, String filename, Bitmap bitmap) throws IOException {
    return save(context, filename, bitmap, true);
  }

  private static String save(Context context, String filename, Bitmap bitmap, boolean png)
      throws IOException {
    if (filename == null
        || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
        || filename.contains("..")) throw new IOException("Invalid image filename");
    File directory = new File(context.getFilesDir(), "media");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create image storage");
    AtomicFile file = new AtomicFile(new File(directory, filename));
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      if (png) BitmapTransforms.exportPng(bitmap, output);
      else BitmapTransforms.exportJpeg(bitmap, output, 90);
      file.finishWrite(output);
    } catch (IOException | RuntimeException e) {
      if (output != null) file.failWrite(output);
      throw e;
    }
    return file.getBaseFile().getAbsolutePath();
  }

  public static Bitmap loadBitmap(String path) throws IOException {
    File file = new File(path);
    if (!file.isFile() || file.length() > MAX_IMPORT_BYTES)
      throw new IOException("Saved image is missing or too large");
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(path, o);
    if (o.outWidth < 1 || o.outHeight < 1 || (long) o.outWidth * o.outHeight > MAX_IMPORT_PIXELS)
      throw new IOException("Saved image is not supported");
    Bitmap result = BitmapFactory.decodeFile(path);
    if (result == null) throw new IOException("Saved image could not be opened");
    return result;
  }

  public static void copyTo(Context context, Uri destination, Bitmap bitmap, int quality)
      throws IOException {
    try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
      if (out == null) throw new IOException("Cannot write to this destination");
      BitmapTransforms.exportJpeg(bitmap, out, quality);
      out.flush();
    }
  }

  public static void copyPngTo(Context context, Uri destination, Bitmap bitmap) throws IOException {
    try (OutputStream out = context.getContentResolver().openOutputStream(destination, "wt")) {
      if (out == null) throw new IOException("Cannot write to this destination");
      BitmapTransforms.exportPng(bitmap, out);
      out.flush();
    }
  }
}
