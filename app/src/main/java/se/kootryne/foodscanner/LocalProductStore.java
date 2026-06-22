package se.kootryne.foodscanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

final class LocalProductStore {
    private static final String PREFS_NAME = "local_products";
    private final SharedPreferences preferences;

    LocalProductStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    Product getProduct(String gtin) {
        if (gtin == null || gtin.trim().isEmpty()) {
            return null;
        }

        String json = preferences.getString(gtin, null);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            JSONObject object = new JSONObject(json);
            return new Product(
                    object.optString("name", ""),
                    object.optString("gtin", gtin),
                    object.optString("ingredients", ""),
                    object.optString("imageUrl", ""),
                    object.optString("source", "Egen produkt (OCR)")
            );
        } catch (JSONException ignored) {
            return null;
        }
    }

    void saveProduct(Product product) throws JSONException {
        if (product == null || product.gtin == null || product.gtin.trim().isEmpty()) {
            return;
        }

        JSONObject object = new JSONObject();
        object.put("name", product.name == null ? "" : product.name);
        object.put("gtin", product.gtin);
        object.put("ingredients", product.ingredients == null ? "" : product.ingredients);
        object.put("imageUrl", product.imageUrl == null ? "" : product.imageUrl);
        object.put("source", product.source == null ? "Egen produkt (OCR)" : product.source);

        preferences.edit()
                .putString(product.gtin, object.toString())
                .apply();
    }
}
