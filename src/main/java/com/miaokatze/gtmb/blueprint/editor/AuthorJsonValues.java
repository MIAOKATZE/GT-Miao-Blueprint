package com.miaokatze.gtmb.blueprint.editor;

import java.math.BigDecimal;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Exact value comparison for already bounded author trees; Gson's numeric equality can round distinct values. */
final class AuthorJsonValues {

    private AuthorJsonValues() {}

    static boolean equal(JsonElement left, JsonElement right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left.isJsonNull() || right.isJsonNull()) return left.isJsonNull() && right.isJsonNull();
        if (left.isJsonObject()) {
            if (!right.isJsonObject()) return false;
            JsonObject a = left.getAsJsonObject(), b = right.getAsJsonObject();
            if (a.entrySet()
                .size()
                != b.entrySet()
                    .size())
                return false;
            for (Map.Entry<String, JsonElement> field : a.entrySet())
                if (!equal(field.getValue(), b.get(field.getKey()))) return false;
            return true;
        }
        if (left.isJsonArray()) {
            if (!right.isJsonArray()) return false;
            JsonArray a = left.getAsJsonArray(), b = right.getAsJsonArray();
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equal(a.get(i), b.get(i))) return false;
            return true;
        }
        if (!right.isJsonPrimitive()) return false;
        JsonPrimitive a = left.getAsJsonPrimitive(), b = right.getAsJsonPrimitive();
        if (a.isNumber() || b.isNumber()) {
            if (!a.isNumber() || !b.isNumber()) return false;
            String x = a.getAsString(), y = b.getAsString();
            if (x.equals(y)) return true;
            try {
                // Compare decimal values without expanding exponent notation into a plain decimal string.
                return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
            } catch (NumberFormatException outsideDecimalDomain) {
                // Admission owns validation. Never collapse different unrepresentable tokens via double/long.
                return false;
            }
        }
        if (a.isBoolean() || b.isBoolean())
            return a.isBoolean() && b.isBoolean() && a.getAsBoolean() == b.getAsBoolean();
        return a.getAsString()
            .equals(b.getAsString());
    }
}
