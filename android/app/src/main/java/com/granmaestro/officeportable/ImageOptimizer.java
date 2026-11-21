package com.granmaestro.officeportable;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Makes a "lighter copy" of a Word / Excel / PowerPoint file by shrinking
 * oversized embedded photos. Photos are what make office files heavy: a phone
 * photo is 12+ megapixels, and the editor has to hold, decode and convert all
 * of them. Resized to screen size they look the same on a phone, and the file
 * (and the memory needed to open and save it) drops several-fold.
 *
 * Only the picture bytes change. Names, formats and every XML part stay
 * the same, so layout, cropping and positioning are untouched.
 */
final class ImageOptimizer {
    /** Pictures smaller than this are never worth touching. */
    private static final long MIN_IMAGE_BYTES = 300 * 1024;
    /** Refuse to load a single picture entry bigger than this into memory. */
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final int JPEG_QUALITY = 85;

    interface Progress {
        void update(int done, int total);
    }

    static final class Scan {
        int candidates;
        long candidateBytes;
        long fileBytes;
    }

    static final class Result {
        int resized;
        long before;
        long after;
    }

    private ImageOptimizer() {}

    /** OOXML containers this works on (ODF needs its "mimetype" entry kept stored; skipped). */
    static boolean supports(String ext) {
        switch (ext) {
            case "docx": case "docm": case "dotx": case "dotm":
            case "xlsx": case "xlsm": case "xltx": case "xltm":
            case "pptx": case "pptm": case "ppsx": case "ppsm": case "potx": case "potm":
                return true;
            default:
                return false;
        }
    }

    private static boolean isPicture(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.contains("/media/")) return false;
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
    }

    /** Counts pictures big enough that resizing could matter. */
    static Scan scan(File file) throws IOException {
        Scan s = new Scan();
        s.fileBytes = file.length();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !isPicture(e.getName())) continue;
                long size = e.getSize();
                if (size >= MIN_IMAGE_BYTES) {
                    s.candidates++;
                    s.candidateBytes += size;
                }
            }
        }
        return s;
    }

    /**
     * Writes the lighter copy to {@code out}. Pictures that are already small
     * enough, can't be decoded, carry an EXIF rotation, or wouldn't get smaller
     * are copied unchanged.
     */
    static Result optimize(File in, File out, int maxDim, Progress progress) throws IOException {
        Result r = new Result();
        r.before = in.length();
        byte[] buf = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(in);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.setLevel(Deflater.BEST_SPEED);

            int total = 0;
            Enumeration<? extends ZipEntry> count = zip.entries();
            while (count.hasMoreElements()) {
                ZipEntry e = count.nextElement();
                if (!e.isDirectory() && isPicture(e.getName()) && e.getSize() >= MIN_IMAGE_BYTES) total++;
            }

            int done = 0;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                ZipEntry copy = new ZipEntry(e.getName());
                if (e.getTime() > 0) copy.setTime(e.getTime());
                zos.putNextEntry(copy);
                if (!e.isDirectory()) {
                    boolean candidate = isPicture(e.getName()) && e.getSize() >= MIN_IMAGE_BYTES
                            && e.getSize() <= MAX_IMAGE_BYTES;
                    if (candidate) {
                        byte[] original = readAll(zip.getInputStream(e), (int) e.getSize());
                        byte[] smaller = shrink(original, e.getName(), maxDim);
                        if (smaller != null) {
                            zos.write(smaller);
                            r.resized++;
                        } else {
                            zos.write(original);
                        }
                        done++;
                        if (progress != null) progress.update(done, total);
                    } else {
                        try (InputStream is = zip.getInputStream(e)) {
                            int n;
                            while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                    }
                }
                zos.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        r.after = out.length();
        return r;
    }

    /** Returns re-encoded bytes, or null to keep the original. */
    private static byte[] shrink(byte[] data, String name, int maxDim) {
        boolean png = name.toLowerCase(Locale.ROOT).endsWith(".png");
        try {
            if (!png && hasRotation(data)) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0 || Math.max(w, h) <= maxDim) return null;

            // Decode at the largest power-of-two reduction that stays >= maxDim,
            // so peak memory stays near the output size, not the photo's size.
            int sample = 1;
            while (Math.max(w, h) / (sample * 2) >= maxDim) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888; // 565 would band gradients
            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (decoded == null) return null;

            float scale = (float) maxDim / Math.max(decoded.getWidth(), decoded.getHeight());
            Bitmap scaled = decoded;
            if (scale < 1f) {
                int nw = Math.max(1, Math.round(decoded.getWidth() * scale));
                int nh = Math.max(1, Math.round(decoded.getHeight() * scale));
                scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true);
                if (scaled != decoded) decoded.recycle();
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 4);
            scaled.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    png ? 100 : JPEG_QUALITY, bos);
            scaled.recycle();
            byte[] out = bos.toByteArray();
            return out.length > 0 && out.length < data.length ? out : null;
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private static boolean hasRotation(byte[] jpeg) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return o != ExifInterface.ORIENTATION_NORMAL && o != ExifInterface.ORIENTATION_UNDEFINED;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in, int sizeHint) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(sizeHint > 0 ? sizeHint : 65536);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static void copy(InputStream in, File out) throws IOException {
        try (InputStream is = in; OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    static String mb(long bytes) {
        double v = bytes / (1024.0 * 1024.0);
        return v >= 10 ? String.format(Locale.US, "%.0f MB", v) : String.format(Locale.US, "%.1f MB", v);
    }
}
