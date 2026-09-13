package com.pishi.hotfix;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * A ready-made {@link PatchManipulate} that needs nothing but a manifest URL:
 *
 * <pre>
 * new PatchExecutor(getApplicationContext(),
 *         new SimpleHttpPatchManipulator("https://cdn.example.com/patches/manifest.json"),
 *         new RobustCallBackSample()).start();
 * </pre>
 *
 * The manifest is plain JSON served by any static host (built automatically by the
 * pishi-autopatch Gradle plugin as patch-manifest.json):
 *
 * <pre>
 * {
 *   "appVersionCode": 1,
 *   "appVersionName": "1.0",
 *   "patchVersion": 2,
 *   "url": "https://cdn.example.com/patches/patch-2.jar",
 *   "md5": "9f8b3c..."
 * }
 * </pre>
 *
 * Security: patches are remote code — always serve the manifest and the jar over HTTPS
 * and keep the md5 (or a stronger signature scheme) in place.
 */
public class SimpleHttpPatchManipulator extends PatchManipulate {

    private static final String TAG = "pishi";

    private final String manifestUrl;

    public SimpleHttpPatchManipulator(String manifestUrl) {
        this.manifestUrl = manifestUrl;
    }

    @Override
    protected List<Patch> fetchPatchList(Context context) {
        List<Patch> patches = new ArrayList<>();
        try {
            String body = httpGet(manifestUrl);
            if (body == null || body.isEmpty()) {
                return patches;
            }
            JSONObject manifest = new JSONObject(body);
            long manifestVersionCode = manifest.optLong("appVersionCode", -1);
            long currentVersionCode = versionCode(context);
            if (manifestVersionCode > 0 && manifestVersionCode != currentVersionCode) {
                Log.i(TAG, "pishi: patch manifest targets versionCode " + manifestVersionCode
                        + ", current is " + currentVersionCode + " — skipping");
                return patches;
            }
            String url = manifest.optString("url", "");
            if (url.isEmpty()) {
                Log.i(TAG, "pishi: manifest has no patch url — nothing to apply");
                return patches;
            }
            Patch patch = new Patch();
            patch.setName("patch-" + manifest.optInt("patchVersion", 1));
            patch.setUrl(url);
            patch.setMd5(manifest.optString("md5", ""));
            patch.setPatchesInfoImplClassFullName(com.pishi.hotfix.Constants.PATCH_PACKAGENAME + ".PatchesInfoImpl");
            patch.setLocalPath(new File(context.getCacheDir(), "pishi/patch.jar").getAbsolutePath());
            patches.add(patch);
        } catch (Exception e) {
            Log.w(TAG, "pishi: fetchPatchList failed", e);
        }
        return patches;
    }

    @Override
    protected boolean verifyPatch(Context context, Patch patch) {
        try {
            File downloaded = new File(patch.getLocalPath());
            downloaded.getParentFile().mkdirs();
            download(patch.getUrl(), downloaded);
            String md5 = patch.getMd5();
            if (md5 != null && !md5.isEmpty()) {
                String actual = md5Of(downloaded);
                if (!md5.equalsIgnoreCase(actual)) {
                    Log.w(TAG, "pishi: patch md5 mismatch, expected " + md5 + " got " + actual + " — rejected");
                    return false;
                }
            }
            File temp = new File(context.getCacheDir(), "pishi/patch.temp.jar");
            copy(downloaded, temp);
            patch.setTempPath(temp.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "pishi: verifyPatch failed", e);
            return false;
        }
    }

    @Override
    protected boolean ensurePatchExist(Patch patch) {
        return true;
    }

    private static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "pishi: manifest http " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("patch download http " + conn.getResponseCode());
            }
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.close();
            in.close();
        } finally {
            conn.disconnect();
        }
    }

    private static String md5Of(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        InputStream in = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            digest.update(buffer, 0, n);
        }
        in.close();
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void copy(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
