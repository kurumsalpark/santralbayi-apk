package com.santralbayi.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class AndroidBridge {
    private final Activity activity;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    public AndroidBridge(Activity activity) {
        this.activity = activity;
        initTts();
    }

    private void initTts() {
        try {
            tts = new TextToSpeech(activity.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("tr", "TR"));
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {
            ttsReady = false;
        }
    }

    @JavascriptInterface
    public boolean isBridgeReady() {
        return true;
    }

    @JavascriptInterface
    public String getDeviceContacts() {
        JSONArray list = new JSONArray();
        try {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                return list.toString();
            }
            ContentResolver cr = activity.getContentResolver();
            Cursor cursor = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            );
            if (cursor != null) {
                Set<String> seen = new HashSet<>();
                int count = 0;
                int idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (cursor.moveToNext() && count < 500) {
                    String name = nameIdx >= 0 ? cursor.getString(nameIdx) : "";
                    String phone = numIdx >= 0 ? cursor.getString(numIdx) : "";
                    String id = idIdx >= 0 ? cursor.getString(idIdx) : String.valueOf(count);
                    if (phone == null || phone.trim().isEmpty()) continue;
                    String norm = phone.replaceAll("\\s+", "");
                    if (seen.contains(norm)) continue;
                    seen.add(norm);
                    JSONObject obj = new JSONObject();
                    obj.put("id", id);
                    obj.put("name", name != null ? name : norm);
                    obj.put("phone", phone.trim());
                    list.put(obj);
                    count++;
                }
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return list.toString();
    }

    @JavascriptInterface
    public String pickSingleContact() {
        // Web UI rehber listesi ve navigator.contacts.select ile anında seçim yapılır;
        // eğer cihaz rehberi senkronizeyse boş dönerek web seçicisinin çalışmasına izin verir.
        return "";
    }

    @JavascriptInterface
    public void startWarmTransferCall(String phoneNumber, String greetingText, String subjectText) {
        activity.runOnUiThread(() -> {
            try {
                if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;
                String cleanPhone = phoneNumber.replaceAll("[^0-9+*#]", "");
                if (cleanPhone.isEmpty()) return;

                boolean hasCallPerm = ContextCompat.checkSelfPermission(
                        activity, Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED;

                Intent intent = new Intent(hasCallPerm ? Intent.ACTION_CALL : Intent.ACTION_DIAL);
                intent.setData(Uri.parse("tel:" + cleanPhone));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void shareText(String title, String body) {
        activity.runOnUiThread(() -> {
            try {
                Intent sendIntent = new Intent(Intent.ACTION_SEND);
                sendIntent.setType("text/plain");
                if (title != null && !title.isEmpty()) {
                    sendIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                }
                sendIntent.putExtra(Intent.EXTRA_TEXT, body != null ? body : "");
                Intent chooser = Intent.createChooser(sendIntent, title != null ? title : "Paylaş");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(chooser);
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void shareWhatsAppImageToNumber(String dataUrl, String waPhone, String waJid) {
        shareImageInternal(dataUrl, waPhone, waJid, "");
    }

    @JavascriptInterface
    public void shareImageBase64(String dataUrl, String waPhone, String caption) {
        shareImageInternal(dataUrl, waPhone, "", caption);
    }

    private void shareImageInternal(String dataUrl, String waPhone, String waJid, String caption) {
        activity.runOnUiThread(() -> {
            try {
                if (dataUrl == null || dataUrl.isEmpty()) return;
                String base64Part = dataUrl.contains(",") ? dataUrl.substring(dataUrl.indexOf(",") + 1) : dataUrl;
                byte[] decoded = Base64.decode(base64Part, Base64.DEFAULT);

                File cacheFile = new File(activity.getCacheDir(), "santralbayi-belge.png");
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(decoded);
                    fos.flush();
                }

                Uri contentUri = FileProvider.getUriForFile(
                        activity,
                        activity.getPackageName() + ".fileprovider",
                        cacheFile
                );

                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                if (caption != null && !caption.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_TEXT, caption);
                }
                if (waJid != null && !waJid.isEmpty()) {
                    intent.putExtra("jid", waJid);
                } else if (waPhone != null && !waPhone.isEmpty()) {
                    String digits = waPhone.replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) {
                        intent.putExtra("jid", digits + "@s.whatsapp.net");
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setPackage("com.whatsapp");

                try {
                    activity.startActivity(intent);
                } catch (Exception notInstalled) {
                    intent.setPackage(null);
                    Intent chooser = Intent.createChooser(intent, "Belgeyi Paylaş");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(chooser);
                }
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void speakText(String text) {
        try {
            if (!ttsReady || tts == null) {
                initTts();
            }
            if (ttsReady && tts != null && text != null && !text.trim().isEmpty()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sb_tts_utterance");
            }
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void stopSpeaking() {
        try {
            if (tts != null) {
                tts.stop();
            }
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void restartCallScreeningService() {
        activity.runOnUiThread(() -> {
            stopSpeaking();
            initTts();
        });
    }
}
