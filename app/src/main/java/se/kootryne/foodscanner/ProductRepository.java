package se.kootryne.foodscanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

final class ProductRepository {
    Product lookup(String gtin) throws IOException, JSONException {
        Product gs1Product = lookupGs1(gtin);
        if (gs1Product != null) {
            return gs1Product;
        }
        return lookupOpenFoodFacts(gtin);
    }

    private Product lookupGs1(String gtin) throws IOException, JSONException {
        String endpoint = BuildConfig.GS1_PRODUCT_ENDPOINT == null ? "" : BuildConfig.GS1_PRODUCT_ENDPOINT.trim();
        if (endpoint.isEmpty()) {
            return null;
        }

        String body = httpGet(buildGs1Url(endpoint, gtin), BuildConfig.GS1_API_KEY);
        JSONObject json = new JSONObject(body);

        String name = findString(json, 0,
                "productName", "tradeItemDescription", "regulatedProductName",
                "functionalName", "description", "name", "title");
        String ingredients = findString(json, 0,
                "ingredientStatement", "ingredientStatementDescription", "ingredients",
                "ingredientsText", "ingredients_text", "ingredientList", "composition");
        String imageUrl = findString(json, 0,
                "imageUrl", "productImageUrl", "image_front_url", "image_url", "image", "uri");
        String foundGtin = findString(json, 0, "gtin", "gtin13", "gtin14", "code");

        if (name == null && ingredients == null && imageUrl == null) {
            return null;
        }

        return new Product(name, emptyTo(foundGtin, gtin), ingredients, imageUrl, "GS1 Sweden ProductSearch");
    }

    private Product lookupOpenFoodFacts(String gtin) throws IOException, JSONException {
        String encoded = URLEncoder.encode(gtin, "UTF-8");
        String url = "https://world.openfoodfacts.org/api/v2/product/" + encoded
                + ".json?fields=code,product_name,brands,ingredients_text,image_front_url,image_url";
        JSONObject json = new JSONObject(httpGet(url, ""));
        if (json.optInt("status", 0) != 1) {
            return null;
        }

        JSONObject product = json.optJSONObject("product");
        if (product == null) {
            return null;
        }

        String name = product.optString("product_name", "").trim();
        String brand = product.optString("brands", "").trim();
        if (!brand.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(brand.toLowerCase(Locale.ROOT))) {
            name = brand + " – " + name;
        }

        String ingredients = product.optString("ingredients_text", "").trim();
        String imageUrl = product.optString("image_front_url", "").trim();
        if (imageUrl.isEmpty()) {
            imageUrl = product.optString("image_url", "").trim();
        }

        return new Product(name, emptyTo(product.optString("code", gtin), gtin), ingredients, imageUrl, "Open Food Facts fallback");
    }

    private static String buildGs1Url(String endpoint, String gtin) throws IOException {
        String encoded = URLEncoder.encode(gtin, "UTF-8");
        if (endpoint.contains("{gtin}")) {
            return endpoint.replace("{gtin}", encoded);
        }
        return endpoint + (endpoint.contains("?") ? "&" : "?") + "gtin=" + encoded;
    }

    private static String httpGet(String urlString, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "FoodScanner Android");

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            String cleanKey = apiKey.trim();
            connection.setRequestProperty("Authorization", "Bearer " + cleanKey);
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", cleanKey);
            connection.setRequestProperty("x-api-key", cleanKey);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readFully(stream);
        connection.disconnect();

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("HTTP " + statusCode + " från produktdatakällan: " + body);
        }
        return body;
    }

    private static String readFully(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (InputStream stream = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String findString(Object value, int depth, String... keys) throws JSONException {
        if (value == null || depth > 5) {
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : keys) {
                if (object.has(key) && !object.isNull(key)) {
                    String direct = stringifyJsonValue(object.get(key));
                    if (direct != null && !direct.trim().isEmpty()) {
                        return direct.trim();
                    }
                }
            }
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String found = findString(object.opt(iterator.next()), depth + 1, keys);
                if (found != null && !found.trim().isEmpty()) {
                    return found.trim();
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findString(array.opt(i), depth + 1, keys);
                if (found != null && !found.trim().isEmpty()) {
                    return found.trim();
                }
            }
        }
        return null;
    }

    private static String stringifyJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String item = stringifyJsonValue(array.opt(i));
                if (item != null && !item.trim().isEmpty()) {
                    if (builder.length() > 0) builder.append(", ");
                    builder.append(item.trim());
                }
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        return null;
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
