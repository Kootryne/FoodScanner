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
        String ingredients = findIngredientString(json);
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
                + ".json?fields=code,product_name,brands,ingredients_text,ingredients_text_sv,ingredients_text_en,ingredients,ingredients_hierarchy,ingredients_tags,image_front_url,image_url";
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

        String ingredients = firstNonEmpty(
                product.optString("ingredients_text_sv", ""),
                product.optString("ingredients_text", ""),
                product.optString("ingredients_text_en", ""),
                ingredientsFromArray(product.optJSONArray("ingredients")),
                tagsToReadableText(product.optJSONArray("ingredients_tags")),
                tagsToReadableText(product.optJSONArray("ingredients_hierarchy"))
        );

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

    private static String findIngredientString(Object value) throws JSONException {
        return findStringInsideMatchedKeys(
                value,
                0,
                new String[]{
                        "ingredientStatement",
                        "ingredientStatementDescription",
                        "ingredients",
                        "ingredientsText",
                        "ingredients_text",
                        "ingredients_text_sv",
                        "ingredients_text_en",
                        "ingredientList",
                        "ingredientInformation",
                        "composition"
                },
                new String[]{
                        "value",
                        "text",
                        "content",
                        "description",
                        "languageSpecificValue",
                        "languageSpecificStringValue",
                        "languageSpecificText",
                        "formattedText"
                }
        );
    }

    private static String findStringInsideMatchedKeys(Object value, int depth, String[] matchedKeys, String[] valueKeys) throws JSONException {
        if (value == null || depth > 6) {
            return null;
        }

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : matchedKeys) {
                if (object.has(key) && !object.isNull(key)) {
                    Object matchedValue = object.get(key);
                    String direct = stringifyJsonValue(matchedValue);
                    if (!isBlank(direct)) {
                        return direct.trim();
                    }
                    String nested = findString(matchedValue, 0, valueKeys);
                    if (!isBlank(nested)) {
                        return nested.trim();
                    }
                    String flattened = flattenTextValues(matchedValue, 0);
                    if (!isBlank(flattened)) {
                        return flattened.trim();
                    }
                }
            }

            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String found = findStringInsideMatchedKeys(object.opt(iterator.next()), depth + 1, matchedKeys, valueKeys);
                if (!isBlank(found)) {
                    return found.trim();
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findStringInsideMatchedKeys(array.opt(i), depth + 1, matchedKeys, valueKeys);
                if (!isBlank(found)) {
                    return found.trim();
                }
            }
        }
        return null;
    }

    private static String ingredientsFromArray(JSONArray ingredients) {
        if (ingredients == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ingredients.length(); i++) {
            Object item = ingredients.opt(i);
            String text = null;
            if (item instanceof JSONObject) {
                JSONObject object = (JSONObject) item;
                text = firstNonEmpty(
                        object.optString("text", ""),
                        object.optString("id", ""),
                        object.optString("name", "")
                );
            } else if (item instanceof String) {
                text = String.valueOf(item);
            }

            text = cleanIngredientToken(text);
            if (!isBlank(text)) {
                if (builder.length() > 0) builder.append(", ");
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private static String tagsToReadableText(JSONArray tags) {
        if (tags == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String text = cleanIngredientToken(tags.optString(i, ""));
            if (!isBlank(text)) {
                if (builder.length() > 0) builder.append(", ");
                builder.append(text);
            }
        }
        return builder.toString();
    }

    private static String cleanIngredientToken(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        int colon = cleaned.indexOf(':');
        if (colon >= 0 && colon < cleaned.length() - 1) {
            cleaned = cleaned.substring(colon + 1);
        }
        return cleaned.replace('-', ' ').trim();
    }

    private static String flattenTextValues(Object value, int depth) throws JSONException {
        if (value == null || depth > 4) {
            return "";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value).trim();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String item = flattenTextValues(array.opt(i), depth + 1);
                if (!isBlank(item)) {
                    if (builder.length() > 0) builder.append(", ");
                    builder.append(item.trim());
                }
            }
            return builder.toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String direct = firstNonEmpty(
                    object.optString("value", ""),
                    object.optString("text", ""),
                    object.optString("content", ""),
                    object.optString("description", ""),
                    object.optString("languageSpecificValue", ""),
                    object.optString("languageSpecificStringValue", ""),
                    object.optString("formattedText", "")
            );
            if (!isBlank(direct)) {
                return direct.trim();
            }
        }
        return "";
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
                    if (!isBlank(direct)) {
                        return direct.trim();
                    }
                }
            }
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String found = findString(object.opt(iterator.next()), depth + 1, keys);
                if (!isBlank(found)) {
                    return found.trim();
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findString(array.opt(i), depth + 1, keys);
                if (!isBlank(found)) {
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
                if (!isBlank(item)) {
                    if (builder.length() > 0) builder.append(", ");
                    builder.append(item.trim());
                }
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
